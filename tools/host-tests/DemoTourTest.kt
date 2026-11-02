import com.example.arfas.*

// Part B: presenter navigation, script completeness, ticks tables (pure logic).
var df = 0; var dp = 0
fun d(c: Boolean, m: String) { if (c) { dp++; println("PASS $m") } else { df++; println("FAIL $m") } }

fun main() {
    val n = ArogyaDemoScript.scenes.size
    // 1. Navigation: next / back / end / labels.
    d(ArogyaDemoNav.next(0, n) == 1, "next from first scene")
    d(ArogyaDemoNav.next(n - 1, n) == n, "next from last scene reaches the end screen")
    d(ArogyaDemoNav.next(n, n) == n, "next on end screen stays")
    d(ArogyaDemoNav.back(0) == 0, "back on first scene stays")
    d(ArogyaDemoNav.back(5) == 4, "back moves one scene")
    d(!ArogyaDemoNav.isEnd(n - 1, n) && ArogyaDemoNav.isEnd(n, n), "end screen is index n")
    d(ArogyaDemoNav.label(0, n, false) == "Scene 1 of $n", "EN label")
    d(ArogyaDemoNav.label(0, n, true) == "सीन 1 / $n", "HI label")
    d(ArogyaDemoNav.label(n, n, false) == "Demo complete", "end label")
    // 2. Script completeness: EN + HI everywhere, no blanks.
    d(ArogyaDemoScript.scenes.all { it.id.isNotBlank() && it.title.isNotBlank() && it.hi.isNotBlank() }, "id + titles everywhere")
    d(ArogyaDemoScript.scenes.all { it.narration.size in 3..6 && it.narrationHi.size in 3..6 }, "3-6 sentences per language")
    d(ArogyaDemoScript.scenes.flatMap { it.narration + it.narrationHi }.all { it.isNotBlank() }, "no blank sentences")
    val ids = ArogyaDemoScript.scenes.map { it.id }
    d(ids.containsAll(listOf("meet", "due", "form", "voice", "danger", "guides", "sops", "card", "medscan", "saved", "practice", "setting", "aitable", "beforeafter")),
        "all 14 B5 scenes in order: ${ids.joinToString(",")}")
    // 3. AI table is built only from recorded ticks.
    val ticks = listOf(
        ArogyaAiTick("speech-to-text", "बोल → लिख", "Whisper (on this phone, offline)", 9000, false),
        ArogyaAiTick("SAM 3.1 count", "SAM 3.1 गिनती", "SAM 3.1 (Meta, online)", 4000, true))
    val rows = ArogyaDemoTicks.rows(ticks, dangerRan = true, hindi = false)
    d(rows.size == 3, "two ticks + danger row")
    d(rows[0].step == "speech-to-text" && !rows[0].cloud && rows[0].benefit.isNotBlank(), "phone tick keeps model + benefit")
    d(rows[1].cloud && rows[1].ms == 4000L, "cloud tick keeps time")
    d(rows[2].model.contains("⚙") && rows[2].model.contains("not AI") && !rows[2].cloud, "danger row is fixed rules, never AI")
    d(ArogyaDemoTicks.rows(ticks, dangerRan = false, hindi = false).size == 2, "no danger row when rules never ran")
    d(ArogyaDemoTicks.rows(emptyList(), dangerRan = false, hindi = true).isEmpty(), "skipped steps stay out of the table")
    d(ArogyaDemoTicks.rows(listOf(ArogyaAiTick("mystery", "रहस्य", "X", 1, false)), false, true)[0].benefit.isNotBlank(), "unknown feature still gets a benefit line")
    d(ArogyaDemoTicks.rows(ticks, true, true)[0].step == "बोल → लिख", "Hindi step names")
    // 4. Before/after uses measured times only.
    d(ArogyaDemoTicks.beforeAfter(emptyList(), false).isEmpty(), "nothing measured -> no rows, never invented")
    val ba = ArogyaDemoTicks.beforeAfter(
        listOf(ArogyaAiTick("form filling + evidence check", "फ़ॉर्म", "M", 12000, false)), false)
    d(ba.size == 1 && ba[0].afterMs == 12000L, "measured task appears with its time")
    d(ba[0].before.contains("not timed"), "paper side always says not timed")
    d(ArogyaDemoTicks.beforeAfter(ticks, true).isEmpty(), "unmeasured tasks stay out even in Hindi")
    println("DemoTourTest: $dp passed, $df failed")
    if (df > 0) kotlin.system.exitProcess(1)
}
