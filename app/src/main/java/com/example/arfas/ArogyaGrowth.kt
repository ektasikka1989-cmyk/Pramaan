package com.example.arfas

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Gap round item 4: growth chart + ANC trend. FIXED RULES, never AI.
 *
 * Weight-for-age zones follow the WHO Child Growth Standards (the same curves
 * printed on the MCP card): green = normal, yellow = below -2 SD (moderate),
 * red = below -3 SD (severe). The reference table is bundled at
 * assets/growth/who_wfa.json (parsed from the official WHO simplified
 * field tables, birth to 5 years, both sexes) and loaded at runtime.
 * Pure Kotlin, host-tested (GrowthTest); no Android imports.
 */
object ArogyaGrowth {
    /** Asset path of the bundled WHO weight-for-age table. */
    const val ASSET = "growth/who_wfa.json"

    /** One row: median, -2 SD, -3 SD in kg. */
    data class Row(val median: Double, val m2: Double, val m3: Double)

    /** 61 rows (months 0..60) per sex. Empty lists = table missing. */
    data class Table(val boys: List<Row>, val girls: List<Row>) {
        val ok: Boolean get() = boys.size == 61 && girls.size == 61
        fun rowsFor(sex: String): List<Row> =
            if (sex.equals("M", true) || sex.equals("Male", true)) boys else girls
    }

    enum class Zone { GREEN, YELLOW, RED }

