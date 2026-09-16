package com.oqod.textgrabber.ocr

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** يُرمى حين لا يطابق الملف المنزَّل الحجم أو البصمة المتوقعة. */
class ModelVerificationException(message: String) : Exception(message)

/**
 * نسخ ملف النموذج مع حساب بصمته في نفس المرور، ثم مطابقتها.
 *
 * منطق خالص بلا اعتماد على أندرويد، يُختبر في `ModelVerifierTest`.
 */
object ModelVerifier {

    private const val BUFFER_SIZE = 64 * 1024

    /** لا نحدّث شريط التقدّم مع كل كتلة، بل كل ربع ميجابايت تقريبا. */
    private const val PROGRESS_STEP_BYTES = 256 * 1024L

    /**
     * ينسخ [input] إلى [output] ويعيد بصمة SHA-256 للمحتوى بصيغة سداسية
     * عشرية بأحرف صغيرة. حساب البصمة أثناء النسخ يغني عن قراءة الملف مرة
     * ثانية بعد التنزيل.
     */
    fun copyAndHash(
        input: InputStream,
        output: OutputStream,
        onProgress: (copiedBytes: Long) -> Unit = {}
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var lastReported = 0L

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            copied += read
            if (copied - lastReported >= PROGRESS_STEP_BYTES) {
                onProgress(copied)
                lastReported = copied
            }
        }
        onProgress(copied)

        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    fun verify(actualSize: Long, actualSha256: String, expectedSize: Long, expectedSha256: String) {
        if (actualSize != expectedSize) {
            throw ModelVerificationException("الحجم $actualSize لا يطابق المتوقع $expectedSize")
        }
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw ModelVerificationException("البصمة لا تطابق المتوقع")
        }
    }
}
