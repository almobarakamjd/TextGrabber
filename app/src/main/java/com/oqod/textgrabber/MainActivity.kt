package com.oqod.textgrabber

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.oqod.textgrabber.data.CopiedTextEntry
import com.oqod.textgrabber.data.CopiedTextStore
import com.oqod.textgrabber.service.MyAccessibilityService
import com.oqod.textgrabber.ui.theme.TextGrabberTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة الرئيسية للتطبيق (Jetpack Compose).
 *
 * تعرض:
 * 1. حالة خدمة إمكانية الوصول (مفعلة / غير مفعلة)
 * 2. زر لفتح إعدادات إمكانية الوصول في النظام مباشرة
 * 3. قائمة بآخر 10 نصوص تم نسخها تلقائيا
 * 4. نافذة شرح (Dialog) تظهر عند أول فتح للتطبيق فقط، توضح للمستخدم
 *    بشفافية كاملة ماذا تفعل صلاحية إمكانية الوصول ولماذا يحتاجها التطبيق.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TextGrabberTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TextGrabberApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextGrabberApp() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("text_grabber_prefs", android.content.Context.MODE_PRIVATE)
    }

    // إظهار نافذة الشرح مرة واحدة فقط عند أول تشغيل للتطبيق
    var showExplanationDialog by remember {
        mutableStateOf(!prefs.getBoolean("explanation_shown", false))
    }

    // إعادة التحقق من حالة تفعيل الخدمة كل مرة تعود فيها الشاشة إلى المقدمة
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val copiedTexts = CopiedTextStore.items

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(id = R.string.app_name)) })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(isEnabled = isAccessibilityEnabled)

            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(id = R.string.open_accessibility_settings))
            }

            Text(
                text = stringResource(id = R.string.recent_texts_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (copiedTexts.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.no_texts_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(copiedTexts) { entry ->
                        CopiedTextItem(entry = entry)
                    }
                }
            }
        }
    }

    if (showExplanationDialog) {
        AccessibilityExplanationDialog(
            onDismiss = {
                showExplanationDialog = false
                prefs.edit().putBoolean("explanation_shown", true).apply()
            }
        )
    }
}

@Composable
private fun StatusCard(isEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Color(0xFFE3F5E9) else Color(0xFFFCE8E6)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box {
                Icon(
                    imageVector = if (isEnabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (isEnabled) Color(0xFF1E8E3E) else Color(0xFFD93025)
                )
            }
            Text(
                text = stringResource(
                    id = if (isEnabled) R.string.service_enabled else R.string.service_disabled
                ),
                fontWeight = FontWeight.Bold,
                color = if (isEnabled) Color(0xFF1E8E3E) else Color(0xFFD93025)
            )
            Text(
                text = stringResource(
                    id = if (isEnabled) R.string.service_enabled_desc else R.string.service_disabled_desc
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CopiedTextItem(entry: CopiedTextEntry) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
            Text(
                text = timeFormatter.format(Date(entry.timestampMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccessibilityExplanationDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.explanation_title)) },
        text = { Text(stringResource(id = R.string.explanation_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.explanation_understood))
            }
        }
    )
}

/**
 * يتحقق من كون خدمة إمكانية الوصول الخاصة بنا مفعلة فعليا من إعدادات النظام.
 * هذه هي الطريقة الرسمية الموصى بها من أندرويد للتحقق (بدلا من الاعتماد فقط
 * على متغير داخلي في الخدمة، الذي قد لا يعكس الحالة الحقيقية دائما).
 */
private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expectedComponentName = context.packageName + "/" + MyAccessibilityService::class.java.name

    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonChar = ':'
    val colonSplitter = TextUtils.SimpleStringSplitter(colonChar)
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedComponentName, ignoreCase = true)) {
            return true
        }
    }
    return false
}
