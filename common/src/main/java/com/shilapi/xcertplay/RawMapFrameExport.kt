package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Owns the destination until PixelCopy completes, including after a UI timeout. Main thread only. */
internal class RawMapFrameCapture(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val copy: (Surface, Bitmap, (Int) -> Unit) -> Unit = { surface, bitmap, done ->
        PixelCopy.request(surface, bitmap, { done(it) }, handler)
    },
) {
    var busy = false
        private set

    fun capture(surface: Surface, size: Pair<Int, Int>, current: () -> Boolean,
        completed: (Result<Bitmap>) -> Unit) {
        check(Looper.myLooper() == handler.looper)
        if (busy) { completed(Result.failure(IOException("busy"))); return }
        val (width, height) = size
        if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 3840L * 2160L) {
            completed(Result.failure(IOException("frame_size_unsupported"))); return
        }
        if (!runCatching(current).getOrDefault(false)) { completed(Result.failure(IOException("map_unavailable"))); return }
        val bitmap = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        catch (_: OutOfMemoryError) { completed(Result.failure(IOException("frame_memory"))); return }
        catch (_: RuntimeException) { completed(Result.failure(IOException("frame_allocation"))); return }
        busy = true
        var delivered = false
        var settled = false
        val timeout = Runnable {
            if (!delivered) {
                delivered = true
                completed(Result.failure(IOException("copy_timeout")))
            }
        }
        fun finish(code: Int) {
            if (settled) return
            settled = true
            handler.removeCallbacks(timeout)
            busy = false
            if (delivered) { bitmap.recycle(); return }
            delivered = true
            if (code == PixelCopy.SUCCESS && runCatching(current).getOrDefault(false)) {
                completed(Result.success(bitmap))
            } else {
                bitmap.recycle()
                completed(Result.failure(IOException(if (code == PixelCopy.SUCCESS) "map_changed" else "pixel_copy_$code")))
            }
        }
        handler.postDelayed(timeout, 5_000)
        try { copy(surface, bitmap) { code -> handler.post { finish(code) } } }
        catch (_: RuntimeException) { finish(PixelCopy.ERROR_SOURCE_INVALID) }
        catch (_: OutOfMemoryError) { finish(PixelCopy.ERROR_DESTINATION_INVALID) }
    }
}

/** Explicit single-frame export. Never changes the decoder surface, view transform or connection. */
internal object RawMapFrameExport {
    private val main = Handler(Looper.getMainLooper())
    private val capture = RawMapFrameCapture(main)
    private val writer by lazy { Executors.newSingleThreadExecutor { Thread(it, "diplay-map-frame").apply { isDaemon = true } } }
    private var exporting = false
    val busy get() = exporting || capture.busy

    fun save(context: Context, completed: (Result<DiagnosticExportStore.SavedReport>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy) return false
        val snapshot = CarPlayBackgroundSession.snapshot()
        val owner = snapshot?.controller
        val session = owner?.mediaAttachmentToken()
        val window = session?.let(ClusterWindowVisibility::rawMapWindow)
        val source = window?.rawMapSurface()
        val size = source?.let { snapshot.sink.videoOutputSize(111, it) }
        if (source == null || size == null || session == null) {
            completed(Result.failure(IOException("map_unavailable"))); return true
        }
        val app = context.applicationContext
        val fileName = "DiPlay-map-raw-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}-${size.first}x${size.second}.png"
        exporting = true
        capture.capture(source, size, current = {
            owner.mediaAttachmentToken() === session && ClusterWindowVisibility.rawMapWindow(session) === window &&
                window.rawMapSurface() === source && snapshot.sink.videoOutputSize(111, source) === size
        }) { result ->
            val bitmap = result.getOrNull()
            if (bitmap == null) {
                exporting = false
                completed(Result.failure(result.exceptionOrNull() ?: IOException("copy_failed")))
            } else {
                try {
                    writer.execute {
                        val saved = try { runCatching { savePng(app, fileName, bitmap) } }
                        finally { bitmap.recycle() }
                        main.post { exporting = false; completed(saved) }
                    }
                } catch (_: RuntimeException) {
                    bitmap.recycle(); exporting = false
                    completed(Result.failure(IOException("writer_unavailable")))
                }
            }
        }
        return true
    }

    internal fun writePng(bitmap: Bitmap, output: OutputStream) {
        output.use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("png_encode_failed") }
    }

    internal fun savePng(context: Context, fileName: String, bitmap: Bitmap): DiagnosticExportStore.SavedReport {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val uri = DiagnosticExportStore.saveDownload(context.contentResolver, fileName, "image/png") { uri ->
                    writePng(bitmap, context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("destination_unavailable"))
                }
                return DiagnosticExportStore.SavedReport(uri, savedPath = "Downloads/DiPlay/$fileName")
            } catch (_: Exception) { }
        }
        val directories = buildList {
            if (DiagnosticExportStore.canWritePublicDownloads(context)) add(DiagnosticExportStore.publicDownloadsDirectory())
            runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { add(File(it, "map-frames")) }
            add(File(context.filesDir, "map-frames"))
        }
        for (directory in directories) {
            var file: File? = null
            try {
                if (!directory.isDirectory && !directory.mkdirs()) continue
                file = File.createTempFile(fileName.removeSuffix(".png") + "-", ".png", directory)
                writePng(bitmap, file.outputStream())
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
                return DiagnosticExportStore.SavedReport(uri, savedInApp = directory == File(context.filesDir, "map-frames"),
                    savedPath = file.absolutePath)
            } catch (_: Exception) { file?.delete() }
            catch (error: OutOfMemoryError) { file?.delete(); throw error }
        }
        throw IOException("storage_unavailable")
    }
}
