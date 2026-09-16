package com.oqod.textgrabber.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * محرّك التعرّف الضوئي على النص (OCR) العامل **بالكامل على الجهاز**.
 *
 * يستخدم Tesseract عبر مكتبة Tesseract4Android، ولا يتصل بأي خادم إطلاقا:
 * الصورة لا تغادر الجهاز في أي مرحلة. اختير Tesseract لأن ML Kit من جوجل
 * لا يدعم النص العربي أصلا (يدعم اللاتيني والصيني والياباني والكوري
 * والديفاناجاري فقط).
 *
 * ملفات اللغة (`ara` و`eng`) مضمّنة في `assets/tessdata` بنسختها السريعة
 * (tessdata_fast)، وتُنسخ عند أول استخدام إلى مجلد التطبيق الخاص
 * (`filesDir/tessdata`) لأن Tesseract يحتاج مسارا حقيقيا على القرص ولا
 * يستطيع القراءة من داخل ملف APK مباشرة.
 */
object OcrEngine {

    private const val TAG = "TextGrabberOcr"

    /** "ara+eng": يتعرّف على العربية والإنجليزية معا في نفس الصورة. */
    private const val LANGUAGES = "ara+eng"

    private const val TESSDATA_DIR = "tessdata"

    private val LANGUAGE_FILES = listOf("ara.traineddata", "eng.traineddata")

    /**
     * أصغر عرض (بالبكسل) نمرّره إلى Tesseract. نص الشاشة المقصوص من مربع
     * صغير يكون منخفض الدقة نسبيا، وتكبيره قبل التعرّف يرفع الدقة بوضوح.
     */
    private const val MIN_OCR_WIDTH_PX = 1000

    private const val MAX_UPSCALE_FACTOR = 3f

    @Volatile
    private var preparedDataPath: String? = null

    /**
     * يشغّل التعرّف على النص في الصورة المعطاة ويعيد النص الناتج، أو `null`
     * عند الفشل. **يجب استدعاؤها من خيط خلفي** — العملية قد تستغرق ثوانيَ.
     */
    fun recognize(context: Context, bitmap: Bitmap): String? {
        val dataPath = runCatching { prepareDataPath(context) }.getOrElse {
            Log.e(TAG, "تعذّر تجهيز ملفات لغة Tesseract", it)
            return null
        }

        val prepared = runCatching { preprocess(bitmap) }.getOrElse {
            Log.e(TAG, "تعذّرت معالجة الصورة قبل التعرّف", it)
            return null
        }

        val tess = TessBaseAPI()
        return try {
            if (!tess.init(dataPath, LANGUAGES)) {
                Log.e(TAG, "فشل تهيئة Tesseract بالمسار $dataPath")
                return null
            }
            // الصورة مقصوصة أصلا على مربع اختاره المستخدم، فهي كتلة نص واحدة
            // وليست صفحة كاملة؛ هذا الوضع أدق لها من التحليل التلقائي للصفحة.
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            tess.setImage(prepared)
            tess.getUTF8Text()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء التعرّف على النص", t)
            null
        } finally {
            runCatching { tess.recycle() }
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    /**
     * تحسين الصورة قبل التعرّف: تكبير حتى ثلاثة أضعاف لبلوغ عرض مناسب، ثم
     * تحويلها إلى تدرّج رمادي بتباين مرفوع. نص الشاشة نظيف (لا ميلان ولا
     * ظلال) فهذا القدر من المعالجة يكفي ويعطي أفضل نتيجة مع Tesseract.
     */
    private fun preprocess(source: Bitmap): Bitmap {
        val factor = (MIN_OCR_WIDTH_PX.toFloat() / source.width)
            .coerceIn(1f, MAX_UPSCALE_FACTOR)

        val targetWidth = (source.width * factor).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * factor).toInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        // رفع التباين: يفصل الحروف عن الخلفية فيقلّ خلط الحروف المتشابهة.
        val contrast = 1.6f
        val translate = (-.5f * contrast + .5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(contrastMatrix)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayscale)
        }
        canvas.drawBitmap(source, null, Rect(0, 0, targetWidth, targetHeight), paint)
        return output
    }

    /**
     * ينسخ ملفات اللغة من `assets` إلى مجلد التطبيق الخاص عند أول استخدام،
     * ويعيد المسار الأب الذي يتوقعه Tesseract (المجلد الذي يحوي `tessdata`).
     */
    private fun prepareDataPath(context: Context): String {
        preparedDataPath?.let { return it }

        synchronized(this) {
            preparedDataPath?.let { return it }

            val baseDir = context.filesDir
            val tessDir = File(baseDir, TESSDATA_DIR)
            if (!tessDir.exists() && !tessDir.mkdirs()) {
                throw IllegalStateException("تعذّر إنشاء المجلد ${tessDir.absolutePath}")
            }

            for (fileName in LANGUAGE_FILES) {
                val target = File(tessDir, fileName)
                val assetSize = context.assets.openFd("$TESSDATA_DIR/$fileName").use { it.length }
                // نعيد النسخ إن كان الملف ناقصا أو من إصدار سابق مختلف الحجم.
                if (target.exists() && target.length() == assetSize) continue

                context.assets.open("$TESSDATA_DIR/$fileName").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val path = baseDir.absolutePath
            preparedDataPath = path
            return path
        }
    }
}
