package com.example.arfas

/**
 * Direct package-adapted port of AndroLLM's CoherenceChecker.
 * Used only once per backend/model load, never as a per-turn answer judge.
 */
internal sealed interface AndroLLMCoherenceResult {
    data object Pass : AndroLLMCoherenceResult
    data class Fail(val reason: String) : AndroLLMCoherenceResult
}

internal object AndroLLMCoherenceChecker {
    private const val MIN_PRINTABLE_RATIO = 0.6
    private const val MAX_REPLACEMENT_RATIO = 0.1
    private const val MAX_TOP_CHAR_FREQUENCY = 0.7
    private const val MIN_TEXT_LENGTH = 2

    fun check(text: String?): AndroLLMCoherenceResult {
        val output = text?.trim().orEmpty()
        if (output.isEmpty()) {
            return AndroLLMCoherenceResult.Fail("model produced no output")
        }
        if (output.length < MIN_TEXT_LENGTH) {
            return AndroLLMCoherenceResult.Fail("output too short to be coherent: \"$output\"")
        }

        var printable = 0
        var replacement = 0
        var control = 0
        for (ch in output) {
            when {
                ch == '\uFFFD' -> replacement++
                ch.code < 0x20 && ch.code != '\n'.code && ch.code != '\t'.code && ch.code != '\r'.code -> control++
                !ch.isISOControl() -> printable++
            }
        }
        val printableRatio = printable.toDouble() / output.length
        val replacementRatio = replacement.toDouble() / output.length
        if (control > 0) {
            return AndroLLMCoherenceResult.Fail("output contains control characters")
        }
        if (replacementRatio > MAX_REPLACEMENT_RATIO) {
            return AndroLLMCoherenceResult.Fail("output contains replacement-character garbage (broken tokenizer)")
        }
        if (printableRatio < MIN_PRINTABLE_RATIO) {
            return AndroLLMCoherenceResult.Fail("output is not printable text")
        }

        if (output.length >= 8) {
            val mostFrequent = output.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
            if (mostFrequent.toDouble() / output.length > MAX_TOP_CHAR_FREQUENCY) {
                return AndroLLMCoherenceResult.Fail("output degenerates into repeated characters")
            }
        }
        return AndroLLMCoherenceResult.Pass
    }
}
