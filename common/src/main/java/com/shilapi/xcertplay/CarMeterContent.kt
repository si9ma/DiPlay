package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.external.CarMeterOutput
import com.shilapi.xcertplay.external.CarMeterSettings
import com.shilapi.xcertplay.external.CarMeterSettings.Content
import com.shilapi.xcertplay.host.R

/**
 * 外接设备(CarMeter)显示什么：按「显示内容」设置 + iPhone 是否在导航，决定推导航地图视频、
 * 只发导航简洁信息，还是不显示导航(手表保持表盘)。
 *
 * - 导航相关的内容只在导航时生效：非导航时一律表盘，地图推流停掉。
 * - 导航结束：[CarMeterOutput] 立刻发 nav:0，这里同时停掉地图推流(手表回表盘)。
 * - 快捷指令「切换外接设备内容」调 [cycle]：导航地图 → 导航简洁信息 → 不显示导航 → …，立即生效。
 * - 设置页的「无论是否导航都推送(测试)」开关走 [setTestStream]，与上面独立。
 *
 * 主线程。
 */
internal object CarMeterContent {
    private const val TAG = "CarMeterContent"
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var attached = false

    /** 设置页的测试开关(不持久化)：不管导航与否都推地图 */
    var testStream = false
        private set

    /** 设置页刷新用(主线程回调) */
    var onChanged: (() -> Unit)? = null

    private val navigationListener: (Boolean) -> Unit = { apply() }

    /** 幂等；DiPlayActivity/CarPlayHostActivity 打开时调用 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (attached) return
        attached = true
        CarMeterOutput.addNavigationListener(navigationListener)
        apply()
    }

    /** 切到下一种内容，返回新内容；announce=true 时弹一个 toast(快捷指令执行时车机上能看到反馈) */
    fun cycle(context: Context, announce: Boolean = false): Content {
        attach(context)
        val app = context.applicationContext
        val next = Content.entries[(CarMeterSettings.content(app).ordinal + 1) % Content.entries.size]
        set(app, next)
        if (announce) {
            val text = app.getString(R.string.carmeter_content_toast, label(app, next))
            main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
        }
        return next
    }

    fun set(context: Context, content: Content) {
        attach(context)
        CarMeterSettings.setContent(context.applicationContext, content)
        Log.i(TAG, "content -> $content")
        // 简洁信息/不显示导航：按当前导航状态立刻重发(不显示 → 马上 nav:0)
        CarMeterOutput.refreshNavigation()
        apply()
    }

    fun setTestStream(context: Context, on: Boolean) {
        attach(context)
        testStream = on
        apply()
    }

    /** 总开关等外部条件变了：重新判断推流 */
    fun refresh(context: Context) {
        attach(context)
        apply()
    }

    fun label(context: Context, content: Content): String = context.getString(when (content) {
        Content.MAP -> R.string.settings_carmeter_content_map
        Content.SIMPLE -> R.string.settings_carmeter_content_simple
        Content.NONE -> R.string.settings_carmeter_content_none
    })

    /** 推流是否应该开着 */
    fun streamWanted(context: Context): Boolean {
        if (!CarMeterSettings.enabled(context)) return false
        if (testStream) return true
        return CarMeterSettings.content(context) == Content.MAP && CarMeterOutput.navigating
    }

    private fun apply() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { apply() }; return }
        val context = appContext ?: return
        val want = streamWanted(context)
        if (want && !CarMeterMapStream.running) {
            if (!CarMeterMapStream.start(context)) Log.w(TAG, "map stream failed to start")
        } else if (!want && CarMeterMapStream.running) {
            CarMeterMapStream.stop()
            // 视频期间固件停了 BLE，结束后尽快回连
            CarMeterOutput.kick()
        }
        onChanged?.invoke()
    }
}
