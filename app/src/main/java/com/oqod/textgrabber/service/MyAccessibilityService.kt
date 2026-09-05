package com.oqod.textgrabber.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.os.BundleCompat
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.oqod.textgrabber.MainActivity
import com.oqod.textgrabber.R
import com.oqod.textgrabber.data.CopiedTextStore
import com.oqod.textgrabber.ui.SelectionOverlayView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * خدمة إمكانية الوصول (Accessibility Service) الأساسية في التطبيق.
 *
 * لا تنسخ الخدمة أي نص تلقائيا أثناء تصفح الشاشة (كانت هذه الطريقة الأولى،
 * لكنها كانت تلتقط نصوصا غير مقصودة مثل الإعلانات وتسميات الأزرار). بدلا من
 * ذلك، تعرض الخدمة زرا عائما صغيرا فوق كل التطبيقات؛ عند الضغط عليه يدخل
 * المستخدم في "وضع التحديد" ويرسم بإصبعه مربعا فوق النص الذي يريده تحديدا،
 * فتقرأ الخدمة فقط عناصر AccessibilityNodeInfo التي تقع داخل ذلك المربع
 * وتنسخها إلى الحافظة. لا يوجد أي التقاط لصورة الشاشة ولا OCR في أي مرحلة.
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val CHANNEL_ID = "text_grabber_channel"
        private const val NOTIFICATION_ID_TEXT = 1001
        private const val NOTIFICATION_ID_IMAGE = 1002
        private const val MAX_SNIPPET_LENGTH = 60

        // حد أدنى لحركة الإصبع (بالبكسل) لاعتبارها سحبا لمربع تحديد
        // وليست مجرد ضغطة عرضية أثناء تحريك الزر العائم.
        private const val DRAG_THRESHOLD_PX = 12

        // شفافية الزر العائم في حالته الطبيعية (خفيفة جدا) وأثناء اللمس (معتم بالكامل).
        private const val ALPHA_IDLE = 0.25f
        private const val ALPHA_PRESSED = 1f

        private const val PREFS_NAME = "text_grabber_service_prefs"
        private const val KEY_FLOATING_BUTTON_ENABLED = "floating_button_enabled"

        // مرجع للخدمة الجارية حاليا، تستخدمه شاشة الإعدادات وبلاطة
        // الإعدادات السريعة (Quick Settings Tile) لإظهار/إخفاء الزر العائم
        // فورا دون الحاجة لإعادة تشغيل الخدمة.
        @Volatile
        private var instance: MyAccessibilityService? = null

        fun isFloatingButtonEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_FLOATING_BUTTON_ENABLED, true)
        }

        /** تفعيل/إخفاء الزر العائم من أي مكان في التطبيق (الواجهة أو بلاطة الإعدادات السريعة). */
        fun setFloatingButtonEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_FLOATING_BUTTON_ENABLED, enabled).apply()
            instance?.applyFloatingButtonVisibility(enabled)
        }
    }

    private lateinit var windowManager: WindowManager

    // الزر العائم الذي يفتح وضع التحديد
    private var floatingButtonView: TextView? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null

    // منطقة "الإغلاق بالسحب" (علامة X) التي تظهر أسفل الشاشة أثناء سحب الزر
    private var closeZoneView: TextView? = null
    private var closeZoneParams: WindowManager.LayoutParams? = null

    // طبقة التحديد الشفافة التي تظهر فوق كامل الشاشة أثناء رسم المربع
    private var selectionOverlayView: SelectionOverlayView? = null

    // منفذ تنفيذ منفصل لمعالجة نتيجة التقاط الشاشة (takeScreenshot) خارج الخيط الرئيسي
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
        createNotificationChannelIfNeeded()
        applyFloatingButtonVisibility(isFloatingButtonEnabled(this))
    }

    private fun applyFloatingButtonVisibility(enabled: Boolean) {
        if (enabled) addFloatingButton() else removeFloatingButton()
    }

    /** لا حاجة للتصرف بناء على أحداث الواجهة؛ التحديد يدوي بالكامل عبر الزر العائم. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // لا شيء هنا عمدا.
    }

    // ---------------------------------------------------------------------
    // الزر العائم القابل للسحب
    // ---------------------------------------------------------------------

    private fun addFloatingButton() {
        if (floatingButtonView != null) return

        val sizePx = dpToPx(56)
        val button = TextView(this).apply {
            text = "T"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6750A4"))
            }
            // شفاف جدا في وضعه الطبيعي حتى لا يحجب محتوى الشاشة، ويصبح
            // معتما بالكامل فقط أثناء لمسه فعليا (اضغط أو اسحب).
            alpha = ALPHA_IDLE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_TOUCH_MODAL: يسمح بمرور اللمس للتطبيقات الأخرى خارج حدود الزر.
            // FLAG_NOT_FOCUSABLE: أساسي جدا - بدونه تسرق هذه النافذة الصغيرة تركيز
            // لوحة المفاتيح وزر الرجوع من كامل النظام، فيتعطل الكتابة في أي تطبيق آخر
            // ويتوقف زر الرجوع عن العمل طالما الزر العائم ظاهرا على الشاشة.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dpToPx(200)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    button.alpha = ALPHA_PRESSED
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) {
                        if (!moved) {
                            // أول لحظة يتحول فيها اللمس إلى سحب فعلي: أظهر منطقة
                            // الإغلاق (X) أسفل الشاشة، على طريقة "حباب" ماسنجر.
                            moved = true
                            showCloseZone()
                        }
                    }
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(button, params) }
                        updateCloseZoneHighlight(sizePx)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        if (isOverCloseZone(sizePx)) {
                            setFloatingButtonEnabled(this, false)
                            Toast.makeText(
                                this,
                                getString(R.string.floating_button_hidden_hint),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        hideCloseZone()
                    } else {
                        startSelectionMode()
                    }
                    button.alpha = ALPHA_IDLE
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideCloseZone()
                    button.alpha = ALPHA_IDLE
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(button, params) }
        floatingButtonView = button
        floatingButtonParams = params
    }

    private fun removeFloatingButton() {
        hideCloseZone()
        floatingButtonView?.let { runCatching { windowManager.removeView(it) } }
        floatingButtonView = null
        floatingButtonParams = null
    }

    // ---------------------------------------------------------------------
    // منطقة "اسحب هنا للإغلاق" (X) — تظهر فقط أثناء سحب الزر العائم
    // ---------------------------------------------------------------------

    private fun showCloseZone() {
        if (closeZoneView != null) return

        val sizePx = dpToPx(64)
        val view = TextView(this).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B3D93025"))
            }
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - sizePx) / 2
            y = screenHeight - dpToPx(140)
        }

        runCatching { windowManager.addView(view, params) }
        closeZoneView = view
        closeZoneParams = params
    }

    private fun hideCloseZone() {
        closeZoneView?.let { runCatching { windowManager.removeView(it) } }
        closeZoneView = null
        closeZoneParams = null
    }

    /** تكبير علامة X قليلا عندما يقترب الزر العائم منها، كإشارة بصرية للمستخدم. */
    private fun updateCloseZoneHighlight(buttonSizePx: Int) {
        val zoneView = closeZoneView ?: return
        val targetScale = if (isOverCloseZone(buttonSizePx)) 1.3f else 1f
        if (zoneView.scaleX != targetScale) {
            zoneView.scaleX = targetScale
            zoneView.scaleY = targetScale
        }
    }

    /** يتحقق إن كان مركز الزر العائم متداخلا مع منطقة الإغلاق حاليا. */
    private fun isOverCloseZone(buttonSizePx: Int): Boolean {
        val buttonParams = floatingButtonParams ?: return false
        val zoneParams = closeZoneParams ?: return false

        val buttonCenterX = buttonParams.x + buttonSizePx / 2
        val buttonCenterY = buttonParams.y + buttonSizePx / 2
        val zoneCenterX = zoneParams.x + zoneParams.width / 2
        val zoneCenterY = zoneParams.y + zoneParams.height / 2

        val dx = (buttonCenterX - zoneCenterX).toDouble()
        val dy = (buttonCenterY - zoneCenterY).toDouble()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val threshold = (buttonSizePx / 2) + (zoneParams.width / 2)
        return distance < threshold
    }

    // ---------------------------------------------------------------------
    // وضع التحديد اليدوي (رسم مربع فوق الشاشة)
    // ---------------------------------------------------------------------

    private fun startSelectionMode() {
        if (selectionOverlayView != null) return

        Toast.makeText(
            this,
            getString(R.string.selection_mode_hint),
            Toast.LENGTH_SHORT
        ).show()

        val overlay = SelectionOverlayView(
            context = this,
            onCopyText = { rect -> handleCopyText(rect) },
            onSaveImage = { rect -> handleSaveImage(rect) },
            onSelectionCancelled = { removeSelectionOverlay() }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_FOCUSABLE: تمنع هذه النافذة من سرقة تركيز لوحة المفاتيح/زر
            // الرجوع (اللمس يبقى يعمل بدون الحاجة للتركيز).
            // FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS: تجعل النافذة تغطي كامل
            // الشاشة الفعلية بما فيها مناطق الشريط العلوي/السفلي، بحيث تتطابق
            // إحداثيات اللمس (rawX/rawY) تماما مع إحداثيات الرسم على الشاشة.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        runCatching { windowManager.addView(overlay, params) }
        selectionOverlayView = overlay
    }

    private fun removeSelectionOverlay() {
        selectionOverlayView?.let { runCatching { windowManager.removeView(it) } }
        selectionOverlayView = null
    }

    private fun handleCopyText(selectedRect: Rect) {
        removeSelectionOverlay()

        if (selectedRect.width() < DRAG_THRESHOLD_PX || selectedRect.height() < DRAG_THRESHOLD_PX) {
            return
        }

        val text = extractTextInRect(selectedRect)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.selection_no_text_found), Toast.LENGTH_SHORT).show()
            return
        }

        copyToClipboard(text)
        CopiedTextStore.addText(text)
        showCopyNotification(text)
        // تأكيد فوري على الشاشة بأن النسخ نجح، بالإضافة إلى الإشعار.
        Toast.makeText(this, getString(R.string.copied_to_clipboard_toast), Toast.LENGTH_SHORT).show()
    }

    private fun handleSaveImage(selectedRect: Rect) {
        removeSelectionOverlay()

        if (selectedRect.width() < DRAG_THRESHOLD_PX || selectedRect.height() < DRAG_THRESHOLD_PX) {
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, getString(R.string.image_not_supported_toast), Toast.LENGTH_LONG).show()
            return
        }

        captureScreenshot { fullBitmap ->
            if (fullBitmap == null) {
                Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
                return@captureScreenshot
            }

            val cropped = runCatching { cropBitmapToRect(fullBitmap, selectedRect) }.getOrNull()
            fullBitmap.recycle()

            if (cropped == null) {
                Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
                return@captureScreenshot
            }

            val uri = runCatching { saveBitmapToGallery(cropped) }.getOrNull()
            cropped.recycle()

            if (uri == null) {
                Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
                return@captureScreenshot
            }

            copyImageToClipboard(uri)
            showImageSavedNotification()
            Toast.makeText(this, getString(R.string.image_saved_toast), Toast.LENGTH_SHORT).show()
        }
    }

    /** يلتقط لقطة لكامل الشاشة الحالية عبر AccessibilityService.takeScreenshot (يتطلب أندرويد 11+). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshot(onResult: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = runCatching {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap?.recycle()
                        result.hardwareBuffer.close()
                        softwareBitmap
                    }.getOrNull()
                    mainHandlerPost { onResult(bitmap) }
                }

                override fun onFailure(errorCode: Int) {
                    mainHandlerPost { onResult(null) }
                }
            }
        )
    }

    private fun mainHandlerPost(action: () -> Unit) {
        android.os.Handler(mainLooper).post(action)
    }

    private fun cropBitmapToRect(source: Bitmap, target: Rect): Bitmap {
        val left = target.left.coerceIn(0, source.width)
        val top = target.top.coerceIn(0, source.height)
        val right = target.right.coerceIn(left, source.width)
        val bottom = target.bottom.coerceIn(top, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    /** يحفظ الصورة في معرض الصور (Pictures/TextGrabber) عبر MediaStore، ويعيد رابط (Uri) الصورة المحفوظة. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveBitmapToGallery(bitmap: Bitmap): android.net.Uri? {
        val fileName = "textgrabber_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TextGrabber")
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return null
        return uri
    }

    /** ينسخ رابط الصورة المحفوظة إلى الحافظة حتى يمكن لصقها كصورة في تطبيقات أخرى. */
    private fun copyImageToClipboard(uri: android.net.Uri) {
        val clipboardManager =
            getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newUri(contentResolver, "TextGrabber Image", uri)
        clipboardManager.setPrimaryClip(clip)
    }

    /**
     * يقرأ شجرة AccessibilityNodeInfo للنافذة النشطة حاليا، ويجمع نص كل
     * العناصر التي تتقاطع حدودها مع مربع التحديد الذي رسمه المستخدم فقط.
     */
    private fun extractTextInRect(target: Rect): String? {
        val root = rootInActiveWindow ?: return null
        val results = LinkedHashSet<String>()
        try {
            collectTextInRect(root, target, results)
        } finally {
            root.recycle()
        }
        return if (results.isEmpty()) null else results.joinToString(separator = "\n")
    }

    private fun collectTextInRect(node: AccessibilityNodeInfo, target: Rect, results: MutableSet<String>) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (Rect.intersects(bounds, target)) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                // المستوى الأدق: كلمات فقط بناء على مواقع الأحرف الفعلية على الشاشة
                val precise = extractWordsInsideRect(node, text, target)
                when {
                    precise != null -> if (precise.isNotBlank()) results.add(precise)
                    // احتياطي عندما لا يوفر التطبيق مواقع الأحرف: نأخذ نص العنصر
                    // كاملا فقط إن كان جزء معتبر منه داخل المربع (وليس مجرد تلامس حافة).
                    isMeaningfullyInside(bounds, target) -> results.add(text.trim())
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextInRect(child, target, results)
            child.recycle()
        }
    }

    /**
     * يطلب من النظام إحداثيات كل حرف في نص العنصر على الشاشة، ثم يعيد فقط
     * الكلمات التي يقع مركزها داخل مربع التحديد، محافظا على الفواصل الأصلية
     * بين الكلمات المتجاورة. يعيد null إن لم يوفر التطبيق المصدر هذه البيانات.
     */
    private fun extractWordsInsideRect(node: AccessibilityNodeInfo, originalText: String, target: Rect): String? {
        val length = minOf(
            originalText.length,
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH
        )
        if (length == 0) return null

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        val refreshed = runCatching {
            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
        }.getOrDefault(false)
        if (!refreshed) return null

        // النص قد يتغير بعد التحديث، لذا نعيد قراءته
        val text = node.text?.toString() ?: return null
        val charRects = BundleCompat.getParcelableArray(
            node.extras,
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
            RectF::class.java
        ) ?: return null
        if (charRects.isEmpty()) return null

        val usable = minOf(text.length, charRects.size)
        val builder = StringBuilder()
        var lastIncludedEnd = -1

        var i = 0
        while (i < usable) {
            if (text[i].isWhitespace()) { i++; continue }
            val wordStart = i
            while (i < usable && !text[i].isWhitespace()) i++
            val wordEnd = i // حصري

            // اتحاد مستطيلات أحرف هذه الكلمة
            var union: RectF? = null
            for (c in wordStart until wordEnd) {
                val r = charRects[c] as? RectF ?: continue
                if (union == null) union = RectF(r) else union.union(r)
            }
            if (union == null) continue

            val centerX = union.centerX().toInt()
            val centerY = union.centerY().toInt()
            if (target.contains(centerX, centerY)) {
                if (builder.isNotEmpty()) {
                    // إن كانت الكلمة السابقة المضمّنة تسبق هذه مباشرة، احتفظ بالفاصل الأصلي
                    // (مسافة أو سطر جديد)، وإلا استخدم مسافة واحدة.
                    val gap = text.substring(lastIncludedEnd, wordStart)
                    builder.append(if (gap.isNotEmpty() && gap.all { it.isWhitespace() } && lastIncludedEnd >= 0) gap else " ")
                }
                builder.append(text, wordStart, wordEnd)
                lastIncludedEnd = wordEnd
            }
        }
        return builder.toString()
    }

    /** يعتبر العنصر داخل المربع إن كان مركزه بداخله أو تغطية المربع له 50% أو أكثر. */
    private fun isMeaningfullyInside(bounds: Rect, target: Rect): Boolean {
        if (target.contains(bounds.centerX(), bounds.centerY())) return true
        val intersection = Rect()
        if (!intersection.setIntersect(bounds, target)) return false
        val nodeArea = bounds.width().toLong() * bounds.height().toLong()
        if (nodeArea <= 0) return false
        val interArea = intersection.width().toLong() * intersection.height().toLong()
        return interArea * 2 >= nodeArea
    }

    // ---------------------------------------------------------------------
    // نسخ للحافظة + إشعار
    // ---------------------------------------------------------------------

    /** نسخ النص إلى حافظة النظام (Clipboard) */
    private fun copyToClipboard(text: String) {
        val clipboardManager =
            getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("TextGrabber", text)
        clipboardManager.setPrimaryClip(clip)
    }

    /** إنشاء قناة الإشعارات (مطلوبة من Android 8 فأعلى) */
    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_description)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /** إظهار إشعار بسيط يوضح مقتطفًا من النص الذي تم نسخه للتو */
    private fun showCopyNotification(fullText: String) {
        val snippet = if (fullText.length > MAX_SNIPPET_LENGTH) {
            fullText.substring(0, MAX_SNIPPET_LENGTH) + "…"
        } else {
            fullText
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(snippet)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snippet))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID_TEXT, notification)
    }

    /** إظهار إشعار بسيط يؤكد حفظ الصورة المحددة في المعرض ونسخها إلى الحافظة */
    private fun showImageSavedNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(getString(R.string.image_notification_title))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID_IMAGE, notification)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, openAppIntent, pendingIntentFlags)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    /** يُستدعى عندما يقاطع النظام الخدمة (مثلاً عند طلب إيقاف مؤقت) */
    override fun onInterrupt() {
        // لا حاجة لتنظيف موارد خاصة هنا حالياً، لكن الدالة إلزامية التطبيق
    }

    override fun onUnbind(intent: Intent?): Boolean {
        selectionOverlayView?.let { runCatching { windowManager.removeView(it) } }
        selectionOverlayView = null
        removeFloatingButton()
        instance = null
        return super.onUnbind(intent)
    }
}
