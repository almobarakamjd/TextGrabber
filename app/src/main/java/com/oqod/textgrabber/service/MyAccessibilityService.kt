package com.oqod.textgrabber.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.oqod.textgrabber.MainActivity
import com.oqod.textgrabber.R
import com.oqod.textgrabber.data.CopiedTextStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * خدمة إمكانية الوصول (Accessibility Service) الأساسية في التطبيق.
 *
 * تعمل هذه الخدمة على قراءة شجرة واجهة المستخدم (AccessibilityNodeInfo) لأي تطبيق
 * آخر مفتوح على الشاشة، دون أي التقاط للشاشة أو استخدام OCR، وذلك عبر أحداث
 * إمكانية الوصول القياسية التي يوفرها نظام أندرويد.
 *
 * عند رصد نص جديد (تغيّر محتوى، أو تركيز على عنصر نصي، أو تغيّر تحديد نص)
 * يتم نسخه تلقائيًا إلى الحافظة (Clipboard) مع تفادي تكرار نسخ نفس النص
 * بشكل متتالي، وإظهار إشعار بسيط يوضح مقتطفًا من النص المنسوخ.
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val CHANNEL_ID = "text_grabber_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_SNIPPET_LENGTH = 60

        // حالة تفعيل الخدمة، تُستخدم من MainActivity لعرض الحالة الحالية للمستخدم.
        // ملاحظة: هذه ليست الطريقة الرسمية للتحقق من التفعيل (نستخدم
        // AccessibilityManager في الواجهة)، لكنها مفيدة كإشارة سريعة إضافية.
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    // آخر نص تم نسخه، لتفادي تكرار النسخ لنفس النص عدة مرات متتالية
    private var lastCopiedText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        createNotificationChannelIfNeeded()
    }

    /**
     * يُستدعى في كل مرة يحدث فيها أحد الأحداث التي اشتركنا بها في
     * accessibility_service_config.xml (تغيّر محتوى، تركيز، تغيّر تحديد نص).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handlePossibleTextChange(event)
            }
        }
    }

    /**
     * يحاول استخراج أفضل نص متاح من الحدث نفسه، وإن لم يوجد،
     * يحاول قراءته من العقدة المصدر (source node) في شجرة الواجهة.
     */
    private fun handlePossibleTextChange(event: AccessibilityEvent) {
        // أولاً: النص المرفق مباشرة مع الحدث (غالبًا أسرع وأدق)
        val textFromEvent = event.text?.joinToString(separator = " ")?.trim()

        // ثانياً: النص من العقدة المصدر للحدث عبر شجرة AccessibilityNodeInfo
        val sourceNode: AccessibilityNodeInfo? = event.source
        val textFromNode = try {
            sourceNode?.text?.toString()?.trim()
                ?: sourceNode?.contentDescription?.toString()?.trim()
        } finally {
            // يجب دائمًا تحرير العقدة بعد الاستخدام لتفادي تسريب الذاكرة
            sourceNode?.recycle()
        }

        val candidate = when {
            !textFromNode.isNullOrBlank() -> textFromNode
            !textFromEvent.isNullOrBlank() -> textFromEvent
            else -> null
        }

        if (candidate.isNullOrBlank()) return

        // تفادي نسخ نفس النص المكرر بشكل متتالي
        if (candidate == lastCopiedText) return

        lastCopiedText = candidate
        copyToClipboard(candidate)
        CopiedTextStore.addText(candidate)
        showCopyNotification(candidate)
    }

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

    /** يُستدعى عندما يقاطع النظام الخدمة (مثلاً عند طلب إيقاف مؤقت) */
    override fun onInterrupt() {
        // لا حاجة لتنظيف موارد خاصة هنا حالياً، لكن الدالة إلزامية التطبيق
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        return super.onUnbind(intent)
    }
}
