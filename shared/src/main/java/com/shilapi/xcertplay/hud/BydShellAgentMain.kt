package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.input.ShellKeyReplay
import com.shilapi.xcertplay.input.ShellKeyReplayMain
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay (see [BydShellAgent] and
 * [BydShellAgentProtocol]). Reads getters straight from autoservice's Binder, injects validated key
 * pairs, and pushes the changes BYD's device listeners report. It exits when its stdin ends (DiPlay
 * closed the adb stream) or when DiPlay's process is gone. It never calls a setter.
 */
object BydShellAgentMain {
    private const val SHELL_UID = 2000
    private const val MAX_LINE_BYTES = 4_096
    private val stdout = FileOutputStream(FileDescriptor.out)
    private var prefix = ""

    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 2 || !BydShellAgentProtocol.validToken(args[0])) return
        val token = args[0]
        val appPid = args[1].toIntOrNull() ?: return
        prefix = BydShellAgentProtocol.prefix(token)
        if (android.os.Process.myUid() != SHELL_UID) return exit("uid")
        runCatching { Looper.prepareMainLooper() }
        val agent = Agent(token, Handler(Looper.getMainLooper()))
        Thread({ agent.serve(BufferedInputStream(System.`in`)); exit("stdin closed") }, "agent-stdin").apply { isDaemon = true }.start()
        if (appPid > 0) Thread({
            while (alive(appPid)) Thread.sleep(1_000)
            exit("app gone")
        }, "agent-watch").apply { isDaemon = true }.start()
        // A listener callback that throws must not end the agent: the loop resumes and push is reported broken.
        while (true) {
            try {
                Looper.loop()
            } catch (error: Throwable) {
                agent.callbackFailed(error)
            }
        }
    }

    @Synchronized
    internal fun emit(line: String) {
        // One write per line, so the SDK's own stdout cannot split it.
        runCatching { stdout.write("$prefix $line\n".toByteArray(Charsets.UTF_8)); stdout.flush() }
    }

    private fun exit(reason: String) {
        emit("bye $reason")
        Runtime.getRuntime().halt(0)
    }

    private fun alive(pid: Int): Boolean = try {
        android.system.Os.kill(pid, 0)
        true
    } catch (error: android.system.ErrnoException) {
        error.errno != android.system.OsConstants.ESRCH
    } catch (_: Throwable) {
        true
    }

    private fun errorType(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        return cause.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_$]"), "_").take(40).ifEmpty { "Throwable" }
    }

    private class Agent(private val token: String, private val main: Handler) {
        private val binder: IBinder? = runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "autoservice") as IBinder?
        }.getOrNull()
        private val descriptor: String? = runCatching { binder?.interfaceDescriptor }.getOrNull()
        private val keys = Executors.newSingleThreadExecutor { Thread(it, "agent-key").apply { isDaemon = true } }

        // Main thread.
        private var context: Context? = null
        private val devices = HashMap<Int, Any>()
        private val listeners = HashMap<Int, Any>()
        @Volatile private var subscribed = emptySet<Pair<Int, Int>>()

        fun serve(input: InputStream) {
            emit("ready ${BydShellAgentProtocol.VERSION} binder=${if (binder != null) 1 else 0}")
            while (true) {
                val line = readLine(input) ?: return
                runCatching { handle(line) }
            }
        }

        private fun handle(line: String) {
            val words = line.trim().split(' ').filter { it.isNotEmpty() }
            val id = words.getOrNull(1)?.toIntOrNull() ?: return
            val body = words.drop(2)
            when (words[0]) {
                "R" -> {
                    val values = body.take(BydShellAgentProtocol.MAX_READS).map { word ->
                        BydShellAgentProtocol.parseRead(word)?.let { get(it) }?.toString() ?: "x"
                    }
                    emit("r $id ${values.joinToString(" ")}")
                }
                "K" -> {
                    val request = ShellKeyReplay.parseRequest(body.joinToString(" "), token)
                    if (request == null) emit("k $id rejected")
                    else keys.execute { emit("k $id ${ShellKeyReplayMain.inject(request, AtomicReference(null))}") }
                }
                "S" -> {
                    val targets = body.take(BydShellAgentProtocol.MAX_SUBSCRIPTIONS).mapNotNull(BydShellAgentProtocol::parseTarget)
                    main.post { emit("s $id ${subscribe(targets).joinToString(" ")}") }
                }
                "P" -> emit("p $id")
            }
        }

        /** What `service call autoservice <tx> i32 <device> i32 <fid>` returns: the word after a zero exception code. */
        private fun get(signal: BydSignal): Int? {
            val service = binder ?: return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                descriptor?.takeIf { it.isNotEmpty() }?.let(data::writeInterfaceToken)
                data.writeInt(signal.device)
                data.writeInt(signal.fid)
                if (!service.transact(signal.transaction, data, reply, 0)) return null
                reply.setDataPosition(0)
                if (reply.dataAvail() < 8 || reply.readInt() != 0) null else reply.readInt()
            } catch (_: Exception) {
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        // Main thread: BYD's devices deliver their callbacks on the looper they were created on.
        private fun subscribe(targets: List<Pair<Int, Int>>): List<String> {
            for ((device, listener) in listeners) {
                runCatching { unregister(devices.getValue(device), listener) }
            }
            listeners.clear()
            subscribed = targets.toSet()
            val results = HashMap<Pair<Int, Int>, String>()
            for ((device, group) in targets.groupBy { it.first }) {
                val outcome = try {
                    val instance = devices.getOrPut(device) { device(device) }
                    val listener = listener(device)
                    register(instance, listener, group.map { it.second }.toIntArray())
                    listeners[device] = listener
                    "ok"
                } catch (error: Throwable) {
                    "err:${errorType(error)}"
                }
                group.forEach { results[it] = outcome }
            }
            return targets.map { results[it] ?: "err:none" }
        }

        fun callbackFailed(error: Throwable) {
            emit("log callback-failed ${errorType(error)}")
        }

        private fun listenerInterface(): Class<*> = Class.forName("android.hardware.IBYDAutoListener")

        /** A plain IBYDAutoListener: the SDK's typed listener classes are not on DiPlay's compile path. */
        private fun listener(device: Int): Any {
            val type = listenerInterface()
            val eventType = Class.forName("android.hardware.IBYDAutoEvent")
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onDataChanged" -> {
                        val event = args?.getOrNull(0)
                        if (event != null) deliver(device, eventType.getMethod("getEventType").invoke(event) as Int,
                            eventType.getMethod("getValue").invoke(event) as Int)
                        null
                    }
                    "onDataEventChanged" -> {
                        val fid = args?.getOrNull(0) as? Int
                        val value = args?.getOrNull(1)?.let { runCatching { it.javaClass.getField("intValue").getInt(it) }.getOrNull() }
                        if (fid != null && value != null) deliver(device, fid, value)
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "DiPlayAgentListener($device)"
                    else -> null
                }
            }
            return Proxy.newProxyInstance(BydShellAgentMain::class.java.classLoader, arrayOf(type), handler)
        }

        private fun deliver(device: Int, fid: Int, value: Int) {
            // BYDAutoEventValue.INVAILD_INT marks a value the SDK did not fill in.
            if ((device to fid) !in subscribed || value == -999999999) return
            emit("e $device,$fid $value")
        }

        private fun register(device: Any, listener: Any, fids: IntArray) {
            val type = listenerInterface()
            val method = device.javaClass.methods.firstOrNull {
                it.name == "registerListener" && it.parameterTypes.contentEquals(arrayOf(type, IntArray::class.java))
            } ?: throw NoSuchMethodException("registerListener")
            method.invoke(device, listener, fids)
        }

        private fun unregister(device: Any, listener: Any) {
            val type = listenerInterface()
            device.javaClass.methods.firstOrNull {
                it.name == "unregisterListener" && it.parameterTypes.contentEquals(arrayOf(type))
            }?.invoke(device, listener)
        }

        @SuppressLint("PrivateApi")
        private fun device(device: Int): Any {
            val name = DEVICES[device] ?: throw IllegalArgumentException("device $device")
            val system = context ?: run {
                val thread = Class.forName("android.app.ActivityThread")
                val main = thread.getMethod("systemMain").invoke(null)
                (thread.getMethod("getSystemContext").invoke(main) as Context).also { context = it }
            }
            val type = Class.forName(name)
            // getInstance checks a BYD permission on the caller's side only; build it the same way otherwise.
            return try {
                type.getMethod("getInstance", Context::class.java).invoke(null, system)!!
            } catch (_: InvocationTargetException) {
                type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(system)
            }
        }

        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var overflow = false
            while (true) {
                val value = input.read()
                if (value < 0) return null
                if (value == '\n'.code) {
                    if (!overflow) return bytes.toString("UTF-8").trimEnd('\r')
                    bytes.reset(); overflow = false
                } else if (bytes.size() < MAX_LINE_BYTES) bytes.write(value) else overflow = true
            }
        }
    }

    /** The BYD devices whose listeners the agent may register (see BYDAutoConstants). */
    private val DEVICES = mapOf(
        1001 to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        1007 to "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
        1009 to "android.hardware.bydauto.charging.BYDAutoChargingDevice",
        1011 to "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
    )
}
