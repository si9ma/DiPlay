package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.external.CarMeterSettings
import com.shilapi.xcertplay.external.CarMeterVideo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 推送导航地图：把 CarPlay 仪表地图(stream 111)或中控主屏(stream 110，见 [CarMeterSettings.mapSource])
 * 的一块正方形区域，以 464×464 JPEG 视频经 Wi-Fi/UDP 推到 CarMeter 圆屏(协议见 [CarMeterVideo])。
 * 何时推由 [CarMeterContent] 决定(导航地图模式 + 正在导航，或设置页的测试开关)。
 *
 * 取帧：作为 [MapMirrors] 的一路镜像(key [MapMirrors.CARMETER] / [MapMirrors.CARMETER_MAIN])——媒体 sink 为它另开一个解码器，
 * 画到本类自己的 SurfaceTexture 上，不影响仪表/中控已有的地图输出。专用 GL 线程把 OES 纹理按
 * 取景框(中心 X/Y + 大小)裁剪、2×2 超采样缩到 464²、圆外涂黑，glReadPixels 后 Bitmap.compress(JPEG)
 * (系统 libjpeg-turbo，baseline 4:2:0，BT.601 全范围，正是 ESP32 解码器最快的格式)。
 *
 * 发送：有地图画面才发启动命令把设备切进视频模式(之后每 3 秒保活，固件对同参数重复启动静默忽略)，
 * 地图流断了就发停止命令交还表盘；命令走 UDP 8899，帧走 UDP 8900。
 * 地图静止时 CarPlay 不一定出新帧，这里每秒重发上一帧，避免固件 10 秒无包自动停。
 * 取景/画质/帧率/来源设置持久化在 [CarMeterSettings]。
 *
 * 线程：start/stop/settingsChanged/status 回调都在主线程；GL、编码、UDP 都在 "carmeter-map" 线程。
 */
internal object CarMeterMapStream {
    private const val TAG = "CarMeterMapStream"

    data class Status(
        val running: Boolean,
        val host: String,
        /** 已收到过地图画面 */
        val receiving: Boolean,
        val fps: Int,
        val kbPerFrame: Int,
        val error: String?,
    )

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var appContext: Context? = null

    /** 设置页注册(主线程回调)，推流期间每秒一次 */
    var statusListener: ((Status) -> Unit)? = null
        set(value) { field = value; value?.invoke(status()) }

    val running: Boolean get() = session != null

    fun status(): Status = session?.status() ?: Status(false, "", false, 0, 0, null)

    private val aspectListener: () -> Unit = { appContext?.let { settingsChanged(it) } }

    private fun keyFor(source: CarMeterSettings.MapSource) =
        if (source == CarMeterSettings.MapSource.MAIN) MapMirrors.CARMETER_MAIN else MapMirrors.CARMETER

