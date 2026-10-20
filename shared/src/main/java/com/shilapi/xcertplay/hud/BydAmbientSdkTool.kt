package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Process
import dalvik.system.PathClassLoader
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One-shot getter-only process, separate from the legacy lamp writer and its restore protocol. */
object BydAmbientSdkTool {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 2 || args[0] != "--stdin" || !BydAmbientLightWorkerProtocol.validToken(args[1])) return
        val output = System.out
        val sink = PrintStream(object : OutputStream() { override fun write(b: Int) {} })
        System.setOut(sink)
        System.setErr(sink)
        var bytes = 0
        val outputLock = Any()
        fun emit(entry: BydAmbientSdkProtocol.Entry) = synchronized(outputLock) {
            val line = entry.line()
            if (BydAmbientSdkProtocol.parse(line).size == 1 && bytes + line.length + 1 <= BydAmbientSdkProtocol.MAX_OUTPUT) {
                output.println(line); output.flush(); bytes += line.length + 1
            }
        }
        val deadline = Executors.newSingleThreadScheduledExecutor()
        deadline.schedule({
            emit(BydAmbientSdkProtocol.Entry("complete", "timeout"))
            synchronized(outputLock) { output.println("done"); output.flush() }
            Runtime.getRuntime().halt(0)
        }, BydAmbientSdkProtocol.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        var stage = "lock"
        try {
            val line = BydAmbientLightWorkerProtocol.readLine(System.`in`)
            if (line != "${args[1]} read-sdk") return
            val ownership = AmbientWorkerOwnerLock.tryAcquire(File("/data/local/tmp/diplay-ambient-worker.lock"))
            if (ownership == null) {
                emit(BydAmbientSdkProtocol.Entry("lock", "busy"))
                return
            }
            ownership.use {
                emit(BydAmbientSdkProtocol.Entry("lock", "ok"))
                stage = "context"
                val context = shellContext()
                emit(BydAmbientSdkProtocol.Entry(stage, "ok", "shell"))
                stage = "loader"
                val loader = sdkLoader(context, ::emit)
                stage = "provider"
                val info = context.packageManager.resolveContentProvider(BydAmbientSdkProtocol.AUTHORITY, 0)
                if (info == null) {
                    emit(BydAmbientSdkProtocol.Entry(stage, "provider_missing"))
                    return
                }
                emit(BydAmbientSdkProtocol.Entry(stage, "ok", safeName(info.packageName)))
                stage = "service"
                val serviceClass = Class.forName(BydAmbientSdkProtocol.SERVICE, false, loader)
                val service = readService(context, loader, ::emit)
                if (service == null) {
                    emit(BydAmbientSdkProtocol.Entry(stage, "service_missing"))
                    return
                }
                emit(BydAmbientSdkProtocol.Entry(stage, "ok"))
                val ok = BydAmbientSdkProtocol.readOnce({ read ->
                    if (read.area == null) serviceClass.getMethod(read.method).invoke(service)
                    else serviceClass.getMethod(read.method, Int::class.javaPrimitiveType).invoke(service, read.area)
                }, ::emit)
                emit(BydAmbientSdkProtocol.Entry("complete", if (ok) "ok" else "partial"))
            }
        } catch (error: Throwable) {
            emit(BydAmbientSdkProtocol.Entry(stage, BydAmbientSdkProtocol.failure(error)))
        } finally {
            deadline.shutdownNow()
            synchronized(outputLock) { output.println("done"); output.flush() }
            Runtime.getRuntime().halt(0)
        }
    }

    @SuppressLint("PrivateApi")
    private fun shellContext(): Context {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getMethod("systemMain").invoke(null)
        val system = thread.getMethod("getSystemContext").invoke(instance) as Context
        return system.createPackageContext("com.android.shell", 0)
    }

    @Suppress("DEPRECATION")
    private fun sdkLoader(context: Context, emit: (BydAmbientSdkProtocol.Entry) -> Unit): ClassLoader {
        val parent = BydAmbientSdkTool::class.java.classLoader!!
        fun containsSdk(loader: ClassLoader): Boolean = try {
            Class.forName(BydAmbientSdkProtocol.SERVICE, false, loader)
            Class.forName("com.byd.spi.ipc.cursor.BinderCursor\$BinderParcelable", false, loader)
            true
        } catch (_: ClassNotFoundException) { false }
        if (containsSdk(parent)) {
            emit(BydAmbientSdkProtocol.Entry("loader", "ok", "default"))
            return parent
        }
        for (name in listOf("com.byd.carsettings", "com.byd.carsettings.fse")) {
            val info = try { context.packageManager.getPackageInfo(name, PackageManager.GET_SHARED_LIBRARY_FILES) }
                catch (_: PackageManager.NameNotFoundException) { continue }
            val app = info.applicationInfo ?: continue
            if (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue
            val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty() + app.sharedLibraryFiles.orEmpty()
            val loader = PathClassLoader(paths.joinToString(File.pathSeparator), parent)
            if (!containsSdk(loader)) continue
            emit(BydAmbientSdkProtocol.Entry("loader", "ok", "installed_system_apk"))
            emit(BydAmbientSdkProtocol.Entry("package", "ok", name))
            emit(BydAmbientSdkProtocol.Entry("version", "ok", safeName(info.versionName ?: "unknown")))
            return loader
        }
        throw ClassNotFoundException()
    }

    /** Reproduces the audited SPI read-only Binder lookup, without DiCar.init/connect or listeners. */
    @SuppressLint("PrivateApi")
    @Suppress("DEPRECATION")
    private fun readService(context: Context, loader: ClassLoader, emit: (BydAmbientSdkProtocol.Entry) -> Unit): Any? {
        val properties = Class.forName("android.os.SystemProperties")
        val multiUser = properties.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, "ro.build.multi_display_user", 0) as Int
        emit(BydAmbientSdkProtocol.Entry("user", "ok", "multi=${if (multiUser == 0) 0 else 1};uid=${Process.myUid()}"))
        val authority = if (multiUser == 0) BydAmbientSdkProtocol.AUTHORITY
            else "${Process.myUid() / 100000}@${BydAmbientSdkProtocol.AUTHORITY}"
        val uri = Uri.parse("content://$authority/sync_binder")
        val binder = context.contentResolver.query(uri, null, null, arrayOf(BydAmbientSdkProtocol.SERVICE), null)?.use { cursor ->
            val extras = cursor.extras
            extras.classLoader = loader
            val parcelable = extras.getParcelable<android.os.Parcelable>("binder") ?: return@use null
            val cls = Class.forName("com.byd.spi.ipc.cursor.BinderCursor\$BinderParcelable", false, loader)
            cls.getMethod("getBinder").invoke(parcelable) as? IBinder
        } ?: return null
        require(binder.interfaceDescriptor == BydAmbientSdkProtocol.SERVICE)
        return Class.forName("${BydAmbientSdkProtocol.SERVICE}\$Stub", true, loader)
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9_.+-]"), "_").take(100).ifEmpty { "unknown" }
}
