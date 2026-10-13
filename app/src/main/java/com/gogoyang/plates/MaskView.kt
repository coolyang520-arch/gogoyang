package com.gogoyang.plates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Dims the camera preview everywhere except the recognition window. */
class MaskView(ctx: Context) : View(ctx) {
    var hole = RectF()
    private val paint = Paint().apply { color = Color.parseColor("#99000000") }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (hole.isEmpty) return
        c.drawRect(0f, 0f, w, hole.top, paint)
        c.drawRect(0f, hole.bottom, w, h, paint)
        c.drawRect(0f, hole.top, hole.left, hole.bottom, paint)
        c.drawRect(hole.right, hole.top, w, hole.bottom, paint)
    }
}
