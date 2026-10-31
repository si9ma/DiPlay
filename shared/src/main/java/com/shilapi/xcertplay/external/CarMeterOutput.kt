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
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 外接设备 CarMeter 的数据输出：BLE 主链路(中心) + Wi-Fi/UDP 备用链路。
 *
 * - BLE：扫描自定义服务 a5e80001-… 的 CarMeter 广播 → 连接 → 向 RX 特征写 JSON 帧。
 *   启用后尽力保持连接：首连失败(133 等)、连接超时、服务发现失败、断开后都按退避重试
 *   (2s→4s→8s→15s→30s 封顶)，每第 3 次改为重新扫描(上次设备不在/换了板子)。
 * - UDP：BLE 未连上时向 CarMeter 的 IP:8899 发同样的帧。IP 取设置里填的地址；没填时自动发现：
 *   每几秒向各网卡广播地址 + 192.168.4.1(CarMeter 热点网关)发 `{"hello":1}`，固件回
 *   `{"carmeter":1}` 即记下对端 IP(推送导航地图也用它)。Wi-Fi 状态因此是真实可达，而不只是"填了地址"。
 * - 导航帧来自 BydNavigationOutputs 的转向卡片(含到达时间/剩余距离)；按「外接设备显示内容」
 *   (CarMeterSettings.content)过滤：不显示导航时只发 nav:0。导航开始/结束通知 [addNavigationListener]。
 * - 对时帧在连上(BLE 或 Wi-Fi)后立即发一次，之后每 60 秒一次。
 *
 * 线程：BLE 回调在 binder 线程，UDP 在单线程池，发现在独立线程；全部经状态锁同步。
 */
object CarMeterOutput {
    private const val TAG = "CarMeterOutput"

    /** CarMeter 固件 ble_car.c 定义的自定义服务/特征(128-bit) */
    private val SERVICE_UUID = java.util.UUID.fromString("a5e80001-9c6f-4b21-8d3a-0e1f2a3b4c5d")
    private val RX_UUID = java.util.UUID.fromString("a5e80002-9c6f-4b21-8d3a-0e1f2a3b4c5d")

    private val RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val SCAN_MS = 10_000L
    private const val WIFI_ALIVE_MS = 16_000L

    enum class Transport { BLE, UDP }

