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
    // 2. Script completeness (three workflows): EN + HI everywhere, no blanks, Aim on every screen.
    val all = ArogyaDemoScript.workflows.flatMap { it.scenes }
    d(ArogyaDemoScript.workflows.map { it.id } == listOf(1, 2, 3), "three workflows, picked on the demo home screen")
    d(all.all { it.id.isNotBlank() && it.title.isNotBlank() && it.hi.isNotBlank() }, "id + titles everywhere")
    d(all.all { it.narration.size == 3 && it.narrationHi.size == 3 }, "three short sentences per screen, both languages")
    d(all.flatMap { it.narration + it.narrationHi }.all { it.isNotBlank() }, "no blank sentences")
    d(all.all { it.aim.isNotBlank() && it.aimHi.isNotBlank() }, "every screen states its aim first")
    d(all.all { it.screen.isNotBlank() && it.screenHi.isNotBlank() }, "every scene has an app-screen heading")
    d(ArogyaDemoScript.workflows.all { w -> w.scenes.map { it.id }.toSet().size == w.scenes.size }, "scene ids unique within a workflow")
    d(ArogyaDemoScript.workflows.all { w -> w.scenes.flatMap { it.narration }.count { it.contains("confirm", ignoreCase = true) } <= 1 }, "'she confirms' said once per workflow")
    d(ArogyaDemoScript.flow(1).map { it.id } == listOf("plan", "addperson", "form", "voice", "danger", "sops", "explain", "saved", "mcpcard", "practice", "summary"), "workflow 1: 11 screens")
    d(ArogyaDemoScript.flow(2).map { it.id } == listOf("plan", "card", "form", "voice", "danger", "anm", "sops", "explain", "saved", "mcpcard", "practice", "summary"), "workflow 2: 12 screens")
    d(ArogyaDemoScript.flow(3).map { it.id } == listOf("plan", "form", "voice", "danger", "sops", "explain", "saved", "refill", "medscan", "practice", "summary"), "workflow 3: 11 screens")
    d(ArogyaDemoScript.workflows.all { w -> listOf("plan", "voice", "danger", "sops", "explain", "saved", "practice", "summary").all { id -> w.scenes.any { it.id == id } } },
        "every workflow shows plan, voice, danger, Ask SOP, explain, save, practice, summary")
    d(ArogyaDemoScript.flow(1)[0].narration[0] == "Sunita is the ASHA for Rampur, a village of about 1,000 people.", "owner item 1: intro sentence")
    d(ArogyaDemoScript.flow(1)[0].narration[2] == "These visits follow the fixed schedules in the government guidelines.", "owner item 2: schedules sentence")
    d((1..3).all { ArogyaDemoScript.question(it, false).endsWith("?") && ArogyaDemoScript.question(it, true).endsWith("?") && ArogyaDemoScript.followUp(it, false).isNotBlank() }, "an Ask question per workflow")
    // Danger check by fixed rules on her words (never AI): W2 refers, W1 and W3 do not.
    val today = java.time.LocalDate.now()
    val v2 = ArogyaVisit(kind = ArogyaKind.NEWBORN, date = today.toString(), birthDate = today.minusDays(6).toString())
    val dl2 = ArogyaDemoScript.dangerLines(ArogyaDemoScript.NOTE_EN, ArogyaOfficialForms.ANNEX9, emptyMap(), v2, false)
    d(dl2.any { it.startsWith("⚠") && it.contains("fever", true) }, "W2 danger by rules even with no AI values: $dl2")
    d(ArogyaDemoScript.dangerLines(ArogyaDemoScript.NOTE_W1_EN, ArogyaOfficialForms.MCP_ANC, emptyMap(), ArogyaVisit(date = today.toString()), false).isEmpty(), "W1 no danger")
    d(ArogyaDemoScript.dangerLines(ArogyaDemoScript.NOTE_W3_EN, ArogyaOfficialForms.SICK, mapOf("temp" to "37.2", "diarrhoea" to "Yes"), ArogyaVisit(kind = ArogyaKind.CHILD, date = today.toString()), false).isEmpty(), "W3 no danger")
    d(ArogyaDemoScript.dangerLines(ArogyaDemoScript.NOTE_W1_EN, ArogyaOfficialForms.MCP_ANC, mapOf("bp" to "150/100"), ArogyaVisit(date = today.toString()), false).any { it.contains("140/90") }, "form rule: BP 150/100 refers")
    d(ArogyaDemoScript.dangerChecked(ArogyaOfficialForms.MCP_ANC, false).isNotEmpty() && ArogyaDemoScript.referralAdvice(dl2).contains("PHC"), "checked list + referral advice")
    d(ArogyaDemoScript.flow(9) == ArogyaDemoScript.flow(2) && ArogyaDemoScript.scenes == ArogyaDemoScript.flow(2), "unknown workflow falls back to 2")
    d(ArogyaDemoScript.GOLD_EN.contains("gaadha doodh") && ArogyaDemoScript.GOLD_HI.contains("गाढ़ा दूध"), "gold scene asks in her own words")
    d(ArogyaDemoScript.flow(2).first { it.id == "mcpcard" }.narration.any { it.contains("official MCP card") }, "MCP scene names the official card")
    d(ArogyaDemoScript.flow(2).first { it.id == "plan" }.narration.any { it.contains("Neha") }, "W2 keeps the hero")
    // Notes carry the numbers the forms need (grounding) and the medicines given.
    d(listOf("49", "110/70", "10.2", "152", "82").all { ArogyaDemoScript.NOTE_W1_EN.contains(it) && ArogyaDemoScript.NOTE_W1_HI.contains(it) }, "W1 note numbers in both languages")
    d(listOf("37.2", "4 ORS", "14").all { ArogyaDemoScript.NOTE_W3_EN.contains(it) } && listOf("37.2", "4 ORS", "14").all { ArogyaDemoScript.NOTE_W3_HI.contains(it) }, "W3 note numbers in both languages")
    val cat = ArogyaRegistryLogic.DRUG_KIT
    val g2 = ArogyaDemoScript.medicinesGiven(ArogyaDemoScript.NOTE_EN, cat)
    d(g2.map { it.item to it.qty }.toSet() == setOf("IFA tablets (adult)" to 30, "Calcium tablets" to 60), "W2 medicines read by rules: $g2")
    val g2h = ArogyaDemoScript.medicinesGiven(ArogyaDemoScript.NOTE_HI, cat)
    d(g2h.map { it.item to it.qty }.toSet() == setOf("IFA tablets (adult)" to 30, "Calcium tablets" to 60), "W2 Hindi medicines read by rules: $g2h")
    d(ArogyaDemoScript.medicinesGiven("Baby weight 2650 grams. Temperature 38.4.", cat).isEmpty(), "no medicine without 'gave'")
    d(ArogyaDemoScript.note(1, false) == ArogyaDemoScript.NOTE_W1_EN && ArogyaDemoScript.note(3, true) == ArogyaDemoScript.NOTE_W3_HI && ArogyaDemoScript.note(2, false) == ArogyaDemoScript.NOTE_EN, "note per workflow")
    d(ArogyaDemoScript.QUESTION_EN.contains("cord stump") && ArogyaDemoScript.QUESTION_HI.contains("नाल"),
        "Ask SOP scene uses the slide example")
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
    val nt = listOf(ArogyaAiTick("family explanation", "परिवार को समझाना", "M", 1000, false),
        ArogyaAiTick("add person from photo", "फ़ोटो से नया व्यक्ति", "M", 2000, false))
    val nr = ArogyaDemoTicks.rows(nt, false, false)
    d(nr.size == 2 && nr[0].benefit == "The family understands in Hindi" && nr[1].benefit == "A photo registers the newcomer",
        "new scenes have their own benefit lines")
    val gr = ArogyaDemoTicks.rows(listOf(ArogyaAiTick("gold-layer answer", "गोल्ड उत्तर", "M", 3000, false)), false, false)
    d(gr.size == 1 && gr[0].benefit == "Ask in her own words, get a cited answer", "gold scene has its own benefit line")
    val nba = ArogyaDemoTicks.beforeAfter(nt, false)
    d(nba.size == 2 && nba[0].afterMs == 1000L && nba[1].afterMs == 2000L, "new scenes measured in before/after")
    // 4. Before/after uses measured times only.
    d(ArogyaDemoTicks.beforeAfter(emptyList(), false).isEmpty(), "nothing measured -> no rows, never invented")
    val ba = ArogyaDemoTicks.beforeAfter(
        listOf(ArogyaAiTick("form filling + evidence check", "फ़ॉर्म", "M", 12000, false)), false)
    d(ba.size == 1 && ba[0].afterMs == 12000L, "measured task appears with its time")
    d(ba[0].before.contains("not timed"), "paper side always says not timed")
    d(ArogyaDemoTicks.beforeAfter(ticks, true).isEmpty(), "unmeasured tasks stay out even in Hindi")
    // 5. Demo UI pass: the AI tools table (catalogue + what ran) and the impact table.
    val tr = ArogyaDemoTicks.toolRows(ticks, dangerRan = true)
    d(tr.size == ArogyaDemoTicks.tools.size, "every catalogue feature is a row")
    d(tr.first { it.tool.feature == "Speech to text" }.let { it.ran && it.ranMs == 9000L && it.ranCloud == false }, "a tick marks its feature as ran today")
    d(tr.first { it.tool.feature == "Drug-kit count" }.let { it.ran && it.ranCloud == true && it.tool.metaOnline }, "SAM count is Meta, online")
    d(tr.first { it.tool.rule }.let { it.ran && it.ranCloud == null && !it.tool.metaOffline && !it.tool.metaOnline }, "danger rules: ran, never AI, never Meta")
    d(tr.first { it.tool.feature == "Daily practice questions" }.ran.not(), "untouched feature is not marked as ran")
    d(ArogyaDemoTicks.tools.filter { it.phoneOnly }.all { !it.metaOnline && it.online.contains("phone") }, "card + person reading stay on the phone")
    d(ArogyaDemoTicks.tools.none { it.offline.contains("Whisper") && it.metaOffline }, "Whisper is never labelled Meta")
    val (mOff, mOn) = ArogyaDemoTicks.metaCounts()
    d(mOff >= 5 && mOn >= 6, "Meta tools counted: $mOff offline, $mOn online")
    val mem = ArogyaDemoTicks.timesRecord(ArogyaDemoTicks.timesRecord(emptyMap(),
        ArogyaAiTick("form filling + evidence check", "f", "M", 4000, true)), ArogyaAiTick("form filling + evidence check", "f", "M", 60000, false))
    d(mem["form filling + evidence check"] == (4000L to 60000L), "times kept per mode")
    d(ArogyaDemoTicks.timesDecode(ArogyaDemoTicks.timesEncode(mem)) == mem, "times round-trip")
    d(ArogyaDemoTicks.timesRecord(mem, ArogyaAiTick("x", "x", "M", 0, true)) == mem, "unmeasured tick is not recorded")
    d(ArogyaDemoTicks.timesDecode("garbage").isEmpty(), "bad times string decodes to nothing")
    val imp = ArogyaDemoTicks.impact(listOf(ArogyaAiTick("form filling + evidence check", "f", "M", 5000, true)), mem)
    d(imp.size == 1 && imp[0].todayMs == 5000L && imp[0].onlineMs == 4000L && imp[0].offlineMs == 60000L && imp[0].task.handMin > 0, "impact row: today + online + offline + hand estimate")
    d(ArogyaDemoTicks.impact(emptyList(), emptyMap()).isEmpty(), "impact: nothing measured, no rows")
    d(ArogyaDemoTicks.handTotal(imp) == imp[0].task.handMin, "hand total sums the shown rows")
    println("DemoTourTest: $dp passed, $df failed")
    if (df > 0) kotlin.system.exitProcess(1)
}
