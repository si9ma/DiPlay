package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.host.R

/** Preview coordinates use the selected display's aspect, never the head unit's main-screen aspect. */
internal class ClusterWindowLayoutView(context: Context, private val aspect: Float) : View(context) {
    var onMoved: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var moving: String? = null
    private var dx = 0f
    private var dy = 0f
    init { minimumHeight = (120 * resources.displayMetrics.density).toInt() }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width / aspect.coerceIn(1f, 4f)).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(32, 40, 50))
        val config = ClusterWindowSettings.load(context)
        for ((role, bounds) in listOf("map" to config.mapRect, "music" to config.activeMusicRect)) {
            paint.color = if (role == "map") Color.rgb(27, 110, 135) else Color.rgb(119, 82, 155)
            val rect = bounds.pixels(width, height)
            canvas.drawRect(rect, paint)
            paint.color = Color.WHITE
            paint.textSize = minOf(height * .08f, rect.height() * .3f).coerceAtLeast(10f)
            canvas.drawText(context.getString(if (role == "map") R.string.settings_cluster_window_map else R.string.settings_cluster_window_music),
                rect.left + rect.width() * .04f, rect.top + paint.textSize * 1.4f, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val config = ClusterWindowSettings.load(context)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moving = when {
                    config.activeMusicRect.pixels(width, height).contains(event.x.toInt(), event.y.toInt()) -> config.musicRectKey
                    config.mapRect.pixels(width, height).contains(event.x.toInt(), event.y.toInt()) -> "map"
                    else -> null
                }
                val rect = if (moving == config.musicRectKey) config.activeMusicRect else config.mapRect
                dx = rect.x - event.x * 100 / width.coerceAtLeast(1)
                dy = rect.y - event.y * 100 / height.coerceAtLeast(1)
                if (moving == null) return false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> moving?.let { role ->
                ClusterWindowSettings.save(context, "${role}_x", (event.x * 100 / width.coerceAtLeast(1) + dx).toInt())
                ClusterWindowSettings.save(context, "${role}_y", (event.y * 100 / height.coerceAtLeast(1) + dy).toInt())
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) { moving = null; parent?.requestDisallowInterceptTouchEvent(false); onMoved?.invoke(); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> { moving = null; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
