package com.oqod.textgrabber.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
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
        private const val NOTIFICATION_ID = 1001
        private const val MAX_SNIPPET_LENGTH = 60

        // حد أدنى لحركة الإصبع (بالبكسل) لاعتبارها سحبا لمربع تحديد
        // وليست مجرد ضغطة عرضية أثناء تحريك الزر العائم.
        private const val DRAG_THRESHOLD_PX = 12
    }

    private lateinit var windowManager: WindowManager

    // الزر العائم الذي يفتح وضع التحديد
    private var floatingButtonView: TextView? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null

    // طبقة التحديد الشفافة التي تظهر فوق كامل الشاشة أثناء رسم المربع
    private var selectionOverlayView: SelectionOverlayView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannelIfNeeded()
        addFloatingButton()
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
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) {
                        moved = true
                    }
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(button, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        startSelectionMode()
                    }
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
        floatingButtonView?.let { runCatching { windowManager.removeView(it) } }
        floatingButtonView = null
        floatingButtonParams = null
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
            onSelectionComplete = { rect -> finishSelectionMode(rect) },
            onSelectionCancelled = { finishSelectionMode(null) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )

        runCatching { windowManager.addView(overlay, params) }
        selectionOverlayView = overlay
    }

    private fun finishSelectionMode(selectedRect: Rect?) {
        selectionOverlayView?.let { runCatching { windowManager.removeView(it) } }
        selectionOverlayView = null

        if (selectedRect == null || selectedRect.width() < DRAG_THRESHOLD_PX || selectedRect.height() < DRAG_THRESHOLD_PX) {
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
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                results.add(text)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextInRect(child, target, results)
            child.recycle()
        }
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

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(snippet)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snippet))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
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
        return super.onUnbind(intent)
    }
}
