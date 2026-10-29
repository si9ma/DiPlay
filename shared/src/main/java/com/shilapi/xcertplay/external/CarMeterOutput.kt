package com.shilapi.xcertplay.external

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 外接设备 CarMeter 的数据输出：BLE 主链路(中心) + Wi-Fi/UDP 备用链路。
 *
 * - BLE：扫描自定义服务 a5e80001-… 的 CarMeter 广播 → 连接 → 向 RX 特征写 JSON 帧。
 * - UDP：BLE 未连上但配置了仪表 IP 时，向 ip:8899 发同样的帧(仪表端聚合链路状态)。
 * - 导航帧来自 BydNavigationOutputs 的转向卡片(含到达时间/剩余距离)。
 * - 对时帧在连接后立即发一次，之后每 60 秒一次。
 *
 * 线程：BLE 回调在 binder 线程，UDP 在单线程池；全部经状态锁同步。
 */
object CarMeterOutput {
    private const val TAG = "CarMeterOutput"

    /** CarMeter 固件 ble_car.c 定义的自定义服务/特征(128-bit) */
    private val SERVICE_UUID = java.util.UUID.fromString("a5e80001-9c6f-4b21-8d3a-0e1f2a3b4c5d")
    private val RX_UUID = java.util.UUID.fromString("a5e80002-9c6f-4b21-8d3a-0e1f2a3b4c5d")

    enum class Transport { BLE, UDP }

