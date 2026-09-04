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
 * يُستدعى [onSelectionComplete] بإحداثيات المربع النهائية **على الشاشة**.
 * الضغط دون سحب (نقرة بسيطة) يُعتبر إلغاء ويستدعي [onSelectionCancelled].
 *
 * ملاحظة دقة: نستخدم إحداثيات اللمس المحلية (event.x / event.y) للرسم حتى
 * يتطابق المربع المرسوم مع الإصبع تماما بغض النظر عن موضع النافذة أو
 * أشرطة النظام، ثم نحوّل المربع النهائي إلى إحداثيات الشاشة عبر
 * getLocationOnScreen لمطابقته مع حدود عناصر إمكانية الوصول.
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

    private val rectBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6750A4")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // خطوط إرشادية رفيعة تمتد على كامل الشاشة عند حواف المربع لتسهيل
    // محاذاة التحديد مع بداية/نهاية سطر النص بدقة.
    private val guidePaint = Paint().apply {
        color = Color.parseColor("#806750A4")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDragging = false

    private val locationOnScreen = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (isDragging) {
            val left = minOf(startX, currentX)
            val top = minOf(startY, currentY)
            val right = maxOf(startX, currentX)
            val bottom = maxOf(startY, currentY)

            canvas.drawLine(0f, top, width.toFloat(), top, guidePaint)
            canvas.drawLine(0f, bottom, width.toFloat(), bottom, guidePaint)
            canvas.drawLine(left, 0f, left, height.toFloat(), guidePaint)
            canvas.drawLine(right, 0f, right, height.toFloat(), guidePaint)

            canvas.drawRect(left, top, right, bottom, rectPaint)
            canvas.drawRect(left, top, right, bottom, rectBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = startX
                currentY = startY
                isDragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                invalidate()

                val localLeft = minOf(startX, currentX)
                val localTop = minOf(startY, currentY)
                val localRight = maxOf(startX, currentX)
                val localBottom = maxOf(startY, currentY)

                if (localRight - localLeft < MIN_SELECTION_SIZE_PX ||
                    localBottom - localTop < MIN_SELECTION_SIZE_PX
                ) {
                    onSelectionCancelled()
                    return true
                }

                // تحويل المربع من إحداثيات هذه الطبقة إلى إحداثيات الشاشة الفعلية
                getLocationOnScreen(locationOnScreen)
                val screenRect = Rect(
                    (localLeft + locationOnScreen[0]).toInt(),
                    (localTop + locationOnScreen[1]).toInt(),
                    (localRight + locationOnScreen[0]).toInt(),
                    (localBottom + locationOnScreen[1]).toInt()
                )
                onSelectionComplete(screenRect)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                invalidate()
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