    /** 主线程。返回 false = GL 线程起不来 */
    fun start(context: Context): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session != null) return true
        val app = context.applicationContext
        appContext = app
        val host = com.shilapi.xcertplay.external.CarMeterOutput.videoHost(app)
        val source = CarMeterSettings.mapSource(app)
        val s = try { Session(host, source, params(app, source)) } catch (e: RuntimeException) {
            Log.w(TAG, "start failed", e); return false
        }
        session = s
        MapMirrors.addAspectListener(aspectListener)
        if (source == CarMeterSettings.MapSource.MAIN) {
            MapMirrors.addMainStreamListener(streamListener)
            s.streamActive = MapMirrors.mainStreamActive
        } else {
            MapMirrors.addStreamListener(streamListener)
            s.streamActive = MapMirrors.streamActive
        }
        s.start { surface ->
            // GL 线程建好 Surface 后回到主线程挂镜像；期间已被停掉就不挂
            if (session === s) MapMirrors.set(keyFor(source), surface)
        }
        Log.i(TAG, "map stream ($source) -> $host")
        return true
    }

    private val streamListener: (Boolean) -> Unit = { active -> session?.streamActive = active }

    /** 主线程 */
    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val s = session ?: return
        session = null
        MapMirrors.removeAspectListener(aspectListener)
        MapMirrors.removeStreamListener(streamListener)
        MapMirrors.removeMainStreamListener(streamListener)
        // 先撤镜像(媒体 sink 关掉那路解码器)，再拆 GL；Surface 晚一点释放，同 MapEmbedService
        MapMirrors.set(keyFor(s.source), null)
        s.stop()
        statusListener?.invoke(status())
        Log.i(TAG, "map stream stopped")
    }

    /** 取景/画质/帧率/画面来源改了(主线程)：推流中实时生效；换来源要换一路镜像，整场重启 */
    fun settingsChanged(context: Context) {
        val s = session ?: return
        val app = context.applicationContext
        val source = CarMeterSettings.mapSource(app)
        if (source != s.source) {
            stop()
            start(app)
            return
        }
        s.update(params(app, source))
    }

    private fun params(context: Context, source: CarMeterSettings.MapSource) = Params(
        crop = CarMeterVideo.squareCrop(
            if (source == CarMeterSettings.MapSource.MAIN) MapMirrors.mainAspect else MapMirrors.streamAspect,
            CarMeterSettings.mapCenterX(context), CarMeterSettings.mapCenterY(context), CarMeterSettings.mapSize(context)),
        quality = CarMeterSettings.mapQuality(context),
        fps = CarMeterSettings.mapFps(context),
    )

    private data class Params(val crop: CarMeterVideo.Crop, val quality: Int, val fps: Int) {
        val startCommand: String get() = CarMeterVideo.startCommand(dither = true, teSync = CarMeterVideo.teSyncFor(fps))
    }

    private class Session(private val host: String, val source: CarMeterSettings.MapSource, initial: Params) {
        private val thread = HandlerThread("carmeter-map", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        private val gl = Handler(thread.looper)
        @Volatile private var params = initial
        @Volatile private var stopped = false
        /** iPhone 正在推所镜像的那路流(主线程写) */
        @Volatile var streamActive = false

        // ---- 以下只在 GL 线程访问 ----
        private var egl: Egl? = null
        private var texture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var socket: DatagramSocket? = null
        private var address: InetAddress? = null
        private val stMatrix = FloatArray(16)
        private var hasFrame = false
        private var newFrame = false
        private var lastNewFrameAt = 0L
        private var dirty = true
        /** 设备已被我们切进视频模式(发过启动命令、还没发停止) */
        private var deviceOn = false
        private var deviceOnAt = 0L
        private var lastJpeg: ByteArray? = null
        private var frameId = 0L
        private var lastCommandAt = 0L
        private var lastSentAt = 0L
        private var qualityCap = 95
        private val jpegOut = ByteArrayOutputStream(64 * 1024)
        private val pixels: ByteBuffer = ByteBuffer.allocateDirect(N * N * 4).order(ByteOrder.nativeOrder())
        private var bitmap: Bitmap? = null

        // ---- 统计(GL 线程写，主线程读) ----
        @Volatile private var fps = 0
        @Volatile private var kb = 0
        @Volatile private var error: String? = null
        @Volatile private var receiving = false
        private var windowFrames = 0
        private var windowBytes = 0L
        private var windowStart = 0L

        fun status() = Status(!stopped, host, receiving, fps, kb, error)

        fun start(attach: (Surface) -> Unit) {
            gl.post {
                try {
                    val e = Egl(N)
                    egl = e
                    val tex = SurfaceTexture(e.texture)
                    // 只是给生产者的尺寸提示；镜像解码器按流的真实尺寸出图
                    if (source == CarMeterSettings.MapSource.MAIN) tex.setDefaultBufferSize(1920, 1080)
                    else tex.setDefaultBufferSize(1600, 600)
                    tex.setOnFrameAvailableListener({ onFrame() }, gl)
                    texture = tex
                    val s = Surface(tex)
                    surface = s
                    bitmap = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
                    socket = DatagramSocket()
                    address = InetAddress.getByName(host)
                    windowStart = SystemClock.uptimeMillis()
                    main.post { attach(s) }
                    gl.post(tick)
                    main.post(statusTick)
                } catch (e: Exception) {
                    fail("init: ${e.message ?: e.javaClass.simpleName}")
                } catch (e: OutOfMemoryError) {
                    fail("init: out of memory")
                }
            }
        }

        fun update(next: Params) {
            params = next
            gl.post { dirty = true }
        }

        fun stop() {
            stopped = true
            main.removeCallbacks(statusTick)
            gl.post {
                gl.removeCallbacks(tick)
                // 停止命令发两次：UDP 不可靠，丢了设备还有 10 秒无包自动停兜底
                if (deviceOn) sendCommand(CarMeterVideo.STOP_COMMAND)
                gl.postDelayed({
                    if (deviceOn) sendCommand(CarMeterVideo.STOP_COMMAND)
                    deviceOn = false
                    release()
                }, RELEASE_DELAY_MS)
            }
        }

        private fun release() {
            texture?.setOnFrameAvailableListener(null)
            surface?.release(); surface = null
            texture?.release(); texture = null
            egl?.release(); egl = null
            bitmap?.recycle(); bitmap = null
            socket?.close(); socket = null
            thread.quitSafely()
        }

        private fun fail(message: String) {
            Log.w(TAG, message)
            error = message
            main.post { statusListener?.invoke(status()) }
        }

        private fun onFrame() {
            if (stopped) return
            val tex = texture ?: return
            try {
                tex.updateTexImage()           // 每帧都锁存，免得缓冲队列塞满卡住镜像解码器
                tex.getTransformMatrix(stMatrix)
                hasFrame = true
                newFrame = true
                lastNewFrameAt = SystemClock.uptimeMillis()
                receiving = true
            } catch (e: RuntimeException) {
                fail("frame: ${e.message}")
            }
        }

        private val tick: Runnable = object : Runnable {
            override fun run() {
                if (stopped) return
                val p = params
                val now = SystemClock.uptimeMillis()
                gl.postDelayed(this, (1000L / p.fps).coerceAtLeast(1))

                // 有地图才把设备切进视频模式；地图流断了就交还表盘，别让手表黑屏或停在旧画面
                val mapLive = hasFrame && (streamActive || now - lastNewFrameAt < MAP_STALE_MS)
                if (!mapLive) {
                    if (deviceOn) {
                        sendCommand(CarMeterVideo.STOP_COMMAND)
                        deviceOn = false
                    }
                    hasFrame = false        // 流恢复后等镜像解码器出新帧再推
                    receiving = false
                    return
                }
                if (!deviceOn) {
                    sendCommand(p.startCommand)
                    deviceOn = true
                    deviceOnAt = now
                    dirty = true
                    return
                }
                // 保活；帧率跨过 30 时 te 字段随之变化，固件热切换 TE 同步、不重启
                if (now - lastCommandAt >= KEEPALIVE_COMMAND_MS || p.startCommand != lastStartCommand) {
                    sendCommand(p.startCommand)
                }
                if (now - deviceOnAt < START_SETTLE_MS) return

                val fresh = newFrame || dirty
                if (!fresh && now - lastSentAt < KEEPALIVE_FRAME_MS) return
                if (fresh) {
                    newFrame = false
                    dirty = false
                    if (!encode(p)) return
                }
                lastJpeg?.let { send(it) }
                lastSentAt = now
                accountWindow(now)
            }
        }

        /** 渲染 + 回读 + JPEG。超过固件 128KB 上限时下调质量重来。 */
        private fun encode(p: Params): Boolean {
            val e = egl ?: return false
            val bmp = bitmap ?: return false
            return try {
                e.draw(stMatrix, p.crop)
                pixels.rewind()
                GLES20.glReadPixels(0, 0, N, N, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                pixels.rewind()
                bmp.copyPixelsFromBuffer(pixels)
                var q = minOf(p.quality, qualityCap)
                while (true) {
                    jpegOut.reset()
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, q, jpegOut)) throw IOException("jpeg_encode_failed")
                    if (jpegOut.size() <= CarMeterVideo.MAX_FRAME_BYTES || q <= 50) break
                    q -= 10
                    qualityCap = q          // 这段画面太复杂：本场往后都压低一点
                }
                if (jpegOut.size() > CarMeterVideo.MAX_FRAME_BYTES) throw IOException("frame_too_large")
                lastJpeg = jpegOut.toByteArray()
                true
            } catch (ex: Exception) {
                fail("encode: ${ex.message ?: ex.javaClass.simpleName}")
                false
            }
        }

        private fun send(jpeg: ByteArray) {
            val s = socket ?: return
            val a = address ?: return
            try {
                for (packet in CarMeterVideo.packets(frameId++, jpeg)) {
                    s.send(DatagramPacket(packet, packet.size, a, CarMeterVideo.VIDEO_PORT))
                }
                windowFrames++
                windowBytes += jpeg.size
                error = null
            } catch (e: IOException) {
                fail("send: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private var lastStartCommand: String? = null

        private fun sendCommand(command: String) {
            val s = socket ?: return
            val a = address ?: return
            lastCommandAt = SystemClock.uptimeMillis()
            if (command != CarMeterVideo.STOP_COMMAND) lastStartCommand = command
            try {
                val bytes = command.toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(bytes, bytes.size, a, CarMeterSettings.UDP_PORT))
            } catch (e: IOException) {
                fail("command: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private fun accountWindow(now: Long) {
            val span = now - windowStart
            if (span < 1000) return
            fps = (windowFrames * 1000L / span).toInt()
            kb = if (windowFrames > 0) (windowBytes / windowFrames / 1024).toInt() else 0
            windowFrames = 0
            windowBytes = 0
            windowStart = now
        }

        private val statusTick: Runnable = object : Runnable {
            override fun run() {
                if (stopped) return
                statusListener?.invoke(status())
                main.postDelayed(this, 1000)
            }
        }
    }

    /** 离屏 EGL：PBuffer N×N + 外部纹理采样程序 */
    private class Egl(private val size: Int) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val program: Int
        val texture: Int
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private val aPos: Int
        private val uSt: Int
        private val uCrop: Int
        private val uSize: Int

        init {
            check(display != EGL14.EGL_NO_DISPLAY) { "no egl display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, size, EGL14.EGL_HEIGHT, size, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }

            program = link(VERTEX, FRAGMENT)
            aPos = GLES20.glGetAttribLocation(program, "a_pos")
            uSt = GLES20.glGetUniformLocation(program, "u_st")
            uCrop = GLES20.glGetUniformLocation(program, "u_crop")
            uSize = GLES20.glGetUniformLocation(program, "u_size")
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texture = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun draw(st: FloatArray, crop: CarMeterVideo.Crop) {
            GLES20.glViewport(0, 0, size, size)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0)
            GLES20.glUniform4f(uCrop, crop.x, crop.y, crop.width, crop.height)
            GLES20.glUniform1f(uSize, size.toFloat())
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("gl error 0x${Integer.toHexString(err)}")
        }

        fun release() {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            // 不 eglTerminate：默认 display 进程内共享(HWUI 等也在用)
            EGL14.eglReleaseThread()
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }

        private fun compile(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "compile: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
    }

    private const val N = CarMeterVideo.FRAME_SIZE
    private const val START_SETTLE_MS = 500L        // 设备卸 BLE + 接管屏幕的时间
    private const val KEEPALIVE_FRAME_MS = 1000L    // 画面静止时重发上一帧的间隔
    private const val KEEPALIVE_COMMAND_MS = 3000L  // 启动命令保活间隔(设备重启后自动续上)
    private const val MAP_STALE_MS = 3000L          // 流状态未知时，多久没新帧算地图断了
    private const val RELEASE_DELAY_MS = 500L

    private const val VERTEX = """
attribute vec2 a_pos;
void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
"""

    // gl_FragCoord.y=0 是回读的第 0 行 = Bitmap 顶行，所以 o.y 直接当"从上往下"的比例用；
    // 纹理坐标 v 是自下而上，采样前翻转。2×2 超采样(间距半个输出像素)抑制缩小时的锯齿。
    private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES u_tex;
uniform mat4 u_st;
uniform vec4 u_crop;
uniform float u_size;
vec4 tap(vec2 p) {
    vec2 t = (u_st * vec4(p.x, 1.0 - p.y, 0.0, 1.0)).xy;
    return texture2D(u_tex, t);
}
void main() {
    vec2 o = gl_FragCoord.xy / u_size;
    if (distance(o, vec2(0.5)) > 0.5) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 p = u_crop.xy + o * u_crop.zw;
    vec2 d = u_crop.zw * (0.25 / u_size);
    vec4 c = tap(p + vec2(-d.x, -d.y)) + tap(p + vec2(d.x, -d.y)) + tap(p + vec2(-d.x, d.y)) + tap(p + vec2(d.x, d.y));
    gl_FragColor = vec4(c.rgb * 0.25, 1.0);
}
"""
}