    data class Status(
        val connected: Boolean,
        val transport: Transport?,
        val deviceName: String?,
        val deviceAddress: String?,
        val scanning: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val udpExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "carmeter-udp").apply { isDaemon = true } }

    private val lock = Any()
    private var appContext: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var connectedAddress: String? = null
    private var connectedName: String? = null
    private val writeQueue = ConcurrentLinkedQueue<String>()
    private var writing = false

    /** 最近一次下发的导航帧与时间，用于去重与保活 */
    private val lastNavFrame = AtomicReference<String?>(null)
    private val lastNavSentAt = java.util.concurrent.atomic.AtomicLong(0)

    private var timeTick: Runnable? = null
    private var statusListener: ((Status) -> Unit)? = null
    private val foundDevices = LinkedHashMap<String, String>()   // address -> name
    private var devicePicker: ((Map<String, String>) -> Unit)? = null

    /** UI 注册状态回调（主线程回调） */
    fun setStatusListener(listener: ((Status) -> Unit)?) {
        statusListener = listener
        publishStatus()
    }

    fun status(): Status = synchronized(lock) {
        val ble = rxCharacteristic != null && connectedAddress != null
        val udp = udpActive()
        Status(
            connected = ble || udp,
            transport = if (ble) Transport.BLE else if (udp) Transport.UDP else null,
            deviceName = connectedName,
            deviceAddress = connectedAddress,
            scanning = scanning,
        )
    }

    private fun udpActive(): Boolean {
        val context = appContext ?: return false
        val connected = synchronized(lock) { connectedAddress != null }
        return !connected && CarMeterSettings.enabled(context) && CarMeterSettings.udpHost(context).isNotEmpty()
    }

    private fun publishStatus() {
        val snapshot = status()
        handler.post { statusListener?.invoke(snapshot) }
    }

    /** 供电入口：设置页总开关/应用打开时调用(见 BydNavigationOutputs.onAppOpened) */
    fun start(context: Context) {
        synchronized(lock) { appContext = context.applicationContext }
        if (!CarMeterSettings.enabled(context)) { stop() ; return }
        ensureAdapter(context)
        val last = CarMeterSettings.lastDevice(context)
        if (last != null && synchronized(lock) { connectedAddress == null }) connectAddress(context, last, CarMeterSettings.lastDeviceName(context))
        else if (last == null && synchronized(lock) { !scanning } && hasBlePermission(context)) startScan(context, null)
        publishStatus()
    }

    fun stop() {
        stopTimeTick()
        stopScan()
        disconnectGatt()
        publishStatus()
    }

    /** UI「扫描/连接」：无权限返回 false 由 UI 先请求权限 */
    fun scan(context: Context, onDevices: (Map<String, String>) -> Unit): Boolean {
        ensureAdapter(context)
        if (!hasBlePermission(context)) return false
        devicePicker = onDevices
        foundDevices.clear()
        startScan(context, null)
        return true
    }

    fun connectAddress(context: Context, address: String, name: String?) {
        if (!hasBlePermission(context)) return
        stopScan()
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) { null } ?: run {
            Log.w(TAG, "unknown address $address")
            return
        }
        synchronized(lock) {
            if (connectedAddress == address) return
            disconnectGattLocked()
        }
        Log.i(TAG, "connecting $address")
        val connected = try {
            if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else @Suppress("DEPRECATION") device.connectGatt(context, false, gattCallback)
        } catch (_: SecurityException) { null } ?: run {
            publishStatus()
            return
        }
        synchronized(lock) { gatt = connected }
        publishStatus()
    }

    fun disconnect() {
        val context = appContext ?: return
        CarMeterSettings.clearLastDevice(context)
        stopTimeTick()
        disconnectGatt()
        publishStatus()
    }

    // ---- 导航/对时入口(BydNavigationOutputs 调用) ----

    fun onGuidance(guidance: ClusterTurnGuidance?) {
        val context = appContext ?: return
        if (!CarMeterSettings.sendNavigation(context)) return
        val now = System.currentTimeMillis()
        val active = guidance != null
        val frame = CarMeterFrames.nav(
            active,
            guidance?.arrivalEpochSeconds,
            guidance?.remainingSeconds,
            guidance?.remainingMeters,
            now,
        ) ?: if (active) null else "{\"nav\":0,\"eta\":0,\"rem_m\":0}"
        if (frame == null) return
        // 去重：内容相同且 8 秒内已发过则跳过(仪表靠状态保持)；
        // 超过 8 秒重发一次作保活；导航收尾帧只发一次。
        val nowMs = System.currentTimeMillis()
        val last = lastNavFrame.get()
        val activeFrame = frame.startsWith("{\"nav\":1")
        if (frame == last) {
            if (!activeFrame) return
            if (nowMs - lastNavSentAt.get() < 8_000) return
        }
        lastNavFrame.set(frame)
        lastNavSentAt.set(nowMs)
        send(frame)
    }

    fun sendTimeNow() {
        val context = appContext ?: return
        if (!CarMeterSettings.sendTime(context)) return
        send(CarMeterFrames.time(System.currentTimeMillis()))
    }

    // ---- 测试帧(设置页) ----

    fun testSend(frame: String) {
        lastNavFrame.set(null)
        send(frame)
    }

    // ---- 帧发送：BLE 优先，未连接时走 UDP ----

    private fun send(frame: String) {
        val context = appContext ?: return
        if (!CarMeterSettings.enabled(context)) return
        val overBle = synchronized(lock) { connectedAddress != null }
        if (overBle) enqueueWrite(frame) else sendUdp(frame)
    }

    @SuppressLint("MissingPermission")
    private fun enqueueWrite(frame: String) {
        writeQueue.add(frame)
        pumpWrites()
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrites() {
        val g = synchronized(lock) { gatt } ?: return
        val rx = synchronized(lock) { rxCharacteristic } ?: return
        if (writing) return
        val next = writeQueue.poll() ?: return
        writing = true
        rx.value = next.toByteArray(Charsets.UTF_8)
        val ok = try {
            g.writeCharacteristic(rx)
        } catch (_: SecurityException) { false }
        if (!ok) {
            writing = false
            Log.w(TAG, "writeCharacteristic failed")
        }
    }

    private fun sendUdp(frame: String) {
        val context = appContext ?: return
        val host = CarMeterSettings.udpHost(context)
        if (host.isEmpty()) return
        udpExecutor.execute {
            try {
                DatagramSocket().use { socket ->
                    val bytes = frame.toByteArray(Charsets.UTF_8)
                    val address = InetAddress.getByName(host)
                    socket.send(DatagramPacket(bytes, bytes.size, address, CarMeterSettings.UDP_PORT))
                }
            } catch (e: IOException) {
                Log.w(TAG, "udp send to $host failed: ${e.message}")
            }
        }
    }

    // ---- BLE 扫描 ----

    private fun ensureAdapter(context: Context) {
        if (adapter == null) {
            adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }
    }

    fun hasBlePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "CarMeter"
            foundDevices[result.device.address] = name
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            synchronized(lock) { scanning = false }
            publishStatus()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(context: Context, done: (() -> Unit)?) {
        val scanner = adapter?.bluetoothLeScanner ?: run { publishStatus(); return }
        synchronized(lock) {
            if (scanning) return
            scanning = true
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (_: SecurityException) {
            synchronized(lock) { scanning = false }
            publishStatus()
            return
        }
        publishStatus()
        handler.postDelayed({
            stopScan()
            val snapshot = LinkedHashMap(foundDevices)
            val picker = devicePicker
            if (picker != null) {
                picker.invoke(snapshot)
                devicePicker = null
            } else if (snapshot.isNotEmpty()) {
                // 自动模式：连第一台发现的 CarMeter
                val (address, name) = snapshot.entries.first()
                connectAddress(context, address, name)
            }
            done?.invoke()
        }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val scanner = try { adapter?.bluetoothLeScanner } catch (_: SecurityException) { null } ?: run {
            synchronized(lock) { scanning = false }
            return
        }
        synchronized(lock) {
            if (!scanning) return
            scanning = false
        }
        try { scanner.stopScan(scanCallback) } catch (_: SecurityException) {}
    }

    // ---- GATT ----

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() { synchronized(lock) { disconnectGattLocked() } }

    @SuppressLint("MissingPermission")
    private fun disconnectGattLocked() {
        val g = gatt ?: return
        gatt = null
        rxCharacteristic = null
        connectedAddress = null
        connectedName = null
        writing = false
        writeQueue.clear()
        try { g.disconnect(); g.close() } catch (_: SecurityException) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                try { g.discoverServices() } catch (_: SecurityException) {}
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = synchronized(lock) { connectedAddress }
                val context = appContext
                disconnectGatt()
                stopTimeTick()
                lastNavFrame.set(null)
                if (wasConnected != null && context != null &&
                    CarMeterSettings.enabled(context) && CarMeterSettings.autoReconnect(context)) {
                    handler.postDelayed({
                        val ctx = appContext ?: return@postDelayed
                        if (CarMeterSettings.enabled(ctx) && CarMeterSettings.autoReconnect(ctx) &&
                            synchronized(lock) { connectedAddress == null }) {
                            connectAddress(ctx, wasConnected, CarMeterSettings.lastDeviceName(ctx))
                        }
                    }, 3_000)
                }
                publishStatus()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "service discovery $status"); return }
            val service: BluetoothGattService? = g.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            if (rx == null) {
                Log.w(TAG, "CarMeter service not found")
                return
            }
            val name = try { g.device.name } catch (_: SecurityException) { null } ?: "CarMeter"
            synchronized(lock) {
                rxCharacteristic = rx
                connectedAddress = g.device.address
                connectedName = name
            }
            try { g.requestMtu(247) } catch (_: SecurityException) {}
            appContext?.let { CarMeterSettings.setLastDevice(it, g.device.address, name) }
            if (CarMeterSettings.sendTime(appContext!!)) sendTimeNow()
            startTimeTick()
            publishStatus()
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            pumpWrites()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write status $status")
            pumpWrites()
        }
    }

    private fun startTimeTick() {
        stopTimeTick()
        val tick = object : Runnable {
            override fun run() {
                sendTimeNow()
                handler.postDelayed(this, 60_000)
            }
        }
        timeTick = tick
        handler.postDelayed(tick, 60_000)
    }

    private fun stopTimeTick() {
        timeTick?.let { handler.removeCallbacks(it) }
        timeTick = null
    }
}
