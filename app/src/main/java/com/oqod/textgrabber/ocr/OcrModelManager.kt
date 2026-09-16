package com.oqod.textgrabber.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * يدير نموذج العربية **الدقيق** (`tessdata_best`) الاختياري.
 *
 * التطبيق يأتي بالنموذج السريع مضمّنا (1.4 ميجابايت) فيعمل فورا بلا إنترنت.
 * أول تجربة فعلية أثبتت ضعفه في الحروف نفسها ("ورحمه" بدل "ورحمة")، بينما
 * النموذج الدقيق 12.6 ميجابايت كان سيرفع حجم APK من 19 إلى 31 ميجابايت
 * لكل المستخدمين. الحل: ينزّله من يحتاجه فقط، مرة واحدة، من داخل التطبيق.
 *
 * **سلامة الملف**: الرابط مثبّت على نسخة محددة من مستودع Tesseract الرسمي
 * (لا على فرع `main` المتغيّر)، والملف لا يُعتمد إلا بعد مطابقة حجمه
 * وبصمته SHA-256 مع القيم المسجلة هنا. التنزيل يُكتب في ملف مؤقت ولا
 * يُنقل إلى مكانه النهائي إلا بعد نجاح التحقق، فلا يبقى نموذج نصف مكتمل
 * يُحمّله المحرّك إن انقطع الاتصال.
 */
object OcrModelManager {

    private const val TAG = "TextGrabberOcrModel"

    /** نسخة مثبّتة من tessdata_best؛ تحديثها يستلزم تحديث الحجم والبصمة معا. */
    private const val BEST_ARABIC_URL =
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/" +
            "9ddc24e750eec0994223a9edc3fcb434a2244f3b/ara.traineddata"

    const val BEST_ARABIC_SIZE_BYTES = 12_603_724L

    private const val BEST_ARABIC_SHA256 =
        "ab9d157d8e38ca00e7e39c7d5363a5239e053f5b0dbdb3167dde9d8124335896"

    /** المجلد الأب الذي يمرَّر إلى Tesseract (يحوي بداخله `tessdata`). */
    private const val BEST_DIR = "ocr_best"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    sealed interface State {
        /** النموذج الدقيق غير منزَّل؛ يُستخدم السريع المضمّن. */
        data object NotInstalled : State

        /** [progress] من 0 إلى 1. */
        data class Downloading(val progress: Float) : State

        /** النموذج الدقيق منزَّل ومتحقَّق منه، ويستخدمه المحرّك تلقائيا. */
        data object Installed : State

        data class Failed(val reason: Reason) : State
    }

    enum class Reason { NETWORK, VERIFICATION, STORAGE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    /** يزامن الحالة مع ما على القرص فعليا (عند فتح الشاشة مثلا). */
    fun refresh(context: Context) {
        if (_state.value is State.Downloading) return
        _state.value = if (isInstalled(context)) State.Installed else State.NotInstalled
    }

    fun isInstalled(context: Context): Boolean {
        val model = bestArabicFile(context)
        // الحجم وحده يكفي هنا للسرعة: البصمة الكاملة تُفحص مرة واحدة عند
        // التنزيل، والملف لا يصل إلى مكانه النهائي إلا بعد نجاحها.
        return model.isFile && model.length() == BEST_ARABIC_SIZE_BYTES &&
            File(bestTessdataDir(context), "eng.traineddata").isFile
    }

    /** المسار الأب الذي يُمرَّر إلى `TessBaseAPI.init` عند استخدام النموذج الدقيق. */
    fun bestDataPath(context: Context): File = File(context.filesDir, BEST_DIR)

    private fun bestTessdataDir(context: Context) = File(bestDataPath(context), "tessdata")

    private fun bestArabicFile(context: Context) = File(bestTessdataDir(context), "ara.traineddata")

    fun startDownload(context: Context) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext

        _state.value = State.Downloading(0f)
        downloadJob = scope.launch {
            _state.value = runCatching { download(appContext) }
                .fold(
                    onSuccess = { State.Installed },
                    onFailure = { error ->
                        Log.e(TAG, "فشل تنزيل النموذج الدقيق", error)
                        State.Failed(
                            when (error) {
                                is ModelVerificationException -> Reason.VERIFICATION
                                is IOException -> Reason.NETWORK
                                else -> Reason.STORAGE
                            }
                        )
                    }
                )
        }
    }

    /** يحذف النموذج الدقيق فيعود المحرّك إلى السريع المضمّن. */
    fun delete(context: Context) {
        if (downloadJob?.isActive == true) return
        bestDataPath(context).deleteRecursively()
        _state.value = State.NotInstalled
    }

    private fun download(context: Context) {
        val tessdata = bestTessdataDir(context)
        if (!tessdata.isDirectory && !tessdata.mkdirs()) {
            throw IllegalStateException("تعذّر إنشاء ${tessdata.absolutePath}")
        }

        val partial = File(tessdata, "ara.traineddata.part")
        partial.delete()

        val connection = (URL(BEST_ARABIC_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("استجابة غير متوقعة: ${connection.responseCode}")
            }

            val sha256 = connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    ModelVerifier.copyAndHash(input, output) { copied ->
                        _state.value = State.Downloading(
                            (copied.toFloat() / BEST_ARABIC_SIZE_BYTES).coerceIn(0f, 1f)
                        )
                    }
                }
            }

            ModelVerifier.verify(
                actualSize = partial.length(),
                actualSha256 = sha256,
                expectedSize = BEST_ARABIC_SIZE_BYTES,
                expectedSha256 = BEST_ARABIC_SHA256
            )
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        // Tesseract يحتاج كل لغات "ara+eng" في مجلد tessdata واحد، فننسخ
        // نموذج الإنجليزية المضمّن بجانب العربية الدقيقة.
        val english = File(tessdata, "eng.traineddata")
        context.assets.open("tessdata/eng.traineddata").use { input ->
            english.outputStream().use { output -> input.copyTo(output) }
        }

        val finalFile = bestArabicFile(context)
        finalFile.delete()
        if (!partial.renameTo(finalFile)) {
            partial.delete()
            throw IllegalStateException("تعذّر نقل النموذج إلى مكانه النهائي")
        }
    }
}
