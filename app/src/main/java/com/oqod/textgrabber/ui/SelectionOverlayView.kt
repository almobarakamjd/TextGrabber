package com.oqod.textgrabber.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * طبقة شفافة تغطي كامل الشاشة أثناء "وضع التحديد".
 *
 * يرسم المستخدم بإصبعه مربعا فوق المحتوى الذي يريده. عند رفع الإصبع لا
 * يتم تنفيذ أي إجراء فورا؛ بل يبقى المربع ظاهرا في "وضع التأكيد" بحدود
 * قابلة للتحكم (مقابض في الزوايا والمنتصف لتغيير الحجم، والسحب من داخله
 * لتحريكه كاملا)، ويظهر أسفله زرّان صغيران: "نسخ" (لاستخراج النص داخل
 * المربع) و"صورة" (لحفظ محتوى المربع كصورة). الضغط على أحد الزرين يستدعي
 * [onCopyText] أو [onSaveImage] بإحداثيات المربع النهائي **على الشاشة**.
 * الضغط خارج المربع وخارج الزرّين يبدأ تحديدا جديدا من الصفر، والنقر
 * البسيط دون سحب يُعتبر إلغاء ويستدعي [onSelectionCancelled].
 *
 * ملاحظة دقة: نستخدم إحداثيات اللمس المحلية (event.x / event.y) للرسم حتى
 * يتطابق المربع المرسوم مع الإصبع تماما بغض النظر عن موضع النافذة أو
 * أشرطة النظام، ثم نحوّل المربع النهائي إلى إحداثيات الشاشة عبر
 * getLocationOnScreen لمطابقته مع حدود عناصر إمكانية الوصول ولقص الصورة.
 */
