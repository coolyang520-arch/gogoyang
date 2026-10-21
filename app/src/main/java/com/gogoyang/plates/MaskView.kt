package com.gogoyang.plates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Dims the camera preview outside the guide frame and marks where the plate should sit. */
class MaskView(ctx: Context) : View(ctx) {
    var hole = RectF()
    private val paint = Paint().apply { color = Color.parseColor("#99000000") }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 3f * ctx.resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (hole.isEmpty) return
        c.drawRect(0f, 0f, w, hole.top, paint)
        c.drawRect(0f, hole.bottom, w, h, paint)
        c.drawRect(0f, hole.top, hole.left, hole.bottom, paint)
        c.drawRect(hole.right, hole.top, w, hole.bottom, paint)
        val hw = hole.width()
        val hh = hole.height()
        c.drawRect(
            hole.left + hw * Decide.PLATE_LEFT, hole.top + hh * Decide.PLATE_TOP,
            hole.left + hw * Decide.PLATE_RIGHT, hole.top + hh * Decide.PLATE_BOTTOM, platePaint
        )
    }
}
