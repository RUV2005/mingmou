package com.danmo.mingmou

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var textBlocks: List<Rect> = emptyList()

    fun setTextBlocks(blocks: List<Rect>) {
        textBlocks = blocks
        invalidate() // 重新绘制
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        textBlocks.forEach { rect ->
            canvas.drawRect(rect, paint)
        }
    }
}