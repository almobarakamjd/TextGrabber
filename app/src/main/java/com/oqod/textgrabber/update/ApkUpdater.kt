package com.oqod.textgrabber.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

private const val UPDATE_APK_FILE_NAME = "TextGrabber-update.apk"

/** يتحقق إن كان النظام يسمح لهذا التطبيق بتثبيت حزم من مصادر غير معروفة. */
fun canInstallFromUnknownSources(context: Context): Boolean {
    return context.packageManager.canRequestPackageInstalls()
}

/** يفتح شاشة النظام التي تطلب من المستخدم السماح لهذا التطبيق بتثبيت تحديثات. */
fun requestInstallPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:" + context.packageName)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * ينزّل ملف APK للتحديث عبر DownloadManager الخاص بالنظام (تنزيل مباشر
 * داخل التطبيق، بدون فتح أي متصفح)، ثم يفتح شاشة تثبيته تلقائيا بمجرد
 * اكتمال التنزيل.
 */
fun downloadAndInstallUpdate(context: Context, apkUrl: String) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
        setTitle(context.getString(com.oqod.textgrabber.R.string.update_download_title))
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_FILE_NAME)
        setMimeType("application/vnd.android.package-archive")
    }

    val downloadId = downloadManager.enqueue(request)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (completedId == downloadId) {
                val uri = downloadManager.getUriForDownloadedFile(downloadId)
                if (uri != null) {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    runCatching { receiverContext.startActivity(installIntent) }
                }
                runCatching { receiverContext.unregisterReceiver(this) }
            }
        }
    }

    ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_EXPORTED
    )
}
