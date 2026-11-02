package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves CarPlay's decoded main-screen picture, at CarPlay's own canvas size and without DiPlay's
 * controls or the head unit's bars, as a PNG in Downloads/DiPlay/vision (the Diagnostics setting
 * "Save CarPlay frames"). Never changes the decoder surface, view transform or connection.
 */
internal object CarPlayFrameCapture {
    const val SUBFOLDER = "vision"
    private const val APP_FOLDER = "carplay-frames"
    private val main = Handler(Looper.getMainLooper())
    private val surfaceCopy = RawMapFrameCapture(main)
    private val writer by lazy { Executors.newSingleThreadExecutor { Thread(it, "diplay-carplay-frame").apply { isDaemon = true } } }
    private var exporting = false
    val busy get() = exporting || surfaceCopy.busy

    fun fileName(width: Int, height: Int, date: Date = Date()): String =
        "DiPlay-vision-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(date)}-${width}x${height}.png"

    /**
     * Copies the picture from [surface] (the SurfaceView output) or else [texture], at [size].
     * Main thread only. Returns false, without calling [completed], while an earlier copy is running.
     */
    fun save(context: Context, texture: TextureView?, surface: SurfaceView?, size: Pair<Int, Int>,
        completed: (Result<DiagnosticExportStore.SavedReport>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy) return false
        val app = context.applicationContext
        val (width, height) = size
        val name = fileName(width, height)
        exporting = true
        val done: (Result<Bitmap>) -> Unit = { result ->
            val bitmap = result.getOrNull()
            if (bitmap == null) {
                exporting = false
                completed(Result.failure(result.exceptionOrNull() ?: IOException("copy_failed")))
            } else {
                try {
                    writer.execute {
                        val saved = try {
                            runCatching { RawMapFrameExport.savePng(app, name, bitmap, SUBFOLDER, APP_FOLDER) }
                        } finally { bitmap.recycle() }
                        main.post { exporting = false; completed(saved) }
                    }
                } catch (_: RuntimeException) {
                    bitmap.recycle(); exporting = false
                    completed(Result.failure(IOException("writer_unavailable")))
                }
            }
        }
        val holderSurface = surface?.holder?.surface
        when {
            holderSurface != null -> surfaceCopy.capture(holderSurface, size, current = { holderSurface.isValid }, completed = done)
            texture != null && texture.isAvailable -> done(copyTexture(texture, width, height))
            else -> done(Result.failure(IOException("video_unavailable")))
        }
        return true
    }

    private fun copyTexture(texture: TextureView, width: Int, height: Int): Result<Bitmap> {
        if (width !in 1..4096 || height !in 1..4096) return Result.failure(IOException("frame_size_unsupported"))
        val bitmap = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        catch (_: OutOfMemoryError) { return Result.failure(IOException("frame_memory")) }
        catch (_: RuntimeException) { return Result.failure(IOException("frame_allocation")) }
        // The texture's own content, scaled to the bitmap; the view transform that places it is not applied.
        return try {
            texture.getBitmap(bitmap)
            Result.success(bitmap)
        } catch (_: RuntimeException) {
            bitmap.recycle()
            Result.failure(IOException("texture_copy_failed"))
        }
    }
}
