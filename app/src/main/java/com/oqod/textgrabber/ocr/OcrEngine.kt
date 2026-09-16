package com.oqod.textgrabber.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
            tess.getUTF8Text()?.let(::cleanUp)?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء التعرّف على النص", t)
            null
        } finally {
            runCatching { tess.recycle() }
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    /**
     * تنظيف بسيط وآمن لنص Tesseract الخام: يشذّب كل سطر ويحذف الأسطر
     * الفارغة المتكررة الناتجة عن فراغات في الصورة، دون لمس محتوى الأسطر
     * نفسها حتى لا نخاطر بحذف نص حقيقي.
     */
    private fun cleanUp(raw: String): String =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")

    /**
     * تحسين الصورة قبل التعرّف: تكبير حتى ثلاثة أضعاف لبلوغ عرض مناسب، ثم
     * تحويلها إلى أبيض وأسود صرف بعتبة Otsu التلقائية (بدل تدرّج رمادي
     * بتباين ثابت). خلفيات الصور الزخرفية والملصقات تجمع ألوانا متعددة
     * (نص ذهبي فوق دائرة بنفسجية مثلا) قد يتقارب سطوعها فيربك Tesseract حتى
     * مع رفع التباين الخطي؛ التحويل الثنائي بعتبة محسوبة من توزيع الصورة
     * نفسها يفصل الحروف عن أي خلفية ملوّنة بثبات أكبر.
     */
    private fun preprocess(source: Bitmap): Bitmap {
        val factor = (MIN_OCR_WIDTH_PX.toFloat() / source.width)
            .coerceIn(1f, MAX_UPSCALE_FACTOR)

        val targetWidth = (source.width * factor).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * factor).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, null, Rect(0, 0, targetWidth, targetHeight), paint)

        return binarize(scaled).also { scaled.recycle() }
    }

    /**
     * يحوّل الصورة إلى أبيض وأسود صرف باستخدام عتبة Otsu المحسوبة من
     * توزيع درجات السطوع في الصورة نفسها، ثم يعكس الألوان إن كانت الكتلة
     * الأكبر سوداء (نص فاتح فوق خلفية داكنة) حتى يخرج النص أسود دوما فوق
     * خلفية بيضاء، وهو ما يتوقعه Tesseract.
     */
    private fun binarize(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = IntArray(pixels.size)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            luminance[i] = l
            histogram[l]++
        }

        val threshold = otsuThreshold(histogram, pixels.size)

        var blackCount = 0
        for (i in pixels.indices) {
            if (luminance[i] <= threshold) blackCount++
        }
        // النص يشغل عادة جزءا أصغر من الصورة؛ إن كانت الكتلة السوداء هي
        // الأكبر فالخلفية داكنة والنص فاتح، فنعكس حتى يبقى النص أسود دوما.
        val invert = blackCount > pixels.size / 2

        for (i in pixels.indices) {
            val isInk = luminance[i] <= threshold
            val black = if (invert) !isInk else isInk
            pixels[i] = if (black) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /** طريقة Otsu لإيجاد عتبة الفصل المثلى بين درجتي سطوع في الصورة. */
    private fun otsuThreshold(histogram: IntArray, totalPixels: Int): Int {
        if (totalPixels == 0) return 128

        var sumAll = 0.0
        for (t in 0..255) sumAll += t * histogram[t]

        var sumBackground = 0.0
        var weightBackground = 0
        var maxVariance = -1.0
        var threshold = 128

        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = totalPixels - weightBackground
            if (weightForeground == 0) break

            sumBackground += t * histogram[t]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground

            val variance = weightBackground.toDouble() * weightForeground.toDouble() *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }
        return threshold
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
