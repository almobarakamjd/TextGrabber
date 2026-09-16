package com.oqod.textgrabber.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextCleanerTest {

    private fun line(index: Int, vararg words: Pair<String, Float>) =
        words.map { (text, confidence) -> OcrWord(text, confidence, index) }

    /**
     * الناتج الفعلي من تجربة أبو فيصل على ملصق "السلام عليكم ورحمة الله
     * وبركاته" (2026-09-16). درجات الثقة هنا تقديرية: التشويش منخفضها.
     */
    @Test
    fun `ينظّف ناتج ملصق السلام عليكم من التشويش والتقسيم`() {
        val words = line(0, "السلا" to 88f, "م" to 81f, "عليكم" to 91f) +
            line(1, "ل" to 34f) +
            line(2, "ورحمه" to 79f, "الله" to 93f) +
            line(3, "|" to 22f, "وبركاته" to 86f, "." to 30f, "2" to 41f)

        assertEquals(
            "السلام عليكم\nورحمه الله\nوبركاته",
            OcrTextCleaner.clean(words)
        )
    }

    @Test
    fun `يحذف السطر المكون من حرف واحد حتى لو كانت ثقته عالية`() {
        val words = line(0, "ل" to 95f) + line(1, "الحمد" to 90f, "لله" to 90f)
        assertEquals("الحمد لله", OcrTextCleaner.clean(words))
    }

    @Test
    fun `يبقي الرقم الحقيقي الواضح على طرف السطر`() {
        val words = line(0, "سورة" to 92f, "البقرة" to 94f, "2" to 96f)
        assertEquals("سورة البقرة 2", OcrTextCleaner.clean(words))
    }

    @Test
    fun `يحذف الرقم المشكوك فيه على طرف السطر`() {
        val words = line(0, "وبركاته" to 90f, "2" to 60f)
        assertEquals("وبركاته", OcrTextCleaner.clean(words))
    }

    @Test
    fun `لا يدمج واو العطف المنفصلة`() {
        val words = line(0, "الله" to 90f, "و" to 90f, "رسوله" to 90f)
        assertEquals("الله و رسوله", OcrTextCleaner.clean(words))
    }

    @Test
    fun `لا يدمج حرفا منفردا بعد كلمة تنتهي بحرف متصل`() {
        // "عليكم" تنتهي بميم تتصل بما بعدها، فالانفصال هنا ليس خطأ تقسيم.
        val words = line(0, "عليكم" to 90f, "ب" to 90f)
        assertEquals("عليكم ب", OcrTextCleaner.clean(words))
    }

    @Test
    fun `يبقي النص الإنجليزي كما هو`() {
        val words = line(0, "Hello" to 90f, "World" to 88f)
        assertEquals("Hello World", OcrTextCleaner.clean(words))
    }

    @Test
    fun `يستبعد الكلمات منخفضة الثقة`() {
        val words = line(0, "نص" to 90f, "واضح" to 90f, "xq" to 20f)
        assertEquals("نص واضح", OcrTextCleaner.clean(words))
    }

    @Test
    fun `ينظف النص الخام حين لا تتوفر درجات الثقة`() {
        assertEquals(
            "السلام عليكم\nوبركاته",
            OcrTextCleaner.cleanRaw("السلا م عليكم\nل\n| وبركاته .")
        )
    }

    @Test
    fun `يعيد نصا فارغا حين يكون كله تشويشا`() {
        val words = line(0, "|" to 90f) + line(1, "." to 90f) + line(2, "ل" to 90f)
        assertEquals("", OcrTextCleaner.clean(words))
    }
}
