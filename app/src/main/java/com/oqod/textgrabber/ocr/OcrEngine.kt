package com.oqod.textgrabber.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
     * صغير يكون منخفض الدقة نسبيا، وتكبيره قبل التعرّف يرفع الدقة.
     */
    private const val MIN_OCR_WIDTH_PX = 800

    /**
     * كان 3 أضعاف، وخُفّض إلى ضعفين: محرك LSTM يوحّد ارتفاع السطر داخليا،
     * فالتكبير الزائد لا يرفع الدقة بل يضاعف زمن المعالجة فقط، ويضخّم
     * حواف الزخارف فتُقرأ حروفا.
     */
    private const val MAX_UPSCALE_FACTOR = 2f

    /**
     * هامش حول الصورة نسبة إلى أصغر بُعديها. الحروف والأشكال الملاصقة
     * لحافة الصورة سبب شائع لظهور رموز وهمية مثل "|" في الناتج.
     */
    private const val PADDING_RATIO = 0.08f

    /**
     * دقة وهمية نبلغ بها Tesseract. صور الشاشة لا تحمل معلومات DPI، فيفترض
     * المحرك 70 ويحذّر، وهي قيمة تُفسد تقديره لأحجام الحروف.
     */
    private const val ASSUMED_DPI = "300"

    /** عدد العيّنات على كل حافة عند تقدير لون الخلفية. */
    private const val EDGE_SAMPLES = 40

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
            tess.setVariable("user_defined_dpi", ASSUMED_DPI)
            tess.setImage(prepared)

            // getUTF8Text هو ما يشغّل التعرّف فعليا؛ يجب استدعاؤه قبل المكرِّر.
            val raw = tess.getUTF8Text().orEmpty()
            if (raw.isBlank()) return null

            val cleaned = runCatching { OcrTextCleaner.clean(collectWords(tess)) }
                .getOrElse {
                    Log.w(TAG, "تعذّرت قراءة درجات الثقة، يُنظَّف النص الخام بدلا منها", it)
                    OcrTextCleaner.cleanRaw(raw)
                }
            cleaned.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء التعرّف على النص", t)
            null
        } finally {
            runCatching { tess.recycle() }
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    /**
     * يقرأ كل كلمة مع درجة ثقة المحرك بها ورقم سطرها. درجة الثقة هي ما
     * يسمح لـ[OcrTextCleaner] باستبعاد التشويش الناتج عن الزخارف والحواف.
     */
    private fun collectWords(tess: TessBaseAPI): List<OcrWord> {
        val iterator = tess.resultIterator ?: return emptyList()
        val words = mutableListOf<OcrWord>()
        var line = -1
        try {
            iterator.begin()
            do {
                if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) line++
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (!text.isNullOrBlank()) {
                    val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    words.add(OcrWord(text, confidence, line.coerceAtLeast(0)))
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            iterator.delete()
        }
        return words
    }

    /**
     * تحسين الصورة قبل التعرّف: تكبير حتى ضعفين لبلوغ عرض مناسب، ثم تحويلها
     * إلى تدرّج رمادي بتباين مرفوع، مع هامش حولها **بلون خلفيتها نفسها**.
     *
     * الهامش بلون الخلفية لا بالأبيض عمدا: في صورة نصها فاتح على خلفية داكنة
     * (مثل ملصق ذهبي على بنفسجي) يصنع الهامش الأبيض حافة حادة جديدة تُقرأ
     * خطا عموديا "|"، أي أنه يضيف التشويش الذي جاء ليزيله.
     */
    private fun preprocess(source: Bitmap): Bitmap {
        val factor = (MIN_OCR_WIDTH_PX.toFloat() / source.width)
            .coerceIn(1f, MAX_UPSCALE_FACTOR)

        val scaledWidth = (source.width * factor).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * factor).toInt().coerceAtLeast(1)
        val padding = (minOf(scaledWidth, scaledHeight) * PADDING_RATIO).toInt()

        val targetWidth = scaledWidth + padding * 2
        val targetHeight = scaledHeight + padding * 2

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

        val filter = ColorMatrixColorFilter(grayscale)

        // نملأ الخلفية بمتوسط لون حواف الصورة بعد تمريره على نفس المرشّح،
        // فيتصل الهامش بالخلفية بلا حافة مرئية.
        canvas.drawRect(
            0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(),
            Paint().apply {
                color = averageEdgeColor(source)
                colorFilter = filter
            }
        )

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = filter
        }
        canvas.drawBitmap(
            source,
            null,
            Rect(padding, padding, padding + scaledWidth, padding + scaledHeight),
            paint
        )
        return output
    }

    /** متوسط لون البكسلات على الحواف الأربع للصورة (بعيّنة لا بكل بكسل). */
    private fun averageEdgeColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return Color.WHITE

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0

        fun sample(x: Int, y: Int) {
            val pixel = bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
            count++
        }

        val stepX = (width / EDGE_SAMPLES).coerceAtLeast(1)
        val stepY = (height / EDGE_SAMPLES).coerceAtLeast(1)
        for (x in 0 until width step stepX) {
            sample(x, 0)
            sample(x, height - 1)
        }
        for (y in 0 until height step stepY) {
            sample(0, y)
            sample(width - 1, y)
        }

        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
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
