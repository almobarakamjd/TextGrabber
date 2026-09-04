package com.oqod.textgrabber.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * طبقة شفافة تغطي كامل الشاشة أثناء "وضع التحديد".
 *
 * يرسم المستخدم بإصبعه مربعا فوق النص الذي يريد نسخه، وعند رفع الإصبع
 * يُستدعى [onSelectionComplete] بإحداثيات المربع النهائية على الشاشة.
 * الضغط دون سحب (نقرة بسيطة) يُعتبر إلغاء ويستدعي [onSelectionCancelled].
 */
class SelectionOverlayView(
    context: Context,
    private val onSelectionComplete: (Rect) -> Unit,
    private val onSelectionCancelled: () -> Unit
) : View(context) {

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#40000000")
        style = Paint.Style.FILL
    }

    private val rectPaint = Paint().apply {
        color = Color.parseColor("#606750A4")
        style = Paint.Style.FILL
    }

    private val rectBorderPaint = Paint().apply {
        color = Color.parseColor("#6750A4")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (isDragging) {
            val left = minOf(startX, currentX)
            val top = minOf(startY, currentY)
            val right = maxOf(startX, currentX)
            val bottom = maxOf(startY, currentY)
            canvas.drawRect(left, top, right, bottom, rectPaint)
            canvas.drawRect(left, top, right, bottom, rectBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                currentX = startX
                currentY = startY
                isDragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.rawX
                currentY = event.rawY
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val rect = Rect(
                    minOf(startX, currentX).toInt(),
                    minOf(startY, currentY).toInt(),
                    maxOf(startX, currentX).toInt(),
                    maxOf(startY, currentY).toInt()
                )
                isDragging = false
                if (rect.width() < MIN_SELECTION_SIZE_PX || rect.height() < MIN_SELECTION_SIZE_PX) {
                    onSelectionCancelled()
                } else {
                    onSelectionComplete(rect)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                onSelectionCancelled()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val MIN_SELECTION_SIZE_PX = 12
    }
}
