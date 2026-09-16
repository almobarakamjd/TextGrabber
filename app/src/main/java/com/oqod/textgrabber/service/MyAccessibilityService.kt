package com.oqod.textgrabber.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.os.BundleCompat
import android.provider.MediaStore
import android.util.Log
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
import com.oqod.textgrabber.capture.Box
import com.oqod.textgrabber.capture.CopyMode
import com.oqod.textgrabber.capture.MixedContentComposer
import com.oqod.textgrabber.capture.PlacedText
import com.oqod.textgrabber.capture.ScreenWord
import com.oqod.textgrabber.capture.SelectionAnalyzer
import com.oqod.textgrabber.capture.TextBlock
import com.oqod.textgrabber.data.CopiedTextStore
import com.oqod.textgrabber.ocr.OcrEngine
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
        private const val TAG = "TextGrabberService"
        private const val CHANNEL_ID = "text_grabber_channel"

        // حارس أمان طبقة التحديد: نفحص كل 15 ثانية، ونزيل الطبقة إن مضت
        // دقيقتان بلا أي لمس (غادر المستخدم السياق وتركها معلّقة).
        private const val SELECTION_IDLE_CHECK_MS = 15_000L
        private const val SELECTION_IDLE_TIMEOUT_MS = 120_000L

        // حدود الالتقاط الممتد (تمرير الصفحة تلقائيا لتجاوز حافة الشاشة).
        private const val MAX_EXTENDED_PAGES = 12
        private const val SCROLL_GESTURE_DURATION_MS = 300L
        // مهلة استقرار المحتوى بعد كل تمريرة، وهي أيضا أكبر من الحد الذي
        // يفرضه النظام على تكرار takeScreenshot (نحو ثانية).
        private const val SCROLL_SETTLE_DELAY_MS = 1_100L
        // نسبة التداخل المتعمّد بين كل صفحتين: تضمن ألا يضيع سطر عند الحافة،
        // وتعطي خوارزمية دمج الصور منطقة مشتركة تتعرف عليها.
        private const val SCROLL_OVERLAP_RATIO = 0.18f

        // معاملات كشف التداخل بين لقطتين متتاليتين عند دمج الصورة الممتدة.
        private const val SAMPLE_COLUMNS = 12
        private const val SAMPLE_ROWS = 6
        private const val MIN_OVERLAP_PX = 16
        private const val OVERLAP_STEP_PX = 4
        private const val OVERLAP_MATCH_THRESHOLD = 400

        // إزالة نافذة من WindowManager لا تنعكس على الشاشة فورا؛ ننتظر
        // لحظة قبل الالتقاط وإلا ظهر الزر العائم داخل الصورة الملتقطة.
        private const val WINDOW_REMOVAL_DELAY_MS = 180L

        // أصغر عنصر صورة يُقرأ في النسخ المركب. الحد مقصود لاستبعاد الأيقونات
        // والصور الشخصية الصغيرة (40-56dp) التي لا تحوي نصا، بينما الصور
        // والملصقات التي تحمل كتابة تكون أكبر من ذلك عادة.
        private const val MIN_IMAGE_WIDTH_DP = 96
        private const val MIN_IMAGE_HEIGHT_DP = 32

        // حين لا تكشف الواجهة عناصر صور، نعتبر المربع مركبا فقط إن كانت
        // المساحة الخالية من النص معتبرة، لا مجرد تباعد بين الفقرات.
        private const val MIN_UNCOVERED_RATIO_FOR_MIXED = 0.35f

        // عنصرا صورة يُدمجان في منطقة واحدة إن تداخلا بهذه النسبة من الأصغر.
        private const val IMAGE_MERGE_OVERLAP_RATIO = 0.5f
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

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * عند انطفاء الشاشة نزيل طبقة التحديد فورا: المستخدم غادر السياق ولن
     * يُكمل التحديد، وبقاؤها يجعل الزر العائم غير قابل للضغط عند العودة.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                removeSelectionOverlay()
            }
        }
    }
    private var screenOffReceiverRegistered = false

    /** هل أُخفي الزر العائم مؤقتا لأن التقاطا جاريا الآن؟ */
    private var floatingButtonHiddenForCapture = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
        createNotificationChannelIfNeeded()
        registerScreenOffReceiver()
        applyFloatingButtonVisibility(isFloatingButtonEnabled(this))
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        runCatching {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }.onSuccess {
            screenOffReceiverRegistered = true
        }.onFailure {
            Log.e(TAG, "تعذّر تسجيل مستقبل انطفاء الشاشة", it)
        }
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
        // تعافٍ ذاتي: إن بقي مرجع لعرض لم يعد مرتبطا فعليا بنافذة (فشل إضافة
        // سابق، أو أزاله النظام)، ننظّفه بدل أن نرتد ونترك الزر مختفيا للأبد.
        floatingButtonView?.let { existing ->
            if (existing.isAttachedToWindow) return
            Log.w(TAG, "مرجع زر عائم قديم غير مرتبط بنافذة — يُنظَّف ويُعاد إنشاؤه")
            removeFloatingButton()
        }

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

        // لا نُسنِد المراجع إلا بعد نجاح addView فعليا. كان الإسناد سابقا غير
        // مشروط، فإن فشلت الإضافة مرة واحدة (رمز نافذة منتهٍ بعد إعادة ربط
        // الخدمة، أو موت العملية) يبقى floatingButtonView مملوءا بعرض لم يُضف،
        // فيرتد addFloatingButton() من حارس "if (floatingButtonView != null)"
        // إلى الأبد: الزر لا يظهر ولا تستجيب البلاطة حتى إعادة تشغيل الجهاز.
        val added = runCatching { windowManager.addView(button, params) }
        if (added.isFailure) {
            Log.e(TAG, "فشل إضافة الزر العائم إلى WindowManager", added.exceptionOrNull())
            floatingButtonView = null
            floatingButtonParams = null
            return
        }
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

        val added = runCatching { windowManager.addView(view, params) }
        if (added.isFailure) {
            Log.e(TAG, "فشل إضافة منطقة الإغلاق إلى WindowManager", added.exceptionOrNull())
            closeZoneView = null
            closeZoneParams = null
            return
        }
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
        // نفس التعافي الذاتي: مرجع طبقة تحديد قديمة غير مرتبطة بنافذة كان
        // يقفل الزر العائم نهائيا (الضغط عليه لا يفعل شيئا).
        selectionOverlayView?.let { existing ->
            if (existing.isAttachedToWindow) return
            Log.w(TAG, "مرجع طبقة تحديد قديمة غير مرتبطة بنافذة — يُنظَّف")
            removeSelectionOverlay()
        }

        Toast.makeText(
            this,
            getString(R.string.selection_mode_hint),
            Toast.LENGTH_SHORT
        ).show()

        val overlay = SelectionOverlayView(
            context = this,
            onAnalyze = { rect -> analyzeSelection(rect) },
            onCopyText = { rect, mode, extended -> handleCopy(rect, mode, extended) },
            onSaveImage = { rect, extended -> handleSaveImage(rect, extended) },
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

        val added = runCatching { windowManager.addView(overlay, params) }
        if (added.isFailure) {
            Log.e(TAG, "فشل إضافة طبقة التحديد إلى WindowManager", added.exceptionOrNull())
            selectionOverlayView = null
            return
        }
        selectionOverlayView = overlay
        scheduleSelectionOverlayWatchdog()
    }

    private fun removeSelectionOverlay() {
        mainHandler.removeCallbacks(selectionOverlayWatchdog)
        selectionOverlayView?.let { runCatching { windowManager.removeView(it) } }
        selectionOverlayView = null
    }

    /**
     * حارس أمان لطبقة التحديد: منذ الإصدار 1.3.0 صارت الطبقة تبقى في "وضع
     * التأكيد" حتى يضغط المستخدم "نسخ" أو "صورة"، فإن غادر السياق (شاشة
     * الرئيسية، تبديل تطبيق) بقيت الطبقة تغطي الشاشة وتبتلع كل اللمسات،
     * فيبدو الزر العائم ميتا. هنا نزيلها تلقائيا بعد فترة خمول بلا أي لمس.
     */
    private fun scheduleSelectionOverlayWatchdog() {
        mainHandler.removeCallbacks(selectionOverlayWatchdog)
        mainHandler.postDelayed(selectionOverlayWatchdog, SELECTION_IDLE_CHECK_MS)
    }

    private val selectionOverlayWatchdog: Runnable = Runnable {
        val overlay = selectionOverlayView ?: return@Runnable
        val idleMs = System.currentTimeMillis() - overlay.lastTouchAtMs
        if (idleMs >= SELECTION_IDLE_TIMEOUT_MS) {
            Log.w(TAG, "إزالة طبقة تحديد خاملة منذ ${idleMs}ms")
            removeSelectionOverlay()
        } else {
            mainHandler.postDelayed(selectionOverlayWatchdog, SELECTION_IDLE_CHECK_MS)
        }
    }

    // ---------------------------------------------------------------------
    // تحليل محتوى المربع واختيار وضع النسخ المناسب
    // ---------------------------------------------------------------------

    /**
     * فحص خفيف وسريع لمحتوى المربع، تستدعيه طبقة التحديد بعد كل تعديل
     * لحدوده ليقرر عنوان زر النسخ. لا يشغّل OCR ولا يلتقط صورة: يكتفي
     * بحدود عناصر النص في شجرة إمكانية الوصول.
     */
    private fun analyzeSelection(selectedRect: Rect): CopyMode {
        val bounds = collectTextBounds(selectedRect)
        if (bounds.isEmpty()) return CopyMode.IMAGE
        if (collectImageRegions(selectedRect).isNotEmpty()) return CopyMode.MIXED

        // احتياط للتطبيقات التي ترسم صورها بنفسها فلا تظهر عناصر صور في
        // الواجهة: نعتمد على المساحة الخالية من النص، بشرط أن تكون معتبرة.
        val uncovered = SelectionAnalyzer.findImageBands(selectedRect, bounds)
            .sumOf { it.height() }
        val ratio = uncovered.toFloat() / selectedRect.height().coerceAtLeast(1)
        return if (ratio >= MIN_UNCOVERED_RATIO_FOR_MIXED) CopyMode.MIXED else CopyMode.TEXT
    }

    /**
     * يكتشف عناصر الصور داخل المربع **بحدودها الحقيقية** من شجرة الواجهة،
     * لتُقرأ كل صورة وحدها بسياقها الكامل في النسخ المركب.
     *
     * أصناف الصور المعتمدة تغطي الحالات الشائعة: `ImageView` في التطبيقات
     * الأصلية وفي Compose (يعرّف صوره بهذا الصنف)، و`android.widget.Image`
     * في Chrome وWebView. أما `ImageButton` فمستبعد لأنه أيقونات أزرار.
     */
    private fun collectImageRegions(target: Rect): List<Rect> {
        val root = rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<Rect>()
        try {
            collectImageRegionsRecursive(root, target, found)
        } finally {
            root.recycle()
        }
        return mergeOverlappingRegions(found)
    }

    private fun collectImageRegionsRecursive(
        node: AccessibilityNodeInfo,
        target: Rect,
        result: MutableList<Rect>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!Rect.intersects(bounds, target)) return

        if (node.isVisibleToUser && isImageNode(node)) {
            val clipped = Rect(bounds)
            if (clipped.intersect(target) &&
                clipped.width() >= dpToPx(MIN_IMAGE_WIDTH_DP) &&
                clipped.height() >= dpToPx(MIN_IMAGE_HEIGHT_DP)
            ) {
                result.add(clipped)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectImageRegionsRecursive(child, target, result)
            child.recycle()
        }
    }

    private fun isImageNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: return false
        return className == "android.widget.ImageView" ||
            className == "android.widget.Image" ||
            className.endsWith(".ImageView")
    }

    /**
     * صورة داخل صورة (غلاف وصورته، أو طبقات متراكبة) تُقرأ مرة واحدة: ندمج
     * كل منطقتين متداخلتين بنصف مساحة الأصغر على الأقل.
     */
    private fun mergeOverlappingRegions(regions: List<Rect>): List<Rect> {
        val merged = mutableListOf<Rect>()
        for (region in regions.sortedByDescending { it.width().toLong() * it.height() }) {
            val area = region.width().toLong() * region.height()
            val host = merged.firstOrNull { existing ->
                val overlap = Rect()
                overlap.setIntersect(existing, region) &&
                    overlap.width().toLong() * overlap.height() >= area * IMAGE_MERGE_OVERLAP_RATIO
            }
            if (host != null) host.union(region) else merged.add(Rect(region))
        }
        return merged
    }

    /** حدود كل عنصر نصي يقع فعليا داخل المربع (بلا استخراج النص نفسه). */
    private fun collectTextBounds(target: Rect): List<Rect> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<Rect>()
        try {
            collectTextBoundsRecursive(root, target, result)
        } finally {
            root.recycle()
        }
        return result
    }

    private fun collectTextBoundsRecursive(
        node: AccessibilityNodeInfo,
        target: Rect,
        result: MutableList<Rect>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (Rect.intersects(bounds, target) && !node.text.isNullOrBlank() &&
            isMeaningfullyInside(bounds, target)
        ) {
            val clipped = Rect(bounds)
            if (clipped.intersect(target)) result.add(clipped)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextBoundsRecursive(child, target, result)
            child.recycle()
        }
    }

    /** كتل النص المقروءة من الواجهة داخل المربع، مع مواضعها على الشاشة. */
    private fun collectTextBlocks(target: Rect): List<TextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<TextBlock>()
        try {
            collectTextBlocksRecursive(root, target, result)
        } finally {
            root.recycle()
        }
        return result
    }

    private fun collectTextBlocksRecursive(
        node: AccessibilityNodeInfo,
        target: Rect,
        result: MutableList<TextBlock>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (Rect.intersects(bounds, target)) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                val precise = extractWordsInsideRect(node, text, target)
                val value = when {
                    precise != null -> precise.takeIf { it.isNotBlank() }
                    isMeaningfullyInside(bounds, target) -> text.trim()
                    else -> null
                }
                if (value != null) result.add(TextBlock(value, bounds.top, Rect(bounds)))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextBlocksRecursive(child, target, result)
            child.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // زر النسخ التكيّفي (نص / OCR / مركب) بشقيه العادي والممتد
    // ---------------------------------------------------------------------

    private fun handleCopy(selectedRect: Rect, mode: CopyMode, extended: Boolean) {
        removeSelectionOverlay()

        if (selectedRect.width() < DRAG_THRESHOLD_PX || selectedRect.height() < DRAG_THRESHOLD_PX) {
            return
        }

        if (mode != CopyMode.TEXT && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, getString(R.string.image_not_supported_toast), Toast.LENGTH_LONG).show()
            return
        }

        if (mode != CopyMode.TEXT || extended) {
            Toast.makeText(this, getString(R.string.ocr_working_toast), Toast.LENGTH_SHORT).show()
        }

        if (extended) {
            hideFloatingButtonThen { startExtendedTextCapture(selectedRect, mode) }
            return
        }

        if (mode == CopyMode.TEXT) {
            deliverText(SelectionAnalyzer.merge(collectTextBlocks(selectedRect)))
            return
        }

        hideFloatingButtonThen {
            capturePageBlocks(selectedRect, mode) { blocks ->
                restoreFloatingButtonAfterCapture()
                deliverText(SelectionAnalyzer.merge(blocks))
            }
        }
    }

    /**
     * يجمع كتل نص صفحة واحدة: نص الواجهة (إن طُلب)، إضافة إلى نص مستخرج
     * بالـOCR من الأشرطة التي لا يغطيها نص — وهي الصور داخل المربع. بهذا
     * يخرج "النسخ المركب" مرتبا: نص الصورة الأولى، ثم الفقرة التي تحتها،
     * ثم نص الصورة التالية، وهكذا حسب موضع كل كتلة على الشاشة.
     */
    private fun capturePageBlocks(
        target: Rect,
        mode: CopyMode,
        onResult: (List<TextBlock>) -> Unit
    ) {
        when (mode) {
            CopyMode.TEXT -> onResult(collectTextBlocks(target))
            CopyMode.IMAGE -> captureWholeBoxOcr(target, onResult)
            CopyMode.MIXED -> captureMixed(target, onResult)
        }
    }

    /** "نسخ OCR": المربع كله يُقرأ دفعة واحدة بسياقه الكامل. */
    private fun captureWholeBoxOcr(target: Rect, onResult: (List<TextBlock>) -> Unit) {
        captureCrop(target) { cropped ->
            if (cropped == null) {
                onResult(emptyList())
                return@captureCrop
            }
            runOnOcrThread(onFailure = { cropped.recycle(); onResult(emptyList()) }) {
                val text = OcrEngine.recognize(this, cropped)
                cropped.recycle()
                mainHandlerPost {
                    onResult(listOfNotNull(text?.let { TextBlock(it, target.top, Rect(target)) }))
                }
            }
        }
    }

    /**
     * "نسخ مركب": كل صورة في المربع تُقصّ **وحدها** وتُقرأ بسياقها الكامل،
     * ونص الواجهة يؤخذ كما هو لأنه لا يخطئ، ثم يُركَّب الكل حسب موضعه على
     * الشاشة ([MixedContentComposer]).
     *
     * إن لم تكشف الواجهة أي عنصر صورة (تطبيق يرسم صوره بنفسه)، يُقرأ المربع
     * كاملا دفعة واحدة، ويحذف المركِّب من نتيجته ما يقع فوق نص الواجهة.
     */
    private fun captureMixed(target: Rect, onResult: (List<TextBlock>) -> Unit) {
        val uiBlocks = collectTextBlocks(target)
        val regions = collectImageRegions(target).ifEmpty { listOf(Rect(target)) }

        captureScreenshot { fullBitmap ->
            if (fullBitmap == null) {
                onResult(uiBlocks)
                return@captureScreenshot
            }

            // موضع كل قصاصة على الشاشة بعد حصرها داخل حدود اللقطة، لإرجاع
            // مواضع كلماتها إلى إحداثيات الشاشة.
            val crops = regions.mapNotNull { region ->
                val cropped = runCatching { cropBitmapToRect(fullBitmap, region) }.getOrNull()
                    ?: return@mapNotNull null
                val originX = region.left.coerceIn(0, fullBitmap.width)
                val originY = region.top.coerceIn(0, fullBitmap.height)
                Triple(originX, originY, cropped)
            }
            fullBitmap.recycle()

            if (crops.isEmpty()) {
                onResult(uiBlocks)
                return@captureScreenshot
            }

            runOnOcrThread(onFailure = { crops.forEach { it.third.recycle() }; onResult(uiBlocks) }) {
                val results = OcrEngine.recognizeWords(this, crops.map { it.third })
                crops.forEach { it.third.recycle() }

                if (results == null) {
                    mainHandlerPost { onResult(uiBlocks) }
                    return@runOnOcrThread
                }

                val screenWords = results.flatMapIndexed { index, words ->
                    val (originX, originY) = crops[index]
                    words.map { word ->
                        ScreenWord(
                            word = word,
                            region = index,
                            box = Box(
                                originX + word.left,
                                originY + word.top,
                                originX + word.right,
                                originY + word.bottom
                            )
                        )
                    }
                }

                val composed = MixedContentComposer.compose(uiBlocks.map { it.toPlacedText() }, screenWords)
                mainHandlerPost { onResult(composed.map { it.toTextBlock() }) }
            }
        }
    }

    private fun runOnOcrThread(onFailure: () -> Unit, task: () -> Unit) {
        runCatching { screenshotExecutor.execute(task) }.onFailure {
            Log.e(TAG, "تعذّر جدولة مهمة التعرّف على النص", it)
            onFailure()
        }
    }

    private fun TextBlock.toPlacedText() =
        PlacedText(text, Box(bounds.left, top, bounds.right, top + bounds.height()))

    private fun PlacedText.toTextBlock() =
        TextBlock(text, box.top, Rect(box.left, box.top, box.right, box.bottom))

    private fun deliverText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.selection_no_text_found), Toast.LENGTH_SHORT).show()
            return
        }

        copyToClipboard(text)
        CopiedTextStore.addText(text)
        showCopyNotification(text)
        Toast.makeText(this, getString(R.string.copied_to_clipboard_toast), Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // الالتقاط الممتد: تمرير الصفحة تلقائيا لتجاوز حافة الشاشة
    // ---------------------------------------------------------------------

    /** يجمع نص صفحات متتالية بتمرير المحتوى تحت المربع مرة بعد مرة. */
    private fun startExtendedTextCapture(target: Rect, mode: CopyMode) {
        val collected = mutableListOf<TextBlock>()
        var offset = 0

        fun step(page: Int) {
            capturePageBlocks(target, mode) { blocks ->
                // نزيح المواضع بمقدار ما مُرّر حتى الآن، فتبقى الكتل مرتبة
                // ترتيبا صحيحا عبر الصفحات لا داخل الصفحة الواحدة فقط.
                val shift = offset
                collected += blocks.map { TextBlock(it.text, it.top + shift, it.bounds) }

                if (page + 1 >= MAX_EXTENDED_PAGES) {
                    finishExtendedText(collected)
                    return@capturePageBlocks
                }

                val distance = scrollDistanceFor(target)
                scrollSelection(target) { scrolled ->
                    if (!scrolled) {
                        finishExtendedText(collected)
                    } else {
                        offset += distance
                        mainHandler.postDelayed({ step(page + 1) }, SCROLL_SETTLE_DELAY_MS)
                    }
                }
            }
        }

        step(0)
    }

    private fun finishExtendedText(blocks: List<TextBlock>) {
        restoreFloatingButtonAfterCapture()
        deliverText(SelectionAnalyzer.merge(blocks))
    }

    private fun scrollDistanceFor(target: Rect): Int {
        val overlap = (target.height() * SCROLL_OVERLAP_RATIO).toInt()
        return (target.height() - overlap).coerceAtLeast(1)
    }

    /**
     * يمرّر محتوى المربع بمقدار ارتفاعه ناقصا نسبة تداخل، عبر إيماءة سحب
     * حقيقية (dispatchGesture) لأنها الطريقة الوحيدة التي تتيح **التحكم في
     * مسافة التمرير**؛ أما ACTION_SCROLL_FORWARD فيمرّر بمقدار لا نعرفه،
     * فقد يضيع ما بين الحافتين ويستحيل دمج الصور. نستخدمه احتياطيا فقط إن
     * فشلت الإيماءة (بعض التطبيقات تمنعها).
     */
    private fun scrollSelection(target: Rect, onDone: (Boolean) -> Unit) {
        val distance = scrollDistanceFor(target)
        val centerX = target.centerX().toFloat()
        val startY = target.bottom - target.height() * 0.08f
        val endY = (startY - distance).coerceAtLeast(1f)

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_GESTURE_DURATION_MS))
            .build()

        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandlerPost { onDone(true) }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        mainHandlerPost { onDone(scrollWithAccessibilityAction(target)) }
                    }
                },
                mainHandler
            )
        }.getOrDefault(false)

        if (!dispatched) onDone(scrollWithAccessibilityAction(target))
    }

    /** احتياطي: تمرير عبر ACTION_SCROLL_FORWARD على أقرب عنصر قابل للتمرير. */
    private fun scrollWithAccessibilityAction(target: Rect): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val node = findScrollableNode(root, target) ?: return false
            val performed = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (node !== root) node.recycle()
            performed
        } finally {
            root.recycle()
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        // نفضّل الأعمق: عنصر التمرير الداخلي أدق من تمرير الشاشة كلها.
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child, target)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return if (node.isScrollable && Rect.intersects(bounds, target)) node else null
    }

    /**
     * الزر العائم يظهر فوق كل شيء، فلو بقي ظاهرا لدخل داخل اللقطة وأفسد
     * الصورة أو نتيجة التعرّف. نخفيه أثناء الالتقاط ونعيده بعده.
     */
    private fun hideFloatingButtonForCapture() {
        if (floatingButtonView == null) return
        floatingButtonHiddenForCapture = true
        removeFloatingButton()
    }

    /**
     * يخفي الزر العائم ثم ينفّذ [action] بعد لحظة قصيرة، حتى يكون النظام قد
     * أزال النافذة فعليا من الشاشة قبل التقاط اللقطة.
     */
    private fun hideFloatingButtonThen(action: () -> Unit) {
        val wasVisible = floatingButtonView != null
        hideFloatingButtonForCapture()
        if (wasVisible) {
            mainHandler.postDelayed(action, WINDOW_REMOVAL_DELAY_MS)
        } else {
            action()
        }
    }

    private fun restoreFloatingButtonAfterCapture() {
        if (!floatingButtonHiddenForCapture) return
        floatingButtonHiddenForCapture = false
        if (isFloatingButtonEnabled(this)) addFloatingButton()
    }

    // ---------------------------------------------------------------------
    // زر الصورة (لقطة للمربع، وممتدة عبر التمرير عند تفعيل "ممتد")
    // ---------------------------------------------------------------------

    private fun handleSaveImage(selectedRect: Rect, extended: Boolean) {
        removeSelectionOverlay()

        if (selectedRect.width() < DRAG_THRESHOLD_PX || selectedRect.height() < DRAG_THRESHOLD_PX) {
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, getString(R.string.image_not_supported_toast), Toast.LENGTH_LONG).show()
            return
        }

        if (extended) {
            Toast.makeText(this, getString(R.string.extended_working_toast), Toast.LENGTH_SHORT).show()
            hideFloatingButtonThen { startExtendedImageCapture(selectedRect) }
            return
        }

        hideFloatingButtonThen {
            captureCrop(selectedRect) { cropped ->
                restoreFloatingButtonAfterCapture()
                if (cropped == null) {
                    Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
                } else {
                    saveAndDeliverImage(cropped)
                }
            }
        }
    }

    /** يلتقط الشاشة ويقصّها على المربع المطلوب، ويعيد الصورة أو null. */
    private fun captureCrop(target: Rect, onResult: (Bitmap?) -> Unit) {
        captureScreenshot { fullBitmap ->
            if (fullBitmap == null) {
                onResult(null)
                return@captureScreenshot
            }
            val cropped = runCatching { cropBitmapToRect(fullBitmap, target) }.getOrNull()
            fullBitmap.recycle()
            onResult(cropped)
        }
    }

    /**
     * صورة ممتدة: يلتقط المربع، يمرّر، يلتقط مجددا، ثم يدمج اللقطات عموديا
     * بعد كشف منطقة التداخل بينها، فتخرج صورة واحدة طويلة للصفحة كاملة.
     */
    private fun startExtendedImageCapture(target: Rect) {
        val pages = mutableListOf<Bitmap>()

        fun finish() {
            restoreFloatingButtonAfterCapture()
            if (pages.isEmpty()) {
                Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
                return
            }

            val snapshot = pages.toList()
            val scheduled = runCatching {
                screenshotExecutor.execute {
                    val stitched = runCatching { stitchVertically(snapshot) }.getOrNull()
                    snapshot.forEach { it.recycle() }
                    val uri = stitched?.let { bitmap ->
                        runCatching { saveBitmapToGallery(bitmap) }.getOrNull().also { bitmap.recycle() }
                    }
                    mainHandlerPost { onImageSaved(uri) }
                }
            }
            if (scheduled.isFailure) {
                Log.e(TAG, "تعذّر جدولة دمج الصورة الممتدة", scheduled.exceptionOrNull())
                snapshot.forEach { it.recycle() }
                Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
            }
        }

        fun step(page: Int) {
            captureCrop(target) { cropped ->
                if (cropped != null) pages.add(cropped)

                if (cropped == null || page + 1 >= MAX_EXTENDED_PAGES) {
                    finish()
                    return@captureCrop
                }

                scrollSelection(target) { scrolled ->
                    if (!scrolled) {
                        finish()
                    } else {
                        mainHandler.postDelayed({ step(page + 1) }, SCROLL_SETTLE_DELAY_MS)
                    }
                }
            }
        }

        step(0)
    }

    private fun saveAndDeliverImage(bitmap: Bitmap) {
        val scheduled = runCatching {
            screenshotExecutor.execute {
                val uri = runCatching { saveBitmapToGallery(bitmap) }.getOrNull()
                bitmap.recycle()
                mainHandlerPost { onImageSaved(uri) }
            }
        }
        if (scheduled.isFailure) {
            Log.e(TAG, "تعذّر جدولة حفظ الصورة", scheduled.exceptionOrNull())
            bitmap.recycle()
            Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onImageSaved(uri: android.net.Uri?) {
        if (uri == null) {
            Toast.makeText(this, getString(R.string.image_save_failed_toast), Toast.LENGTH_SHORT).show()
            return
        }

        copyImageToClipboard(uri)
        showImageSavedNotification()
        Toast.makeText(this, getString(R.string.image_saved_toast), Toast.LENGTH_SHORT).show()
    }

    /**
     * يدمج لقطات متتابعة في صورة واحدة. لا نعتمد على مسافة التمرير المطلوبة
     * لأن التطبيقات تتفاوت (قصور ذاتي، التصاق بالعناصر)، بل نكتشف التداخل
     * الفعلي بمقارنة الصفوف الأخيرة من الصورة السابقة بصفوف الصورة التالية.
     */
    private fun stitchVertically(pages: List<Bitmap>): Bitmap {
        if (pages.size == 1) return pages[0].copy(Bitmap.Config.ARGB_8888, false)

        val width = pages.minOf { it.width }

        // التداخل بين كل صفحة وسابقتها، يُحسب مرة واحدة فقط لأنه أثقل جزء.
        val overlaps = IntArray(pages.size)
        var totalHeight = pages[0].height
        for (i in 1 until pages.size) {
            val overlap = detectOverlap(pages[i - 1], pages[i], width)
                .coerceIn(0, pages[i].height)
            overlaps[i] = overlap
            totalHeight += pages[i].height - overlap
        }

        if (totalHeight <= 0) return pages[0].copy(Bitmap.Config.ARGB_8888, false)

        val result = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        var y = 0
        for (i in pages.indices) {
            val page = pages[i]
            val srcTop = overlaps[i]
            val sliceHeight = page.height - srcTop
            if (sliceHeight <= 0) continue
            canvas.drawBitmap(
                page,
                Rect(0, srcTop, width, page.height),
                Rect(0, y, width, y + sliceHeight),
                null
            )
            y += sliceHeight
        }

        return result
    }

    /**
     * يعيد عدد الصفوف التي تتكرر في أعلى [next] وقد ظهرت أصلا في أسفل
     * [previous]. يقارن عيّنة من الأعمدة فقط حتى تبقى العملية سريعة.
     */
    private fun detectOverlap(previous: Bitmap, next: Bitmap, width: Int): Int {
        val maxOverlap = minOf(previous.height, next.height)
        if (maxOverlap <= 0 || width <= 0) return 0

        val sampleColumns = IntArray(SAMPLE_COLUMNS) { i ->
            ((i + 0.5f) / SAMPLE_COLUMNS * width).toInt().coerceIn(0, width - 1)
        }

        var bestOverlap = 0
        var bestScore = Int.MAX_VALUE

        var overlap = maxOverlap
        while (overlap > MIN_OVERLAP_PX) {
            var score = 0
            var rows = 0
            var row = 0
            while (row < overlap && rows < SAMPLE_ROWS) {
                val prevY = previous.height - overlap + row
                if (prevY < 0) break
                for (x in sampleColumns) {
                    val a = previous.getPixel(x, prevY)
                    val b = next.getPixel(x, row)
                    score += pixelDistance(a, b)
                }
                rows++
                row += (overlap / SAMPLE_ROWS).coerceAtLeast(1)
            }
            if (rows > 0) {
                val normalized = score / rows
                if (normalized < bestScore) {
                    bestScore = normalized
                    bestOverlap = overlap
                }
            }
            overlap -= OVERLAP_STEP_PX
        }

        return if (bestScore <= OVERLAP_MATCH_THRESHOLD) bestOverlap else 0
    }

    private fun pixelDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    /** يلتقط لقطة لكامل الشاشة الحالية عبر AccessibilityService.takeScreenshot (يتطلب أندرويد 11+). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshot(onResult: (Bitmap?) -> Unit) {
        val requested = runCatching { requestScreenshot(onResult) }
        if (requested.isFailure) {
            Log.e(TAG, "تعذّر طلب لقطة الشاشة", requested.exceptionOrNull())
            onResult(null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot(onResult: (Bitmap?) -> Unit) {
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
        mainHandler.post(action)
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
        removeSelectionOverlay()
        removeFloatingButton()
        if (screenOffReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        // بدون هذا الإغلاق يتراكم خيط تنفيذ جديد مع كل إعادة ربط للخدمة.
        runCatching { screenshotExecutor.shutdownNow() }
        instance = null
        return super.onUnbind(intent)
    }
}
