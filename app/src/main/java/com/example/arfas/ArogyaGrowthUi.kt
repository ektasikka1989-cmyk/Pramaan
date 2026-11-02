package com.example.arfas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Gap round item 4: on-screen growth chart. Draws the same WHO weight-for-age
 * geometry as [ArogyaGrowth] (green/yellow/red zones + reference curves) with
 * the child's weights as dots joined by a line. Tap a dot for date + kg.
 * Fixed rules only — no AI. Hindi + English.
 */
@Composable
fun GrowthChartCard(hindi: Boolean, sex: String, dob: String, weights: List<WeightEntry>) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val table = remember {
        runCatching {
            ArogyaGrowth.parseTable(context.assets.open(ArogyaGrowth.ASSET).bufferedReader().readText())
        }.getOrDefault(ArogyaGrowth.Table(emptyList(), emptyList()))
    }
    val layout = remember(sex, dob, weights, table) { ArogyaGrowth.layout(sex, dob, weights, table) }
    if (layout == null) {
        if (weights.isNotEmpty() && dob.isBlank())
            Text(tr("Add the date of birth to see the growth chart.", "विकास चार्ट के लिए जन्मतिथि जोड़ें।"),
                style = MaterialTheme.typography.bodySmall)
        return
    }
    var selected by remember(weights) { mutableStateOf<ArogyaGrowth.Dot?>(null) }
    val green = Color(0xFF188038); val yellow = Color(0xFFE37400); val red = Color(0xFFD93025)
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Growth chart — weight for age", "विकास चार्ट — उम्र के अनुसार वज़न"),
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("⚙ " + tr("WHO standards", "WHO मानक"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        val chartH = 220.dp
        val chartHpx = with(density) { chartH.toPx() }
        Row {
            Column(Modifier.height(chartH).padding(end = 2.dp), verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End) {
                Text("${"%.0f".format(layout.yMax)}", style = MaterialTheme.typography.labelSmall)
                Text("kg", style = MaterialTheme.typography.labelSmall)
                Text("0", style = MaterialTheme.typography.labelSmall)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val wpx = with(density) { maxWidth.toPx() }
                val pad = with(density) { 10.dp.toPx() }
                fun px(x: Float) = pad + x * (wpx - 2 * pad)
                fun py(y: Float) = pad + y * (chartHpx - 2 * pad)
                val dotsPx = remember(layout, wpx) { layout.dots.map { Offset(px(it.x), py(it.y)) } }
                Canvas(Modifier.fillMaxWidth().height(chartH).pointerInput(dotsPx) {
                    detectTapGestures { tap ->
                        val i = dotsPx.indices.minByOrNull { hypot((dotsPx[it] - tap).x, (dotsPx[it] - tap).y) }
                        selected = if (i != null && hypot((dotsPx[i] - tap).x, (dotsPx[i] - tap).y) < with(density) { 28.dp.toPx() })
                            layout.dots[i] else null
                    }
                }) {
                    fun pts(curve: List<Pair<Float, Float>>) = curve.map { Offset(px(it.first), py(it.second)) }
                    fun band(curve: List<Pair<Float, Float>>, color: Color) {
                        val p = pts(curve)
                        val path = Path().apply {
                            moveTo(p.first().x, p.first().y)
                            p.drop(1).forEach { lineTo(it.x, it.y) }
                            lineTo(p.last().x, size.height - pad)
                            lineTo(p.first().x, size.height - pad)
                            close()
                        }
                        drawPath(path, color)
                    }
                    drawRect(Color(0xFFE6F4EA))
                    band(layout.m2, Color(0xFFFEF7E0))
                    band(layout.m3, Color(0xFFFCE8E6))
                    fun curve(curve: List<Pair<Float, Float>>, color: Color) {
                        val p = pts(curve)
                        val path = Path().apply {
                            moveTo(p.first().x, p.first().y); p.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                    }
                    curve(layout.median, green)
                    curve(layout.m2, yellow)
                    curve(layout.m3, red)
                    if (dotsPx.size > 1) {
                        val path = Path().apply {
                            moveTo(dotsPx.first().x, dotsPx.first().y)
                            dotsPx.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, Color(0xFF1A73E8),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                    }
                    dotsPx.forEachIndexed { i, o ->
                        val c = when (layout.dots[i].zone) {
                            ArogyaGrowth.Zone.GREEN -> green
                            ArogyaGrowth.Zone.YELLOW -> yellow
                            ArogyaGrowth.Zone.RED -> red
                        }
                        drawCircle(c, radius = with(density) { 6.dp.toPx() }, center = o)
                        drawCircle(Color.White, radius = with(density) { 6.dp.toPx() },
                            center = o, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        if (layout.dots[i] == selected)
                            drawCircle(Color.Black, radius = with(density) { 9.dp.toPx() },
                                center = o, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0m", style = MaterialTheme.typography.labelSmall)
            Text("${layout.xMax / 2}m", style = MaterialTheme.typography.labelSmall)
            Text("${layout.xMax}m", style = MaterialTheme.typography.labelSmall)
        }
        Text(selected?.let { "${it.date} · ${it.kg} kg · " + when (it.zone) {
            ArogyaGrowth.Zone.GREEN -> tr("normal", "सामान्य")
            ArogyaGrowth.Zone.YELLOW -> tr("below −2 SD — tell the ANM", "−2 SD से कम — ANM को बताएँ")
            ArogyaGrowth.Zone.RED -> tr("below −3 SD — refer", "−3 SD से कम — रेफर करें")
        } } ?: tr("Tap a dot for the date and weight.", "तारीख़ और वज़न के लिए बिंदु दबाएँ।"),
            style = MaterialTheme.typography.bodySmall, fontWeight = if (selected != null) FontWeight.Bold else null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("● " + tr("Normal", "सामान्य") to green,
                "● " + tr("Below −2 SD", "−2 SD से कम") to yellow,
                "● " + tr("Below −3 SD", "−3 SD से कम") to red).forEach { (t, c) ->
                Text(t, style = MaterialTheme.typography.labelSmall, color = c)
            }
        }
        ArogyaGrowth.weightNote(weights)?.let {
            Text("⚖ $it", style = MaterialTheme.typography.bodySmall,
                color = if (it.contains("fell")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

/** ANC 1..4 weight + Hb trend line for a pregnant woman's page (fixed rule, no AI). */
@Composable
fun AncTrendLine(hindi: Boolean, details: Map<Int, AncDetail>) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val t = remember(details) { ArogyaGrowth.ancTrend(details) }
    if (t.isNotBlank()) Text(tr("Trend: ", "रुझान: ") + t, style = MaterialTheme.typography.bodySmall)
}
