import com.example.arfas.*
import com.example.arfas.MetaVisionMath.Box

// Offline counting: de-duplication (NMS) + dot editing (pure logic).
var cf = 0; var cp = 0
fun c(c: Boolean, m: String) { if (c) { cp++; println("PASS $m") } else { cf++; println("FAIL $m") } }

fun main() {
    // 1. NMS: highest score wins, overlaps dropped, reading order out.
    val dup = listOf(Box(0.1f, 0.1f, 0.2f, 0.2f), Box(0.12f, 0.12f, 0.2f, 0.2f))
    c(MetaVisionMath.nms(dup, listOf(0.9f, 0.7f)) == listOf(0), "duplicate boxes -> best kept")
    c(MetaVisionMath.nms(dup, listOf(0.7f, 0.9f)) == listOf(1), "best wins whatever the order")
    val apart = listOf(Box(0.05f, 0.05f, 0.1f, 0.1f), Box(0.7f, 0.7f, 0.1f, 0.1f))
    c(MetaVisionMath.nms(apart, listOf(0.6f, 0.6f)) == listOf(0, 1), "separate items all kept")
    val chain = listOf(Box(0f, 0f, 0.3f, 0.3f), Box(0.1f, 0f, 0.3f, 0.3f), Box(0.6f, 0f, 0.3f, 0.3f))
    c(MetaVisionMath.nms(chain, listOf(0.9f, 0.8f, 0.7f)) == listOf(0, 2), "chain: middle suppressed, far kept")
    val rows = listOf(Box(0.7f, 0.7f, 0.1f, 0.1f), Box(0.1f, 0.1f, 0.1f, 0.1f), Box(0.5f, 0.1f, 0.1f, 0.1f))
    c(MetaVisionMath.nms(rows, listOf(0.9f, 0.8f, 0.7f)) == listOf(1, 2, 0), "kept boxes numbered in reading order")
    c(MetaVisionMath.nms(emptyList(), emptyList()).isEmpty(), "empty in -> empty out")
    c(runCatching { MetaVisionMath.nms(listOf(Box(0f, 0f, 0.1f, 0.1f)), emptyList()) }.isFailure, "size mismatch rejected")
    // 2. Dots from boxes.
    val dots = ArogyaCountDots.fromBoxes(listOf(Box(0.1f, 0.2f, 0.2f, 0.2f), Box(0.9f, 0.9f, 0.3f, 0.3f)))
    c(dots == listOf(CountDot(0.2f, 0.3f), CountDot(1f, 1f)), "dot centers clamped: $dots")
    // 3. Tap editing.
    val one = ArogyaCountDots.toggle(emptyList(), 0.5f, 0.5f)
    c(one == listOf(CountDot(0.5f, 0.5f)), "tap empty space adds a dot")
    c(ArogyaCountDots.toggle(one, 0.51f, 0.51f).isEmpty(), "tap on a dot removes it")
    c(ArogyaCountDots.toggle(one, 0.9f, 0.9f).size == 2, "tap far away adds another")
    val two = listOf(CountDot(0.2f, 0.2f), CountDot(0.8f, 0.8f))
    c(ArogyaCountDots.toggle(two, 0.21f, 0.2f) == listOf(CountDot(0.8f, 0.8f)), "nearest dot removed, other kept")
    c(ArogyaCountDots.toggle(emptyList(), 1.5f, -0.5f) == listOf(CountDot(1f, 0f)), "taps clamped into the photo")
    val full = List(ArogyaCountDots.MAX) { CountDot(0.01f * (it % 100), 0.01f * (it / 100)) }
    c(ArogyaCountDots.toggle(full, 0.99f, 0.99f).size == ArogyaCountDots.MAX, "dot count capped at MAX")
    println("CountDotsTest: $cp passed, $cf failed")
    if (cf > 0) kotlin.system.exitProcess(1)
}
