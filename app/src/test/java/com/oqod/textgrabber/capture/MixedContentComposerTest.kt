package com.oqod.textgrabber.capture

import com.oqod.textgrabber.ocr.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedContentComposerTest {

    private fun ui(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        PlacedText(text, Box(left, top, right, bottom))

    private fun ocr(
        text: String,
        region: Int,
        line: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Float = 90f
    ) = ScreenWord(OcrWord(text, confidence, line), region, Box(left, top, right, bottom))

    private fun compose(ui: List<PlacedText>, words: List<ScreenWord>) =
        MixedContentComposer.join(MixedContentComposer.compose(ui, words))

    /**
     * مثال أبو فيصل: مقال فيه صورة (بسم الله الرحمن الرحيم)، ثم نص (الحمد
     * لله رب العالمين)، ثم صورة (اعلم)، ثم نص (رحمك الله تعالى).
     */
    @Test
    fun `يركب الصور والنصوص المتناوبة بترتيب ظهورها`() {
        val uiTexts = listOf(
            ui("الحمد لله رب العالمين", 40, 300, 1040, 360),
            ui("رحمك الله تعالى", 40, 700, 1040, 760)
        )
        // الكلمات بترتيب القراءة العربية داخل كل سطر: الأولى أقصى اليمين.
        val words = listOf(
            ocr("بسم", 0, 0, 800, 100, 950, 180),
            ocr("الله", 0, 0, 620, 100, 780, 180),
            ocr("الرحمن", 0, 0, 380, 100, 600, 180),
            ocr("الرحيم", 0, 0, 130, 100, 360, 180),
            ocr("اعلم", 1, 0, 400, 500, 680, 600)
        )

        assertEquals(
            "بسم الله الرحمن الرحيم\nالحمد لله رب العالمين\nاعلم\nرحمك الله تعالى",
            compose(uiTexts, words)
        )
    }

    @Test
    fun `يحذف كلمات OCR الواقعة فوق نص معروف من الواجهة ويعتمد نص الواجهة`() {
        // احتياط "قراءة المربع كاملا": المحرك قرأ نص الواجهة أيضا وبخطأ.
        val uiTexts = listOf(ui("القرآن نور وبركة", 100, 900, 1000, 960))
        val words = listOf(
            ocr("السلام", 0, 0, 500, 200, 900, 300),
            ocr("عليكم", 0, 0, 100, 200, 480, 300),
            ocr("القرءان", 0, 1, 700, 905, 990, 955),
            ocr("نوو", 0, 1, 400, 905, 680, 955)
        )

        assertEquals("السلام عليكم\nالقرآن نور وبركة", compose(uiTexts, words))
    }

    @Test
    fun `يرتب عناصر الصف الواحد من اليمين إلى اليسار في الصفحة العربية`() {
        val items = listOf(
            ui("يسار", 0, 100, 300, 160),
            ui("يمين", 700, 105, 1000, 165),
            ui("تحت", 0, 400, 1000, 460)
        )
        val ordered = MixedContentComposer.orderByReading(items, rightToLeft = true)
        assertEquals(listOf("يمين", "يسار", "تحت"), ordered.map { it.text })
    }

    @Test
    fun `يرتب عناصر الصف الواحد من اليسار إلى اليمين في الصفحة اللاتينية`() {
        val items = listOf(
            ui("right", 700, 100, 1000, 160),
            ui("left", 0, 105, 300, 165)
        )
        val ordered = MixedContentComposer.orderByReading(items, rightToLeft = false)
        assertEquals(listOf("left", "right"), ordered.map { it.text })
    }

    @Test
    fun `لا يعتبر عنصرين متتاليين عموديا في صف واحد لمجرد تلامس بسيط`() {
        val items = listOf(
            ui("الثاني", 700, 150, 1000, 250),
            ui("الأول", 0, 100, 300, 160)
        )
        // تداخل 10 بكسل فقط من ارتفاع 60: ليس نفس الصف، فالأعلى أولا.
        val ordered = MixedContentComposer.orderByReading(items, rightToLeft = true)
        assertEquals(listOf("الأول", "الثاني"), ordered.map { it.text })
    }

    @Test
    fun `ينظف تشويش الصورة داخل المركب`() {
        val uiTexts = listOf(ui("القرآن نور وبركة", 100, 900, 1000, 960))
        val words = listOf(
            ocr("السلا", 0, 0, 500, 200, 900, 300),
            ocr("م", 0, 0, 470, 200, 500, 300),
            ocr("عليكم", 0, 0, 100, 200, 460, 300),
            ocr("ل", 0, 1, 500, 350, 520, 380, confidence = 30f),
            ocr("|", 0, 2, 950, 400, 960, 500, confidence = 20f),
            ocr("وبركاته", 0, 2, 300, 400, 900, 500)
        )

        assertEquals("السلام عليكم\nوبركاته\nالقرآن نور وبركة", compose(uiTexts, words))
    }

    @Test
    fun `يحذف السطر المكرر حرفيا`() {
        val items = listOf(ui("نص", 0, 0, 100, 50), ui("نص", 0, 500, 100, 550))
        assertEquals("نص", MixedContentComposer.join(items))
    }

    @Test
    fun `يكتشف اتجاه الصفحة من أغلب حروفها`() {
        assertTrue(MixedContentComposer.isRightToLeft(listOf(ui("مرحبا بكم hi", 0, 0, 1, 1))))
        assertFalse(MixedContentComposer.isRightToLeft(listOf(ui("Hello world مرحبا", 0, 0, 1, 1))))
    }
}
