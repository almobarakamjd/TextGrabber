package com.oqod.textgrabber.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ModelVerifierTest {

    /** بصمة SHA-256 المعروفة لكلمة "abc" (متجه اختبار قياسي من NIST). */
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `ينسخ المحتوى كاملا ويحسب البصمة الصحيحة`() {
        val output = ByteArrayOutputStream()
        val hash = ModelVerifier.copyAndHash(ByteArrayInputStream("abc".toByteArray()), output)

        assertEquals(abcSha256, hash)
        assertArrayEquals("abc".toByteArray(), output.toByteArray())
    }

    @Test
    fun `يحسب البصمة صحيحة لملف أكبر من حجم المخزن المؤقت`() {
        // 300 كيلوبايت: أكبر من المخزن (64) ومن خطوة التقدّم (256)، فيمر
        // الحساب عبر عدة قراءات جزئية.
        val data = ByteArray(300 * 1024) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val hash = ModelVerifier.copyAndHash(ByteArrayInputStream(data), output) { progress += it }

        assertEquals(expected, hash)
        assertEquals(data.size, output.size())
        assertEquals(data.size.toLong(), progress.last())
        assertTrue("التقدّم يجب أن يتزايد", progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `يقبل الملف المطابق حجما وبصمة بغض النظر عن حالة الأحرف`() {
        ModelVerifier.verify(3, abcSha256.uppercase(), 3, abcSha256)
    }

    @Test(expected = ModelVerificationException::class)
    fun `يرفض الملف الناقص`() {
        ModelVerifier.verify(2, abcSha256, 3, abcSha256)
    }

    @Test(expected = ModelVerificationException::class)
    fun `يرفض الملف المعدل ولو تطابق حجمه`() {
        val tampered = "0" + abcSha256.drop(1)
        ModelVerifier.verify(3, tampered, 3, abcSha256)
    }
}
