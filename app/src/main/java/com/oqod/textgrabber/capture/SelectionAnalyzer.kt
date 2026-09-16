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

object SelectionAnalyzer {

    /**
     * أقل ارتفاع (بالبكسل) لشريط فارغ من النص حتى يُحتسب في المساحة الخالية.
     * أقل من ذلك يكون مجرد تباعد بين أسطر النص.
     */
    private const val MIN_BAND_HEIGHT_PX = 48

    /**
     * يحسب الأشرطة الأفقية داخل [target] التي لا يغطيها أي نص من الواجهة،
     * انطلاقا من حدود عناصر النص. يدمج الحدود المتداخلة أولا ثم يأخذ المتمم.
     *
     * **لم تعد هذه الأشرطة تُقصّ وتُقرأ بالـOCR** (كان ذلك حتى v1.4.1): الشريط
     * بعرض المربع كاملا يقطّع الصورة عند أي نص بجانبها ويُدخل معها الأزرار
     * المجاورة، فيضعف "النسخ المركب" عن "نسخ OCR" الصرف. صارت الصور تُكتشف
     * كعناصر بحدودها الحقيقية وتُقرأ كل واحدة وحدها. أما هذه الأشرطة فتُستخدم
     * الآن فقط لتقدير نسبة المساحة الخالية من النص، احتياطا للتطبيقات التي
     * ترسم صورها بنفسها فلا تظهر لها عناصر في الواجهة.
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
     * يدمج كتل النص (من الواجهة ومن OCR معا) في نص واحد بترتيب القراءة:
     * صفوفا من الأعلى إلى الأسفل، وداخل الصف من اليمين إلى اليسار في
     * الصفحات العربية (انظر [MixedContentComposer.orderByReading]). ويحذف
     * التكرار الحرفي الذي ينشأ عند التحديد الممتد حين يظهر نفس السطر في
     * لقطتين متتاليتين.
     *
     * الموضع العمودي يؤخذ من [TextBlock.top] لا من حدوده: في التحديد الممتد
     * تُزاح [TextBlock.top] بمقدار ما مُرّر، بينما تبقى حدوده كما التُقطت.
     */
    fun merge(blocks: List<TextBlock>): String {
        val placed = blocks.map { block ->
            PlacedText(
                block.text,
                Box(block.bounds.left, block.top, block.bounds.right, block.top + block.bounds.height())
            )
        }
        val ordered = MixedContentComposer.orderByReading(
            placed,
            MixedContentComposer.isRightToLeft(placed)
        )
        return MixedContentComposer.join(ordered)
    }
}
