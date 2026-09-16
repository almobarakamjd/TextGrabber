package com.oqod.textgrabber.capture

import android.graphics.Rect

/**
 * نوع النسخ المناسب لمحتوى المربع الذي حدده المستخدم. يُحسب لحظيا بعد رسم
 * المربع، فيتغيّر عنوان زر النسخ تلقائيا بدل أن يضطر المستخدم للاختيار:
 *
 * - [TEXT]: المربع كله نص تقرؤه واجهة إمكانية الوصول → "نسخ" (فوري ودقيق 100%).
 * - [IMAGE]: لا نص في الواجهة داخل المربع (صورة أو لقطة أو PDF) → "نسخ OCR".
 * - [MIXED]: نص وصور تحوي نصا معا → "نسخ مركب" (يدمج الاثنين بترتيب القراءة).
 */
enum class CopyMode { TEXT, IMAGE, MIXED }

/**
 * كتلة نص واحدة مع موضعها على الشاشة. الموضع هو ما يسمح بترتيب النتيجة
 * النهائية من أعلى الصفحة إلى أسفلها في وضع "النسخ المركب"، بحيث يخرج
 * النص المستخرج من الصور في مكانه الصحيح بين فقرات النص العادي.
 */
data class TextBlock(val text: String, val top: Int, val bounds: Rect)

/**
 * نتيجة تحليل محتوى المربع.
 *
 * [imageBands] هي الأشرطة الأفقية داخل المربع التي **لا يغطيها أي نص** من
 * شجرة إمكانية الوصول. اعتمدنا هذا الأسلوب بدل التعرّف على أصناف العروض
 * (ImageView وغيرها) لأنه يعمل مع Compose وWebView والتطبيقات التي ترسم
 * محتواها بنفسها، وهي الحالات التي تفشل فيها مطابقة أسماء الأصناف. كل شريط
 * من هذه الأشرطة مرشّح لتمريره على محرّك OCR.
 */
data class SelectionAnalysis(
    val textBlocks: List<TextBlock>,
    val imageBands: List<Rect>
) {
    val mode: CopyMode
        get() = when {
            textBlocks.isNotEmpty() && imageBands.isNotEmpty() -> CopyMode.MIXED
            textBlocks.isNotEmpty() -> CopyMode.TEXT
            else -> CopyMode.IMAGE
        }
}

object SelectionAnalyzer {

    /**
     * أقل ارتفاع (بالبكسل) لشريط فارغ من النص حتى يُعتبر محتوى مصوّرا يستحق
     * التعرّف الضوئي. أقل من ذلك يكون مجرد تباعد بين أسطر النص.
     */
    private const val MIN_BAND_HEIGHT_PX = 48

    /**
     * يحسب الأشرطة غير المغطاة بنص داخل [target]، انطلاقا من حدود كتل النص
     * المعروفة. يدمج الحدود المتداخلة أولا ثم يأخذ المتمم.
     */
    fun findImageBands(target: Rect, textBounds: List<Rect>): List<Rect> {
        if (target.height() <= 0) return emptyList()

        val covered = textBounds
            .mapNotNull { bounds ->
                val top = bounds.top.coerceIn(target.top, target.bottom)
                val bottom = bounds.bottom.coerceIn(target.top, target.bottom)
                if (bottom > top) top to bottom else null
            }
            .sortedBy { it.first }

        val merged = mutableListOf<Pair<Int, Int>>()
        for ((top, bottom) in covered) {
            val last = merged.lastOrNull()
            if (last != null && top <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, bottom)
            } else {
                merged.add(top to bottom)
            }
        }

        val bands = mutableListOf<Rect>()
        var cursor = target.top
        for ((top, bottom) in merged) {
            if (top - cursor >= MIN_BAND_HEIGHT_PX) {
                bands.add(Rect(target.left, cursor, target.right, top))
            }
            cursor = maxOf(cursor, bottom)
        }
        if (target.bottom - cursor >= MIN_BAND_HEIGHT_PX) {
            bands.add(Rect(target.left, cursor, target.right, target.bottom))
        }
        return bands
    }

    /**
     * يدمج كتل النص (من الواجهة ومن OCR معا) في نص واحد مرتب من أعلى الشاشة
     * إلى أسفلها، مع حذف التكرار الحرفي الذي قد ينشأ عند التحديد الممتد حين
     * يظهر نفس السطر في لقطتين متتاليتين.
     */
    fun merge(blocks: List<TextBlock>): String {
        val seen = LinkedHashSet<String>()
        return blocks
            .sortedWith(compareBy({ it.top }, { it.bounds.left }))
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it) }
            .joinToString(separator = "\n")
    }
}
