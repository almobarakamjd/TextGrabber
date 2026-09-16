package com.oqod.textgrabber.capture

import com.oqod.textgrabber.ocr.OcrTextCleaner
import com.oqod.textgrabber.ocr.OcrWord

/**
 * مستطيل بإحداثيات الشاشة. نستخدمه بدل `android.graphics.Rect` حتى يبقى
 * هذا الملف منطقا خالصا يُختبر باختبارات وحدة عادية على الحاسوب.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    fun inflate(by: Int) = Box(left - by, top - by, right + by, bottom + by)

    fun union(other: Box) = Box(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom)
    )

    fun verticalOverlap(other: Box): Int =
        (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
}

/** نص مع موضعه على الشاشة، من الواجهة أو من OCR. */
data class PlacedText(val text: String, val box: Box)

/**
 * كلمة قرأها OCR بعد تحويل موضعها إلى إحداثيات الشاشة. [region] رقم الصورة
 * التي جاءت منها، و[line] رقم سطرها داخل تلك الصورة.
 */
data class ScreenWord(val word: OcrWord, val region: Int, val box: Box)

/**
 * تركيب "النسخ المركب": يجمع نص الواجهة (موثوق تماما) مع نص الصور (من
 * OCR) في نص واحد مرتب كما تُقرأ الصفحة بالعين.
 *
 * التصميم نشأ من ملاحظة أبو فيصل (2026-09-16): OCR الصرف ممتاز، لكنه في
 * المركب يضعف. السبب أن الطريقة السابقة كانت تقسم المربع إلى أشرطة أفقية
 * بعرضه كاملا بدل فصل الصور كعناصر، فتتقطع الصورة وتدخل معها الأزرار
 * المجاورة. الآن: كل صورة تُقرأ وحدها بسياقها الكامل، ويُحذف من نتيجتها
 * ما يقع فوق نص معروف من الواجهة، ثم يُركَّب الكل حسب موضعه.
 */
object MixedContentComposer {

    /**
     * هامش تسامح حول حدود نص الواجهة عند حذف كلمات OCR المكررة. حدود
     * الكلمة التي يقدّرها OCR لا تنطبق تماما على حدود العنصر في الواجهة.
     */
    const val UI_OVERLAP_MARGIN_PX = 6

    /**
     * عنصران في "نفس الصف" إن تداخلا عموديا بنصف ارتفاع الأقصر منهما على
     * الأقل. عندها يُرتَّبان أفقيا لا عموديا.
     */
    private const val SAME_ROW_OVERLAP_RATIO = 0.5f

    fun compose(uiTexts: List<PlacedText>, ocrWords: List<ScreenWord>): List<PlacedText> {
        val uiBoxes = uiTexts.map { it.box.inflate(UI_OVERLAP_MARGIN_PX) }

        // كلمة OCR يقع مركزها داخل نص معروف من الواجهة هي نسخة مكررة منه
        // (وقد تكون مشوهة)، فنحذفها ونعتمد نص الواجهة.
        val imageOnlyWords = ocrWords.filter { screenWord ->
            uiBoxes.none { it.contains(screenWord.box.centerX, screenWord.box.centerY) }
        }

        val ocrLines = imageOnlyWords
            .groupBy { it.region to it.word.line }
            .values
            .mapNotNull { lineWords ->
                val text = OcrTextCleaner.cleanLineWords(lineWords.map { it.word })
                if (text.isEmpty()) return@mapNotNull null
                val box = lineWords.map { it.box }.reduce(Box::union)
                PlacedText(text, box)
            }

        val all = uiTexts.filter { it.text.isNotBlank() } + ocrLines
        return orderByReading(all, isRightToLeft(all))
    }

    /** يحوّل القائمة المرتبة إلى نص نهائي، مع حذف الأسطر المكررة حرفيا. */
    fun join(items: List<PlacedText>): String {
        val seen = LinkedHashSet<String>()
        return items
            .map { it.text.trim() }
            .filter { it.isNotEmpty() && seen.add(it) }
            .joinToString(separator = "\n")
    }

    /**
     * ترتيب القراءة: صفوفا من الأعلى إلى الأسفل، وداخل الصف الواحد من
     * اليمين إلى اليسار في الصفحات العربية، ومن اليسار إلى اليمين غيرها.
     */
    fun orderByReading(items: List<PlacedText>, rightToLeft: Boolean): List<PlacedText> {
        val rows = mutableListOf<MutableList<PlacedText>>()

        for (item in items.sortedBy { it.box.top }) {
            val row = rows.lastOrNull()
            if (row != null && row.any { isSameRow(it.box, item.box) }) {
                row.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }

        return rows.flatMap { row ->
            if (rightToLeft) row.sortedByDescending { it.box.right } else row.sortedBy { it.box.left }
        }
    }

    private fun isSameRow(a: Box, b: Box): Boolean {
        val smaller = minOf(a.height, b.height)
        if (smaller <= 0) return false
        return a.verticalOverlap(b) >= smaller * SAME_ROW_OVERLAP_RATIO
    }

    /** الصفحة عربية الاتجاه إن كانت أغلب حروفها عربية. */
    fun isRightToLeft(items: List<PlacedText>): Boolean {
        var arabic = 0
        var latin = 0
        for (item in items) {
            for (c in item.text) {
                when {
                    c in '؀'..'ۿ' -> arabic++
                    c in 'A'..'Z' || c in 'a'..'z' -> latin++
                }
            }
        }
        return arabic >= latin
    }
}