class SelectionOverlayView(
    context: Context,
    private val onCopyText: (Rect) -> Unit,
    private val onSaveImage: (Rect) -> Unit,
    private val onSelectionCancelled: () -> Unit
) : View(context) {

    private enum class Mode { DRAWING, CONFIRMING }

    private enum class Handle {
        NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT
    }

    private enum class PendingButton { NONE, COPY, IMAGE }

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
    // محاذاة التحديد مع بداية/نهاية سطر النص بدقة (أثناء الرسم الأولي فقط).
    private val guidePaint = Paint().apply {
        color = Color.parseColor("#806750A4")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6750A4")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6750A4")
        style = Paint.Style.FILL
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = spToPx(15f)
    }

    private var mode = Mode.DRAWING

    // إحداثيات الرسم الأولي (وضع DRAWING)
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f

    // حدود المربع أثناء وضع التأكيد (قابلة للتعديل عبر المقابض/السحب)
    private val confirmedRect = RectF()

    private var activeHandle = Handle.NONE
    private var pendingButton = PendingButton.NONE
    private var dragLastX = 0f
    private var dragLastY = 0f

    // نصف قطر لمس أكبر من الحجم المرسوم فعليا لتسهيل الإمساك بالمقابض بالإصبع
    private val handleTouchRadiusPx = dpToPx(24f)
    private val handleVisualRadiusPx = dpToPx(7f).toFloat()
    private val cornerRadiusPx = dpToPx(8f).toFloat()

    private val buttonWidthPx = dpToPx(84f)
    private val buttonHeightPx = dpToPx(44f)
    private val buttonGapPx = dpToPx(12f)
    private val buttonMarginPx = dpToPx(16f)

    private val copyButtonRect = RectF()
    private val imageButtonRect = RectF()

    private val locationOnScreen = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        when (mode) {
            Mode.DRAWING -> drawDraggingSelection(canvas)
            Mode.CONFIRMING -> drawConfirmingSelection(canvas)
        }
    }

    private fun drawDraggingSelection(canvas: Canvas) {
        val left = min(startX, currentX)
        val top = min(startY, currentY)
        val right = max(startX, currentX)
        val bottom = max(startY, currentY)

        canvas.drawLine(0f, top, width.toFloat(), top, guidePaint)
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, guidePaint)
        canvas.drawLine(left, 0f, left, height.toFloat(), guidePaint)
        canvas.drawLine(right, 0f, right, height.toFloat(), guidePaint)

        canvas.drawRect(left, top, right, bottom, rectPaint)
        canvas.drawRect(left, top, right, bottom, rectBorderPaint)
    }

    private fun drawConfirmingSelection(canvas: Canvas) {
        canvas.drawRect(confirmedRect, rectPaint)
        canvas.drawRect(confirmedRect, rectBorderPaint)
        drawHandles(canvas)

        layoutButtons()
        drawButton(canvas, copyButtonRect, COPY_LABEL)
        drawButton(canvas, imageButtonRect, IMAGE_LABEL)
    }

    private fun drawHandles(canvas: Canvas) {
        val points = listOf(
            confirmedRect.left to confirmedRect.top,
            confirmedRect.right to confirmedRect.top,
            confirmedRect.left to confirmedRect.bottom,
            confirmedRect.right to confirmedRect.bottom,
            confirmedRect.centerX() to confirmedRect.top,
            confirmedRect.centerX() to confirmedRect.bottom,
            confirmedRect.left to confirmedRect.centerY(),
            confirmedRect.right to confirmedRect.centerY()
        )
        for ((x, y) in points) {
            canvas.drawCircle(x, y, handleVisualRadiusPx, handleFillPaint)
            canvas.drawCircle(x, y, handleVisualRadiusPx, handleBorderPaint)
        }
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String) {
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, buttonPaint)
        val textY = rect.centerY() - (buttonTextPaint.ascent() + buttonTextPaint.descent()) / 2
        canvas.drawText(label, rect.centerX(), textY, buttonTextPaint)
    }

    /** يضع زري "نسخ" و"صورة" جنبا إلى جنب أسفل المربع، أو أعلاه إن لم تتسع المساحة أسفله. */
    private fun layoutButtons() {
        val groupWidth = buttonWidthPx * 2 + buttonGapPx
        var top = confirmedRect.bottom + buttonMarginPx
        if (top + buttonHeightPx > height) {
            top = confirmedRect.top - buttonMarginPx - buttonHeightPx
        }
        val groupLeft = (confirmedRect.centerX() - groupWidth / 2f)
            .coerceIn(0f, width - groupWidth.toFloat())

        copyButtonRect.set(groupLeft, top, groupLeft + buttonWidthPx, top + buttonHeightPx)
        val imageLeft = copyButtonRect.right + buttonGapPx
        imageButtonRect.set(imageLeft, top, imageLeft + buttonWidthPx, top + buttonHeightPx)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return handleDown(event)
            MotionEvent.ACTION_MOVE -> return handleMove(event)
            MotionEvent.ACTION_UP -> return handleUp(event)
            MotionEvent.ACTION_CANCEL -> return handleCancel()
        }
        return super.onTouchEvent(event)
    }

    private fun handleDown(event: MotionEvent): Boolean {
        if (mode == Mode.CONFIRMING) {
            when {
                copyButtonRect.contains(event.x, event.y) -> pendingButton = PendingButton.COPY
                imageButtonRect.contains(event.x, event.y) -> pendingButton = PendingButton.IMAGE
                else -> {
                    pendingButton = PendingButton.NONE
                    val handle = findHandleAt(event.x, event.y)
                    when {
                        handle != Handle.NONE -> activeHandle = handle
                        confirmedRect.contains(event.x, event.y) -> activeHandle = Handle.MOVE
                        else -> {
                            // لمسة خارج كل شيء: ابدأ تحديدا جديدا من الصفر
                            mode = Mode.DRAWING
                            startX = event.x
                            startY = event.y
                            currentX = startX
                            currentY = startY
                        }
                    }
                    dragLastX = event.x
                    dragLastY = event.y
                }
            }
            invalidate()
            return true
        }

        startX = event.x
        startY = event.y
        currentX = startX
        currentY = startY
        invalidate()
        return true
    }

    private fun handleMove(event: MotionEvent): Boolean {
        if (mode == Mode.CONFIRMING) {
            when (activeHandle) {
                Handle.NONE -> {}
                Handle.MOVE -> {
                    val dx = event.x - dragLastX
                    val dy = event.y - dragLastY
                    translateConfirmedRect(dx, dy)
                    dragLastX = event.x
                    dragLastY = event.y
                    invalidate()
                }
                else -> {
                    applyHandleDrag(activeHandle, event.x, event.y)
                    invalidate()
                }
            }
            return true
        }
        currentX = event.x
        currentY = event.y
        invalidate()
        return true
    }

    private fun handleUp(event: MotionEvent): Boolean {
        if (mode == Mode.CONFIRMING) {
            if (activeHandle == Handle.NONE) {
                when {
                    pendingButton == PendingButton.COPY && copyButtonRect.contains(event.x, event.y) ->
                        dispatch(onCopyText)
                    pendingButton == PendingButton.IMAGE && imageButtonRect.contains(event.x, event.y) ->
                        dispatch(onSaveImage)
                }
            }
            activeHandle = Handle.NONE
            pendingButton = PendingButton.NONE
            return true
        }

        val localLeft = min(startX, currentX)
        val localTop = min(startY, currentY)
        val localRight = max(startX, currentX)
        val localBottom = max(startY, currentY)

        if (localRight - localLeft < MIN_SELECTION_SIZE_PX ||
            localBottom - localTop < MIN_SELECTION_SIZE_PX
        ) {
            onSelectionCancelled()
            return true
        }

        confirmedRect.set(localLeft, localTop, localRight, localBottom)
        mode = Mode.CONFIRMING
        invalidate()
        return true
    }

    private fun handleCancel(): Boolean {
        if (mode == Mode.CONFIRMING) {
            activeHandle = Handle.NONE
            pendingButton = PendingButton.NONE
            return true
        }
        onSelectionCancelled()
        return true
    }

    private fun dispatch(callback: (Rect) -> Unit) {
        getLocationOnScreen(locationOnScreen)
        val screenRect = Rect(
            (confirmedRect.left + locationOnScreen[0]).toInt(),
            (confirmedRect.top + locationOnScreen[1]).toInt(),
            (confirmedRect.right + locationOnScreen[0]).toInt(),
            (confirmedRect.bottom + locationOnScreen[1]).toInt()
        )
        callback(screenRect)
    }

    private fun findHandleAt(x: Float, y: Float): Handle {
        val r = confirmedRect
        val candidates = listOf(
            Handle.TOP_LEFT to (r.left to r.top),
            Handle.TOP_RIGHT to (r.right to r.top),
            Handle.BOTTOM_LEFT to (r.left to r.bottom),
            Handle.BOTTOM_RIGHT to (r.right to r.bottom),
            Handle.TOP to (r.centerX() to r.top),
            Handle.BOTTOM to (r.centerX() to r.bottom),
            Handle.LEFT to (r.left to r.centerY()),
            Handle.RIGHT to (r.right to r.centerY())
        )
        var best = Handle.NONE
        var bestDist = handleTouchRadiusPx.toFloat()
        for ((handle, point) in candidates) {
            val (px, py) = point
            val dist = hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()
            if (dist <= bestDist) {
                bestDist = dist
                best = handle
            }
        }
        return best
    }

    private fun applyHandleDrag(handle: Handle, x: Float, y: Float) {
        val minSize = MIN_SELECTION_SIZE_PX.toFloat()
        when (handle) {
            Handle.TOP_LEFT -> {
                confirmedRect.left = min(x, confirmedRect.right - minSize)
                confirmedRect.top = min(y, confirmedRect.bottom - minSize)
            }
            Handle.TOP_RIGHT -> {
                confirmedRect.right = max(x, confirmedRect.left + minSize)
                confirmedRect.top = min(y, confirmedRect.bottom - minSize)
            }
            Handle.BOTTOM_LEFT -> {
                confirmedRect.left = min(x, confirmedRect.right - minSize)
                confirmedRect.bottom = max(y, confirmedRect.top + minSize)
            }
            Handle.BOTTOM_RIGHT -> {
                confirmedRect.right = max(x, confirmedRect.left + minSize)
                confirmedRect.bottom = max(y, confirmedRect.top + minSize)
            }
            Handle.TOP -> confirmedRect.top = min(y, confirmedRect.bottom - minSize)
            Handle.BOTTOM -> confirmedRect.bottom = max(y, confirmedRect.top + minSize)
            Handle.LEFT -> confirmedRect.left = min(x, confirmedRect.right - minSize)
            Handle.RIGHT -> confirmedRect.right = max(x, confirmedRect.left + minSize)
            else -> {}
        }
        clampConfirmedRect()
    }

    private fun translateConfirmedRect(dx: Float, dy: Float) {
        val clampedDx = dx.coerceIn(-confirmedRect.left, width - confirmedRect.right)
        val clampedDy = dy.coerceIn(-confirmedRect.top, height - confirmedRect.bottom)
        confirmedRect.offset(clampedDx, clampedDy)
    }

    private fun clampConfirmedRect() {
        confirmedRect.left = confirmedRect.left.coerceIn(0f, width.toFloat())
        confirmedRect.top = confirmedRect.top.coerceIn(0f, height.toFloat())
        confirmedRect.right = confirmedRect.right.coerceIn(0f, width.toFloat())
        confirmedRect.bottom = confirmedRect.bottom.coerceIn(0f, height.toFloat())
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    private fun spToPx(sp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    }

    companion object {
        private const val MIN_SELECTION_SIZE_PX = 12
        private const val COPY_LABEL = "نسخ"
        private const val IMAGE_LABEL = "صورة"
    }
}
