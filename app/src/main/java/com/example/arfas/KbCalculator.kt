package com.example.arfas

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Arithmetic for KB answers is done by the app, never by the language model (small models get
 * "3% of 8,40,000" wrong). The question rewrite gives an expression for the amount the user
 * describes ("20 * 42000"); the app evaluates it and applies the percentages that the key facts
 * themselves state ("3 to 5 per cent", "5%"). Nothing here knows any topic.
 */
object KbCalculator {
    data class Calculation(val expression: String, val value: BigDecimal, val percentages: List<Pair<BigDecimal, BigDecimal>>) {
        /** Evidence line read by the answer model, e.g. "20 × 42000 = 8,40,000; 3% of 8,40,000 = 25,200". */
        fun describe(): String = buildString {
            append("Calculated by the app: ").append(expression.replace("*", " × ").replace("/", " ÷ "))
                .append(" = ").append(indian(value))
            percentages.forEach { (p, v) -> append("; ").append(plain(p)).append("% of ").append(indian(value)).append(" = ").append(indian(v)) }
            append('.')
        }
    }

    private const val MAX_PERCENTAGES = 4

    /** Evaluates [expression] (numbers, + - * / x × ÷, brackets, Indian/Western commas). Null when invalid. */
    fun evaluate(expression: String): BigDecimal? {
        val clean = expression.replace(Regex("(?<=\\d),(?=\\d)"), "").replace('×', '*').replace('÷', '/')
            .replace(Regex("(?<=[\\d)\\s])[xX](?=[\\s\\d(])"), "*").replace(Regex("[₹$€£]|\\b(rs|inr)\\.?", RegexOption.IGNORE_CASE), "")
            .replace(" ", "")
        if (clean.isEmpty() || !Regex("[0-9.+\\-*/()]+").matches(clean) || !clean.any(Char::isDigit)) return null
        return runCatching { Parser(clean).parse() }.getOrNull()?.takeIf { it.signum() >= 0 }
    }

    private val NUMBER_WORD = Regex(
        "\\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|twenty[- ]five|thirty|fifty)\\s*\\((\\d+(?:\\.\\d+)?)\\)",
        RegexOption.IGNORE_CASE
    )
    private const val UNIT = "(?:\\s*%|\\s*per\\s*cent\\b|\\s*percent\\b|\\s*प्रतिशत)"
    private val RANGE = Regex("(\\d+(?:\\.\\d+)?)\\s*%?\\s*(?:to|-|–|and|or|से)\\s*(\\d+(?:\\.\\d+)?)$UNIT", RegexOption.IGNORE_CASE)
    private val SINGLE = Regex("(\\d+(?:\\.\\d+)?)$UNIT", RegexOption.IGNORE_CASE)

    /** "three (3) to five (5) per cent" is written as "3 to 5 per cent" before matching. */
    private fun normalise(text: String) = NUMBER_WORD.replace(text) { it.groupValues[1] }

    /** Every percentage stated in [text]: "5%", "3 per cent", "3 to 5 per cent", "3-5%", "५ प्रतिशत". */
    fun percentages(text: String): List<BigDecimal> {
        val t = normalise(text)
        val out = LinkedHashSet<BigDecimal>()
        RANGE.findAll(t).forEach { out += BigDecimal(it.groupValues[1]); out += BigDecimal(it.groupValues[2]) }
        SINGLE.findAll(t).forEach { out += BigDecimal(it.groupValues[1]) }
        return out.filter { it.signum() > 0 && it <= BigDecimal(100) }.take(MAX_PERCENTAGES)
    }

    /**
     * The first percentage statement of [text] only (a range gives both ends): the rule itself, not
     * the exceptions written after it ("3 to 5 per cent (3 to 10% for Works)" gives 3 and 5).
     */
    fun leadingPercentages(text: String): List<BigDecimal> {
        val t = normalise(text)
        val range = RANGE.find(t)
        val single = SINGLE.find(t)
        val values = when {
            range != null && (single == null || range.range.first <= single.range.first) ->
                listOf(BigDecimal(range.groupValues[1]), BigDecimal(range.groupValues[2]))
            single != null -> listOf(BigDecimal(single.groupValues[1]))
            else -> emptyList()
        }
        return values.filter { it.signum() > 0 && it <= BigDecimal(100) }.distinct()
    }

    /**
     * Value of [expression] plus the percentages of the first fact (in [facts], best first) that
     * states one; null when the expression is not usable.
     */
    fun calculate(expression: String, facts: List<String>): Calculation? {
        val value = evaluate(expression) ?: return null
        val stated = facts.asSequence().map { leadingPercentages(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val pcts = stated.map { p -> p to value.multiply(p).divide(BigDecimal(100), 2, RoundingMode.HALF_UP) }
        return Calculation(expression.trim(), value, pcts)
    }

    fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

    /** Indian digit grouping: 840000 -> 8,40,000; 25200.5 -> 25,200.50. */
    fun indian(v: BigDecimal): String {
        val scaled = v.setScale(2, RoundingMode.HALF_UP)
        val whole = scaled.toBigInteger().toString()
        val fraction = scaled.remainder(BigDecimal.ONE).abs().movePointRight(2).toInt()
        val grouped = if (whole.length <= 3) whole else {
            val head = whole.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        }
        return if (fraction == 0) grouped else grouped + "." + String.format(Locale.US, "%02d", fraction)
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): BigDecimal { val v = expr(); require(i == s.length); return v }
        private fun expr(): BigDecimal {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun term(): BigDecimal {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]; val r = factor()
                v = if (op == '*') v.multiply(r) else v.divide(r, 6, RoundingMode.HALF_UP)
            }
            return v
        }
        private fun factor(): BigDecimal {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); require(s[i++] == ')'); return v }
            if (i < s.length && s[i] == '-') { i++; return factor().negate() }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return BigDecimal(s.substring(start, i))
        }
    }
}