    data class Status(
        val connected: Boolean,
        val transport: Transport?,
        val deviceName: String?,
        val deviceAddress: String?,
        val scanning: Boolean,
        /** Wi-Fi 对端：设置里填的或自动发现的 CarMeter IP；null = 未知 */
        val wifiHost: String? = null,
        /** 最近收到过 CarMeter 对发现包的回应(Wi-Fi 确实可达) */
        val wifiVerified: Boolean = false,
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
    @Volatile private var lastGuidance: ClusterTurnGuidance? = null

    /** iPhone 正在导航(由 [onGuidance] 维护) */
    @Volatile var navigating = false
        private set
    private val navigationListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    @Volatile private var timeTick: Runnable? = null
    private var statusListener: ((Status) -> Unit)? = null
    private val foundDevices = LinkedHashMap<String, String>()   // address -> name
    private var devicePicker: ((Map<String, String>) -> Unit)? = null

    // ---- BLE 重试 ----
    private var retryAttempts = 0
    /** 用户点了「断开」：在下次 start/扫描/手动连接前不自动重试 */
    @Volatile private var retrySuppressed = false
    private val retryRunnable = Runnable { retryNow() }
    private var connectTimeout: Runnable? = null

    // ---- Wi-Fi 发现 ----
    @Volatile private var discoveryThread: Thread? = null
    @Volatile private var discoveredHost: String? = null
    @Volatile private var lastHelloReplyAt = 0L

    /**
     * CarMeter 上报的视频预旋转角(0..359，帧编码前绕中心顺时针转多少度，见 [CarMeterVideo])；
     * -1 = 未知(还没收到 / 老固件不报)。来源：发现包回复，以及推流 socket 收到的命令回复/变化推送
     * (经 [noteRotation])。推流线程每帧读。
     */
    @Volatile var deviceRotation: Int = -1
        private set

    fun noteRotation(rotation: Int) {
        val r = ((rotation % 360) + 360) % 360
        if (r != deviceRotation) Log.i(TAG, "CarMeter screen rotation: pre-rotate video $r°")
        deviceRotation = r
    }

    /** UI 注册状态回调（主线程回调） */
    fun setStatusListener(listener: ((Status) -> Unit)?) {
        statusListener = listener
        publishStatus()
    }

    fun status(): Status = synchronized(lock) {
        val ble = rxCharacteristic != null && connectedAddress != null
        val context = appContext
        val host = context?.let { udpTarget(it) }?.takeIf { it.isNotEmpty() }
        val verified = wifiAlive()
        val udp = !ble && context != null && CarMeterSettings.enabled(context) && host != null
        Status(
            connected = ble || udp,
            transport = if (ble) Transport.BLE else if (udp) Transport.UDP else null,
            deviceName = if (ble) connectedName else host,
            deviceAddress = if (ble) connectedAddress else host,
            scanning = scanning,
            wifiHost = host,
            wifiVerified = verified,
        )
    }

    private fun wifiAlive(): Boolean =
        lastHelloReplyAt != 0L && SystemClock.elapsedRealtime() - lastHelloReplyAt < WIFI_ALIVE_MS

    /** 导航帧/对时帧的 UDP 目标：设置里填的优先，否则自动发现的；都没有 = 不走 UDP */
    private fun udpTarget(context: Context): String =
        CarMeterSettings.udpHost(context).ifEmpty { discoveredHost ?: "" }

    /** 推送导航地图的目标：同上，都没有时退到 CarMeter 热点网关 192.168.4.1 */
    fun videoHost(context: Context): String = udpTarget(context).ifEmpty { CarMeterVideo.DEFAULT_HOST }

    /** 强制仪表导航展示模式(外部指令 {"navm":N}，UDP 8899 与视频命令同通道，见
     *  [CarMeterVideo.NAV_MODE_FULL] 等)。覆盖设备侧 PWR 键设置；任意线程可调，
     *  设备不可达时静默失败(只打日志)。 */
    fun sendNavMode(context: Context, mode: Int) {
        val host = videoHost(context)
        val bytes = CarMeterVideo.navModeCommand(mode).toByteArray(Charsets.UTF_8)
        Thread({
            try {
                DatagramSocket().use { s ->
                    s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), CarMeterSettings.UDP_PORT))
                }
                Log.i(TAG, "nav mode command sent: $mode -> $host")
            } catch (e: Exception) {
                Log.w(TAG, "sendNavMode failed: ${e.message}")
            }
        }, "carmeter-navm").apply { isDaemon = true; start() }
    }

    private fun publishStatus() {
        val snapshot = status()
        handler.post { statusListener?.invoke(snapshot) }
    }

    /** 供电入口：DiPlay/CarPlay 页面打开、设置页总开关时调用。幂等。 */
    fun start(context: Context) {
        synchronized(lock) { appContext = context.applicationContext }
        if (!CarMeterSettings.enabled(context)) { stop() ; return }
        retrySuppressed = false
        ensureAdapter(context)
        startDiscovery()
        val last = CarMeterSettings.lastDevice(context)
        val idle = synchronized(lock) { connectedAddress == null && gatt == null && !scanning }
        if (idle && hasBlePermission(context)) {
            if (last != null) connectAddress(context, last, CarMeterSettings.lastDeviceName(context))
            else startScan(context, null)
        }
        publishStatus()
    }

    fun stop() {
        handler.removeCallbacks(retryRunnable)
        cancelConnectTimeout()
        stopTimeTick()
        stopScan()
        disconnectGatt()
        stopDiscovery()
        publishStatus()
    }

    /** UI「扫描/连接」：无权限返回 false 由 UI 先请求权限 */
    fun scan(context: Context, onDevices: (Map<String, String>) -> Unit): Boolean {
        ensureAdapter(context)
        if (!hasBlePermission(context)) return false
        retrySuppressed = false
        handler.removeCallbacks(retryRunnable)
        devicePicker = onDevices
        foundDevices.clear()
        startScan(context, null)
        return true
    }

    fun connectAddress(context: Context, address: String, name: String?) {
        if (!hasBlePermission(context)) return
        retrySuppressed = false
        handler.removeCallbacks(retryRunnable)
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
            scheduleRetry("connectGatt failed")
            return
        }
        synchronized(lock) { gatt = connected }
        // 连接一直不成也不失败(设备不在附近)时主动放弃重来，免得卡在半连接
        cancelConnectTimeout()
        val timeout = Runnable {
            val stuck = synchronized(lock) { gatt === connected && connectedAddress == null }
            if (stuck) {
                Log.w(TAG, "connect timeout $address")
                disconnectGatt()
                scheduleRetry("connect timeout")
                publishStatus()
            }
        }
        connectTimeout = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        publishStatus()
    }

    fun disconnect() {
        val context = appContext ?: return
        CarMeterSettings.clearLastDevice(context)
        retrySuppressed = true
        handler.removeCallbacks(retryRunnable)
        cancelConnectTimeout()
        stopTimeTick()
        disconnectGatt()
        publishStatus()
    }

    /**
     * 尽快重试 BLE(重置退避)。推送导航地图结束时调用：视频期间固件会停掉 BLE，
     * 结束后几秒内重新广播，别让退避把回连拖到半分钟后。
     */
    fun kick() {
        handler.post {
            val context = appContext ?: return@post
            if (!CarMeterSettings.enabled(context) || retrySuppressed) return@post
            retryAttempts = 0
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, 3_000)
        }
    }

    private fun scheduleRetry(reason: String, reset: Boolean = false) {
        handler.post {
            if (reset) retryAttempts = 0
            val context = appContext ?: return@post
            if (!CarMeterSettings.enabled(context) || !CarMeterSettings.autoReconnect(context)) return@post
            if (retrySuppressed || !hasBlePermission(context)) return@post
            if (synchronized(lock) { connectedAddress != null || gatt != null || scanning }) return@post
            val delay = RETRY_DELAYS_MS[retryAttempts.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
            retryAttempts++
            Log.i(TAG, "BLE retry #$retryAttempts in ${delay}ms ($reason)")
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, delay)
        }
    }

    private fun retryNow() {
        val context = appContext ?: return
        if (!CarMeterSettings.enabled(context) || retrySuppressed) return
        if (synchronized(lock) { connectedAddress != null || gatt != null || scanning }) return
        val last = CarMeterSettings.lastDevice(context)
        // 每第 3 次改扫描：上次那块板子可能不在了，或者地址变了
        if (last != null && retryAttempts % 3 != 0) connectAddress(context, last, CarMeterSettings.lastDeviceName(context))
        else startScan(context, null)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
    }

    // ---- 导航/对时入口(BydNavigationOutputs 调用) ----

    fun addNavigationListener(listener: (Boolean) -> Unit) { navigationListeners.add(listener) }

    fun removeNavigationListener(listener: (Boolean) -> Unit) { navigationListeners.remove(listener) }

    fun onGuidance(guidance: ClusterTurnGuidance?) {
        lastGuidance = guidance
        val active = guidance != null
        if (active != navigating) {
            navigating = active
            Log.i(TAG, "navigation ${if (active) "started" else "ended"}")
            handler.post { navigationListeners.forEach { it(active) } }
        }
        val context = appContext ?: return
        if (!CarMeterSettings.sendNavigation(context)) return
        // 「不显示导航」：手表保持表盘，只发结束帧
        val show = active && CarMeterSettings.content(context) != CarMeterSettings.Content.NONE
        val now = System.currentTimeMillis()
        val frame = CarMeterFrames.nav(
            show,
            guidance?.arrivalEpochSeconds,
            guidance?.remainingSeconds,
            guidance?.remainingMeters,
            now,
        ) ?: if (show) null else "{\"nav\":0,\"eta\":0,\"rem_m\":0}"
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

    /** 「外接设备显示内容」改了：按当前导航状态立刻重发(不显示导航 → 马上 nav:0) */
    fun refreshNavigation() {
        lastNavFrame.set(null)
        onGuidance(lastGuidance)
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
        // peek：写失败(比如 MTU 协商还没完)时帧留在队首，onMtuChanged/下一次写完成后再试
        val next = writeQueue.peek() ?: return
        writing = true
        rx.value = next.toByteArray(Charsets.UTF_8)
        val ok = try {
            g.writeCharacteristic(rx)
        } catch (_: SecurityException) { false }
        if (ok) {
            writeQueue.poll()
        } else {
            writing = false
            Log.w(TAG, "writeCharacteristic busy/failed, will retry")
        }
    }

    private fun sendUdp(frame: String) {
        val context = appContext ?: return
        val host = udpTarget(context)
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

    // ---- Wi-Fi 发现 ----

    private fun startDiscovery() {
        synchronized(lock) {
            if (discoveryThread != null) return
            discoveryThread = Thread({ discoveryLoop() }, "carmeter-discover").apply { isDaemon = true; start() }
        }
    }

    private fun stopDiscovery() {
        synchronized(lock) { discoveryThread = null }
    }

    private fun discoveryLoop() {
        val me = Thread.currentThread()
        val hello = CarMeterVideo.HELLO.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(256)
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1_000
                while (discoveryThread === me) {
                    val context = appContext ?: break
                    if (!CarMeterSettings.enabled(context)) break
                    for (target in discoveryTargets(context)) {
                        try { socket.send(DatagramPacket(hello, hello.size, target, CarMeterSettings.UDP_PORT)) }
                        catch (_: IOException) { }   // 某张网卡没起来/不可达：跳过
                    }
                    // 已找到时每 5 秒一次：比固件 UDP 链路 10 秒超时短，手表上的"网络已连接"不会闪断
                    val wasAlive = wifiAlive()
                    val until = SystemClock.elapsedRealtime() + if (wasAlive) 5_000 else 3_000
                    while (discoveryThread === me && SystemClock.elapsedRealtime() < until) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            if (text.contains("\"carmeter\"")) {
                                onHelloReply(packet.address)
                                CarMeterVideo.parseRotation(text)?.let { noteRotation(it) }
                            }
                        } catch (_: SocketTimeoutException) { }
                    }
                    if (wasAlive && !wifiAlive()) {
                        Log.i(TAG, "Wi-Fi peer lost")
                        publishStatus()
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "discovery stopped: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "discovery not permitted: ${e.message}")
        }
        synchronized(lock) { if (discoveryThread === me) discoveryThread = null }
    }

    private fun onHelloReply(address: InetAddress) {
        val host = address.hostAddress ?: return
        val wasAlive = wifiAlive()
        val changed = host != discoveredHost
        discoveredHost = host
        lastHelloReplyAt = SystemClock.elapsedRealtime()
        if (changed || !wasAlive) {
            Log.i(TAG, "CarMeter on Wi-Fi at $host")
            publishStatus()
            // 只有 Wi-Fi 时也要对时(BLE 连上时 onServicesDiscovered 已经发过)
            handler.post {
                if (synchronized(lock) { connectedAddress == null }) {
                    sendTimeNow()
                    if (timeTick == null) startTimeTick()
                    refreshNavigation()
                }
            }
        }
    }

    /** 全局广播 + 各网卡的子网广播 + CarMeter 热点网关 + 设置里填的地址 */
    private fun discoveryTargets(context: Context): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNull { it.broadcast }
                .forEach { targets.add(it) }
        }
        runCatching { targets.add(InetAddress.getByName(CarMeterVideo.DEFAULT_HOST)) }
        CarMeterSettings.udpHost(context).takeIf { it.isNotEmpty() }?.let { host ->
            runCatching { targets.add(InetAddress.getByName(host)) }
        }
        return targets.toList()
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
            scheduleRetry("scan failed $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(context: Context, done: (() -> Unit)?) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            publishStatus()
            scheduleRetry("bluetooth off")
            return
        }
        synchronized(lock) {
            if (scanning) return
            scanning = true
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
                // 自动模式：优先回连上次那台，否则连第一台发现的 CarMeter
                val last = CarMeterSettings.lastDevice(context)
                val (address, name) = snapshot.entries.firstOrNull { it.key == last } ?: snapshot.entries.first()
                connectAddress(context, address, name)
            } else {
                scheduleRetry("scan found nothing")
            }
            publishStatus()
            done?.invoke()
        }, SCAN_MS)
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
                val ours = synchronized(lock) { gatt === g }
                if (!ours) {
                    // 已被替换/主动断开的旧连接：只释放它
                    try { g.close() } catch (_: SecurityException) {}
                    return
                }
                val wasConnected = synchronized(lock) { connectedAddress }
                Log.i(TAG, "disconnected status=$status wasConnected=${wasConnected != null}")
                handler.post { cancelConnectTimeout() }
                disconnectGatt()
                stopTimeTick()
                lastNavFrame.set(null)
                // 连上过：从最短间隔开始回连
                scheduleRetry(if (wasConnected != null) "link lost" else "connect failed $status", reset = wasConnected != null)
                publishStatus()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service: BluetoothGattService? = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(SERVICE_UUID) else null
            val rx = service?.getCharacteristic(RX_UUID)
            if (rx == null) {
                Log.w(TAG, "CarMeter service not found (status $status)")
                disconnectGatt()
                scheduleRetry("service discovery")
                publishStatus()
                return
            }
            val name = try { g.device.name } catch (_: SecurityException) { null } ?: "CarMeter"
            synchronized(lock) {
                rxCharacteristic = rx
                connectedAddress = g.device.address
                connectedName = name
            }
            handler.post {
                cancelConnectTimeout()
                retryAttempts = 0
            }
            try { g.requestMtu(247) } catch (_: SecurityException) {}
            appContext?.let { CarMeterSettings.setLastDevice(it, g.device.address, name) }
            if (CarMeterSettings.sendTime(appContext!!)) sendTimeNow()
            startTimeTick()
            // 连上后按当前导航状态/显示内容补发一帧，手表不用等下一次路线变化
            refreshNavigation()
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
        handler.post {
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
    }

    private fun stopTimeTick() {
        timeTick?.let { handler.removeCallbacks(it) }
        timeTick = null
    }
}
