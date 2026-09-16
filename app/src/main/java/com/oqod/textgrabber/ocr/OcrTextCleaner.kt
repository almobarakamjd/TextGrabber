package com.oqod.textgrabber.ocr

/**
 * كلمة واحدة كما أخرجها Tesseract، مع درجة ثقته بها (0-100) ورقم السطر
 * الذي تنتمي إليه.
 */
data class OcrWord(val text: String, val confidence: Float, val line: Int)

/**
 * تنظيف ناتج التعرّف الضوئي قبل نسخه.
 *
 * منطق خالص بلا أي اعتماد على أندرويد، حتى يُختبر باختبارات وحدة عادية
 * (انظر `OcrTextCleanerTest`) بدل الاعتماد على التجربة اليدوية وحدها.
 *
 * نشأت هذه القواعد من تجربة فعلية على صورة ملصق "السلام عليكم ورحمة الله
 * وبركاته" بزخارف (ريش، دائرة ملونة، حامل مصحف)، أخرجت:
 *
 * ```
 * السلا م عليكم
 * ل
 * ورحمه الله
 * | وبركاته . 2
 * ```
 *
 * فالمحرك يقرأ الزخارف حروفا ورموزا، ويقسم الكلمة العربية بعد الحروف التي
 * لا تتصل بما بعدها.
 */
object OcrTextCleaner {

    /**
     * أقل درجة ثقة لقبول كلمة. التشويش الناتج عن الزخارف يأتي عادة بثقة
     * منخفضة جدا، بينما النص الحقيقي الواضح يتجاوز 70 غالبا.
     */
    const val MIN_WORD_CONFIDENCE = 50f

    /**
     * رمز من خانة واحدة ليس حرفا (رقم مثل "2") على طرف سطر يُقبل فقط بثقة
     * عالية. حواف الأشكال تُقرأ أرقاما كثيرا، لكننا لا نحذف رقما حقيقيا
     * واضحا مثل "سورة البقرة 2" لمجرد موقعه.
     */
    const val EDGE_SYMBOL_MIN_CONFIDENCE = 85f

    /** ثقة افتراضية حين لا تتوفر درجات من المحرك: نعامل كل شيء كموثوق. */
    private const val UNKNOWN_CONFIDENCE = 100f

    /** رموز لا تكون نصا مقصودا أبدا في ناتج OCR، بل أثر حواف وخطوط. */
    private val NOISE_CHARS = setOf('|', '¦', '§', '~', '_', '`', '^', '¬', '•', '«', '»')

    /**
     * الحروف العربية التي لا تتصل بما بعدها. يخطئ Tesseract فيضع مسافة
     * بعدها لأن الفراغ البصري بعدها يشبه الفراغ بين كلمتين: "السلا م".
     */
    private val NON_JOINING_ARABIC = setOf('ا', 'أ', 'إ', 'آ', 'د', 'ذ', 'ر', 'ز', 'و', 'ؤ', 'ة', 'ى')

    /**
     * حروف تأتي كلمة مستقلة من حرف واحد في الكتابة العربية، فلا تُدمج مع
     * ما قبلها. "و" العاطفة تُكتب أحيانا منفصلة.
     */
    private val STANDALONE_SINGLE_LETTERS = setOf('و')

    private data class Token(val text: String, val confidence: Float)

    fun clean(words: List<OcrWord>): String {
        return words
            .filter { it.confidence >= MIN_WORD_CONFIDENCE }
            .groupBy { it.line }
            .toSortedMap()
            .values
            .map { lineWords -> cleanLine(lineWords.map { Token(it.text, it.confidence) }) }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    /** للحالات التي لا تتوفر فيها درجات الثقة: ينظّف نصا خاما سطرا سطرا. */
    fun cleanRaw(text: String): String {
        return text.lines()
            .map { line ->
                cleanLine(line.split(' ', '\t').map { Token(it, UNKNOWN_CONFIDENCE) })
            }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    private fun cleanLine(rawTokens: List<Token>): String {
        var tokens = rawTokens
            .map { token -> token.copy(text = token.text.filterNot { it in NOISE_CHARS }.trim()) }
            .filter { it.text.isNotEmpty() }
            // رمز بلا أي حرف أو رقم (نقطة وحيدة، شرطة...) ليس كلمة.
            .filter { token -> token.text.any { it.isLetterOrDigit() } }

        tokens = dropEdgeNoise(tokens)
        val texts = mergeSplitArabicWords(tokens.map { it.text })

        // سطر بأقل من حرفين فعليين هو أثر زخرفة، لا نص: مثل "ل" المنفردة.
        val letterCount = texts.sumOf { text -> text.count { it.isLetter() } }
        if (letterCount < 2) return ""

        return texts.joinToString(separator = " ")
    }

    /**
     * يحذف رمزا من خانة واحدة ليس حرفا على طرف السطر، إن كان في السطر
     * كلمات حقيقية **وكانت ثقة المحرك به دون [EDGE_SYMBOL_MIN_CONFIDENCE]**.
     */
    private fun dropEdgeNoise(tokens: List<Token>): List<Token> {
        if (tokens.size < 2) return tokens
        val hasWords = tokens.any { token -> token.text.count { it.isLetter() } >= 2 }
        if (!hasWords) return tokens

        fun isEdgeNoise(token: Token) = token.text.length == 1 &&
            !token.text[0].isLetter() &&
            token.confidence < EDGE_SYMBOL_MIN_CONFIDENCE

        var result = tokens
        if (isEdgeNoise(result.first())) result = result.drop(1)
        if (result.isNotEmpty() && isEdgeNoise(result.last())) result = result.dropLast(1)
        return result
    }

    /**
     * يعيد لصق حرف عربي منفرد بالكلمة السابقة إن كانت تنتهي بحرف لا يتصل
     * بما بعده: "السلا م" ← "السلام".
     */
    private fun mergeSplitArabicWords(tokens: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (token in tokens) {
            val previous = result.lastOrNull()
            val isLoneArabicLetter = token.length == 1 && isArabicLetter(token[0]) &&
                token[0] !in STANDALONE_SINGLE_LETTERS
            if (previous != null && isLoneArabicLetter && previous.last() in NON_JOINING_ARABIC) {
                result[result.lastIndex] = previous + token
            } else {
                result.add(token)
            }
        }
        return result
    }

    private fun isArabicLetter(c: Char): Boolean = c in 'ء'..'ي'
}