    /** Parses the bundled who_wfa.json. Never throws: bad input gives an empty table. */
    fun parseTable(json: String): Table {
        fun rows(o: JSONObject, key: String): List<Row> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val r = a.optJSONArray(i) ?: return@mapNotNull null
                if (r.length() < 3) return@mapNotNull null
                Row(r.optDouble(0, Double.NaN), r.optDouble(1, Double.NaN), r.optDouble(2, Double.NaN))
                    .takeIf { it.median.isFinite() && it.m2.isFinite() && it.m3.isFinite() }
            }
        }
        return runCatching {
            val o = JSONObject(json)
            Table(rows(o, "boys"), rows(o, "girls"))
        }.getOrDefault(Table(emptyList(), emptyList()))
    }

    /** Completed months between DOB and the weight date, clamped to the 0..60 table. -1 = bad dates. */
    fun ageMonths(dob: String, date: String): Int {
        val b = runCatching { LocalDate.parse(dob) }.getOrNull() ?: return -1
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return -1
        return ChronoUnit.MONTHS.between(b, d).toInt().coerceIn(0, 60)
    }

    /**
     * Zone for one weight. Fixed rule: strictly below -3 SD is RED,
     * strictly below -2 SD is YELLOW, anything else is GREEN (normal).
     * Null when the table or inputs are unusable (chart hidden, never guessed).
     */
    fun zone(sex: String, month: Int, kg: Double, table: Table): Zone? {
        if (!table.ok || month !in 0..60 || !kg.isFinite()) return null
        val r = table.rowsFor(sex)[month]
        return when {
            kg < r.m3 -> Zone.RED
            kg < r.m2 -> Zone.YELLOW
            else -> Zone.GREEN
        }
    }

    /** One plotted dot, coordinates normalised to 0..1 (renderers scale to pixels). */
    data class Dot(val x: Float, val y: Float, val month: Int, val kg: Double, val zone: Zone, val date: String)

    /** Chart geometry: axis ranges, reference curves, and the plotted dots. */
    data class Layout(val xMax: Int, val yMax: Double, val median: List<Pair<Float, Float>>,
        val m2: List<Pair<Float, Float>>, val m3: List<Pair<Float, Float>>, val dots: List<Dot>)

    /**
     * Geometry for the chart. xMax grows in 12-month steps (min 12) so a
     * newborn's dots are readable; yMax is an even number ~15% above the
     * highest median in range. Null when there is nothing plottable.
     */
    fun layout(sex: String, dob: String, weights: List<WeightEntry>, table: Table): Layout? {
        if (!table.ok) return null
        val rows = table.rowsFor(sex)
        val dots = weights.mapNotNull { w ->
            val m = ageMonths(dob, w.date)
            if (m < 0 || !w.kg.isFinite() || w.kg <= 0) return@mapNotNull null
            val z = zone(sex, m, w.kg, table) ?: return@mapNotNull null
            Triple(m, w, z)
        }.sortedBy { it.first }
        if (dots.isEmpty()) return null
        val xMax = minOf(60, maxOf(12, ceil((dots.maxOf { it.first } + 1) / 12.0).toInt() * 12))
        val topMedian = (0..xMax).maxOf { rows[it].median }
        val yMax = ceil(topMedian * 1.15 / 2.0) * 2.0
        fun px(m: Int) = m.toFloat() / xMax
        fun py(kg: Double) = (1.0 - kg / yMax).toFloat()
        val median = (0..xMax).map { px(it) to py(rows[it].median) }
        val m2 = (0..xMax).map { px(it) to py(rows[it].m2) }
        val m3 = (0..xMax).map { px(it) to py(rows[it].m3) }
        return Layout(xMax, yMax, median, m2, m3,
            dots.map { (m, w, z) -> Dot(px(m), py(w.kg), m, w.kg, z, w.date) })
    }

    /**
     * The fell/gained line for the chart, reusing the existing fixed rule
     * [ArogyaOfficialForms.weightNote] on the last two recorded weights.
     */
    fun weightNote(weights: List<WeightEntry>): String? {
        val sorted = weights.sortedBy { it.date }
        if (sorted.size < 2) return null
        val p = sorted[sorted.size - 2]; val now = sorted.last()
        return ArogyaOfficialForms.weightNote(
            mapOf("weight" to ("${p.kg}" to p.date)), mapOf("weight" to "${now.kg}"))
    }

    /**
     * ANC trend across ANC 1..5 (5th under PMSMA): "Weight 45 -> 47 -> 50 kg (+5); Hb 10.2 -> 11 (+0.8)".
     * A series needs 2+ numeric values to appear; "" when there is no trend.
     */
    fun ancTrend(details: Map<Int, AncDetail>): String {
        fun num(s: String) = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "$v"
        fun series(label: String, vals: List<Double>, unit: String): String? {
            if (vals.size < 2) return null
            val d = Math.round((vals.last() - vals.first()) * 10) / 10.0
            val ds = (if (d >= 0) "+" else "") + fmt(d)
            return "$label " + vals.joinToString(" → ", transform = ::fmt) +
                (if (unit.isBlank()) "" else " $unit") + " ($ds)"
        }
        val ws = (1..5).mapNotNull { details[it]?.weightKg?.let(::num) }
        val hs = (1..5).mapNotNull { details[it]?.hb?.let(::num) }
        return listOfNotNull(series("Weight", ws, "kg"), series("Hb", hs, "")).joinToString("; ")
    }

    /**
     * Inline SVG of the growth chart for the MCP card PDF (WebView renders it
     * for preview and print). Zone bands (dark green/yellow/orange) + reference
     * curves + the child's dots joined by a line; pink border for girls, blue
     * for boys. "" when unplottable.
     */
    fun svg(sex: String, dob: String, weights: List<WeightEntry>, table: Table,
        width: Int = 520, height: Int = 300): String {
        val l = layout(sex, dob, weights, table) ?: return ""
        val ml = 44.0; val mr = 10.0; val mt = 10.0; val mb = 26.0
        val pw = width - ml - mr; val ph = height - mt - mb
        fun X(x: Float) = ml + x * pw
        fun Y(y: Float) = mt + y * ph
        fun pts(curve: List<Pair<Float, Float>>) =
            curve.joinToString(" ") { "${"%.1f".format(X(it.first))},${"%.1f".format(Y(it.second))}" }
        fun band(curve: List<Pair<Float, Float>>): String {
            val top = pts(curve)
            val first = curve.first(); val last = curve.last()
            return "$top ${"%.1f".format(X(last.first))},${"%.1f".format(mt + ph)} " +
                "${"%.1f".format(X(first.first))},${"%.1f".format(mt + ph)}"
        }
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">")
        val border = if (sex.equals("M", true) || sex.equals("Male", true)) "#1e88e5" else "#ec407a"
        sb.append("<rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" fill=\"#ffffff\" stroke=\"$border\" stroke-width=\"3\"/>")
        sb.append("<rect x=\"$ml\" y=\"$mt\" width=\"$pw\" height=\"$ph\" fill=\"#e6f4ea\"/>")
        sb.append("<polygon points=\"${band(l.m2)}\" fill=\"#fef7e0\"/>")
        sb.append("<polygon points=\"${band(l.m3)}\" fill=\"#fde8d8\"/>")
        // Y gridlines: 0, quarters, max.
        for (i in 0..4) {
            val v = l.yMax * i / 4.0
            val y = mt + ph - (v / l.yMax) * ph
            sb.append("<line x1=\"$ml\" y1=\"${"%.1f".format(y)}\" x2=\"${"%.1f".format(ml + pw)}\" " +
                "y2=\"${"%.1f".format(y)}\" stroke=\"#ccc\" stroke-width=\"0.5\"/>")
            sb.append("<text x=\"${"%.1f".format(ml - 4)}\" y=\"${"%.1f".format(y + 3)}\" font-size=\"9\" " +
                "text-anchor=\"end\">${"%.1f".format(v)}</text>")
        }
        // X labels: 0, mid, max months.
        for (m in listOf(0, l.xMax / 2, l.xMax)) {
            val x = ml + m.toDouble() / l.xMax * pw
            sb.append("<text x=\"${"%.1f".format(x)}\" y=\"${height - 8}\" font-size=\"9\" " +
                "text-anchor=\"middle\">${m}m</text>")
        }
        sb.append("<text x=\"${ml + pw}\" y=\"${height - 8}\" font-size=\"9\" text-anchor=\"end\">WHO weight-for-age</text>")
        sb.append("<polyline points=\"${pts(l.median)}\" fill=\"none\" stroke=\"#137333\" stroke-width=\"1.5\"/>")
        sb.append("<polyline points=\"${pts(l.m2)}\" fill=\"none\" stroke=\"#f9ab00\" stroke-width=\"1\" stroke-dasharray=\"4,2\"/>")
        sb.append("<polyline points=\"${pts(l.m3)}\" fill=\"none\" stroke=\"#e8710a\" stroke-width=\"1\" stroke-dasharray=\"4,2\"/>")
        if (l.dots.size > 1) sb.append("<polyline points=\"${l.dots.joinToString(" ") {
            "${"%.1f".format(X(it.x))},${"%.1f".format(Y(it.y))}" }}\" " +
            "fill=\"none\" stroke=\"#1a73e8\" stroke-width=\"1.5\"/>")
        l.dots.forEach { d ->
            val c = when (d.zone) { Zone.GREEN -> "#137333"; Zone.YELLOW -> "#f9ab00"; Zone.RED -> "#e8710a" }
            sb.append("<circle cx=\"${"%.1f".format(X(d.x))}\" cy=\"${"%.1f".format(Y(d.y))}\" r=\"4\" " +
                "fill=\"$c\" stroke=\"#fff\" stroke-width=\"1\"><title>${d.date}: ${d.kg} kg</title></circle>")
        }
        sb.append("<text x=\"$ml\" y=\"${height - 8}\" font-size=\"9\">kg</text>")
        sb.append("</svg>")
        return sb.toString()
    }
}
