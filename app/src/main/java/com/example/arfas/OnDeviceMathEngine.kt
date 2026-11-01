package com.example.arfas

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Small deterministic local calculator used after semantic math-intent detection.
 *
 * This is deliberately NOT a topic router. It only parses/evaluates arithmetic after
 * SemanticCapabilityContextProvider classifies the turn as MATH_CALCULATION.
 */
object OnDeviceMathEngine {

    data class Result(
        val answer: String,
        val canonicalExpression: String,
        val value: Double
    )

    fun solve(rawQuery: String): Result? {
        val original = rawQuery.trim()
        if (original.isBlank()) return null
        val normalized = normalizeExpression(original) ?: return null
        val value = runCatching { Parser(normalized).parse() }.getOrNull() ?: return null
        if (!value.isFinite()) return null
        val answer = buildAnswer(value)
        return Result(answer = answer, canonicalExpression = normalized, value = value)
    }

    private fun normalizeExpression(input: String): String? {
        var s = input.lowercase(Locale.US)
            .replace(',', ' ')
            .replace("₹", " ")
            .replace("$", " ")
            .replace("€", " ")
            .replace("£", " ")

        // Convert common scale suffixes into numeric literals. These are numeric grammar,
        // not routing keywords.
        s = scaleNumbers(s)

        // Natural-language arithmetic grammar. Semantic intent has already decided that
        // the turn is a calculation; these replacements only convert the expression.
        val replacements = listOf(
            Regex("\\bsquare\\s+root\\s+of\\b") to " sqrt ",
            Regex("\\bsqrt\\s+of\\b") to " sqrt ",
            Regex("\\bmultiplied\\s+by\\b") to " * ",
            Regex("\\bmultiply\\s+by\\b") to " * ",
            Regex("\\btimes\\b") to " * ",
            Regex("\\bdivided\\s+by\\b") to " / ",
            Regex("\\bdivide\\s+by\\b") to " / ",
            Regex("\\bplus\\b") to " + ",
            Regex("\\bminus\\b") to " - ",
            Regex("\\bpercent\\s+of\\b") to " % * ",
            Regex("\\bpercentage\\s+of\\b") to " % * ",
            Regex("\\bof\\b") to " * "
        )
        replacements.forEach { (r, v) -> s = r.replace(s, v) }

        s = s.replace('×', '*').replace('÷', '/').replace('^', '^')
        s = Regex("(?<=\\d)\\s*x\\s*(?=\\d)").replace(s, "*")
        s = Regex("(?<=\\d)\\s*%\\s*(?=\\d)").replace(s, "% * ")

        // Keep only math syntax and sqrt token after semantic classification.
        // Words such as "what is" simply disappear here.
        s = s.replace(Regex("[^0-9.+\\-*/%^()\\s]|(?<![a-z])sqrt(?![a-z])")) { m ->
            if (m.value == "sqrt") "sqrt" else " "
        }
        s = s.replace(Regex("\\s+"), " ").trim()

        // Restore sqrt if the broad character filter split it.
        if (input.lowercase(Locale.US).contains("sqrt") ||
            Regex("(?i)square\\s+root").containsMatchIn(input)) {
            val number = Regex("(?:sqrt|square\\s+root(?:\\s+of)?)\\s*([0-9][0-9.,]*)", RegexOption.IGNORE_CASE)
                .find(input)?.groupValues?.getOrNull(1)?.replace(",", "")
            if (!number.isNullOrBlank()) s = "sqrt($number)"
        }

        // Need at least one operator/function; otherwise a plain number is not a calculation.
        if (!Regex("[+\\-*/%^()]|sqrt").containsMatchIn(s)) return null
        return s
    }

    private fun scaleNumbers(input: String): String {
        val scale = mapOf(
            "thousand" to 1_000.0,
            "lakh" to 100_000.0,
            "lakhs" to 100_000.0,
            "crore" to 10_000_000.0,
            "crores" to 10_000_000.0,
            "million" to 1_000_000.0,
            "millions" to 1_000_000.0,
            "billion" to 1_000_000_000.0,
            "billions" to 1_000_000_000.0
        )
        return Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(thousand|lakhs?|crores?|millions?|billions?)\\b", RegexOption.IGNORE_CASE)
            .replace(input) { m ->
                val base = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
                val factor = scale[m.groupValues[2].lowercase(Locale.US)] ?: 1.0
                formatPlain(base * factor)
            }
    }

    private fun buildAnswer(value: Double): String {
        val formatted = formatNumber(value)
        val indian = indianScale(value)
        return buildString {
            append(formatted)
            if (indian != null && !formatted.contains(indian, ignoreCase = true)) {
                append(" (").append(indian).append(')')
            }
        }
    }

    private fun formatNumber(value: Double): String {
        val rounded = BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
        val df = DecimalFormat("#,##0.########")
        return df.format(rounded.toDouble())
    }

    private fun formatPlain(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun indianScale(value: Double): String? {
        val a = abs(value)
        return when {
            a >= 10_000_000.0 -> "${trimDecimal(value / 10_000_000.0)} crore"
            a >= 100_000.0 -> "${trimDecimal(value / 100_000.0)} lakh"
            else -> null
        }
    }

    private fun trimDecimal(value: Double): String =
        BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Double {
            val value = parseExpression()
            skipSpaces()
            if (pos != text.length) error("Unexpected token at $pos")
            return value
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                value = when {
                    eat('+') -> value + parseTerm()
                    eat('-') -> value - parseTerm()
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                skipSpaces()
                value = when {
                    eat('*') -> value * parsePower()
                    eat('/') -> {
                        val d = parsePower()
                        if (abs(d) < 1e-15) error("division by zero")
                        value / d
                    }
                    else -> return value
                }
            }
        }

        private fun parsePower(): Double {
            var value = parseUnary()
            skipSpaces()
            if (eat('^')) value = value.pow(parsePower())
            return value
        }

        private fun parseUnary(): Double {
            skipSpaces()
            if (eat('+')) return parseUnary()
            if (eat('-')) return -parseUnary()
            if (peekWord("sqrt")) {
                pos += 4
                skipSpaces()
                val v = if (eat('(')) {
                    val inside = parseExpression()
                    if (!eat(')')) error("missing )")
                    inside
                } else parseUnary()
                if (v < 0) error("sqrt negative")
                return sqrt(v)
            }
            var value = parsePrimary()
            while (true) {
                skipSpaces()
                if (eat('%')) value /= 100.0 else break
            }
            return value
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (eat('(')) {
                val value = parseExpression()
                if (!eat(')')) error("missing )")
                return value
            }
            val start = pos
            var dotSeen = false
            while (pos < text.length) {
                val c = text[pos]
                if (c.isDigit()) pos++
                else if (c == '.' && !dotSeen) { dotSeen = true; pos++ }
                else break
            }
            if (start == pos) error("number expected")
            return text.substring(start, pos).toDouble()
        }

        private fun skipSpaces() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        private fun eat(c: Char): Boolean {
            skipSpaces()
            if (pos < text.length && text[pos] == c) { pos++; return true }
            return false
        }
        private fun peekWord(word: String): Boolean =
            text.regionMatches(pos, word, 0, word.length, ignoreCase = true)
    }
}
