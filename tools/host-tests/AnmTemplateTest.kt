import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // Part B5: fixed ANM template from the records.
    val baby = Beneficiary(id = "b1", name = "Baby of Neha", relativeName = "Neha", village = "Rampur",
        phone = "9811111111", household = "H12", sex = "F", category = BenCategory.CHILD,
        dob = "2026-10-28", birthWeightKg = 2.4, preterm = true,
        mcp = McpCard(vaccines = mapOf("BCG" to "2026-10-29"),
            weights = listOf(WeightEntry("2026-10-29", 2.4), WeightEntry("2026-11-02", 2.6))))
    val visit = ArogyaVisit(id = "v1", personId = "b1", name = "Baby of Neha", village = "Rampur",
        kind = ArogyaKind.NEWBORN, date = "2026-11-03", birthDate = "2026-10-28",
        notes = "Baby has fever and poor feeding.",
        captureItems = listOf(ArogyaCaptureItem("Observation", "Baby has fever.", false)),
        referralNote = "Gave ORS. Advised to keep warm.",
        nextDate = "2026-11-05", nextReason = "Follow-up",
        guideLog = listOf(ArogyaGuideEntry(at = 1, question = "Fever?", answer = "Refer.",
            quote = "Fever in a newborn is a danger sign.",
            sources = listOf(ArogyaSource("1", "HBYC Handbook", "p.63", "...", "")))))
    val tpl = ArogyaAnmTemplate.build(ArogyaAnmTemplate.Input(visit = visit, person = baby,
        concern = "Not feeding since morning.", ashaName = "Sunita", ashaVillage = "Rampur",
        anmName = "Anita", givenMeds = listOf("ORS x4"), dateTime = "2026-11-03, 10:00"))
    check("head-urgent", "URGENT referral" in tpl)
    check("head-to-from", "To: Anita" in tpl && "From: ASHA Sunita, Rampur" in tpl && "2026-11-03, 10:00" in tpl)
    check("person", "PERSON: Baby of Neha" in tpl && "newborn day 7" in tpl)
    check("household", "H12" in tpl && "Neha" in tpl && "9811111111" in tpl)
    check("why", "WHY I AM WRITING:" in tpl && "fever / feels hot" in tpl && "Not feeding since morning" in tpl)
    check("seen", "SEEN TODAY: Baby has fever." in tpl && "Weight gained" in tpl)
    check("history", "HISTORY:" in tpl && "Birth weight 2.4" in tpl && "LBW" in tpl && "Preterm" in tpl && "BCG (2026-10-29)" in tpl)
    check("done", "WHAT I HAVE DONE: ORS x4" in tpl && "Gave ORS" in tpl)
    check("guideline", "GUIDELINE:" in tpl && "Fever in a newborn is a danger sign." in tpl && "HBYC Handbook, p.63" in tpl)
    check("need", "WHAT I NEED FROM YOU: Please visit today." in tpl)
    check("next", "Next visit planned: 2026-11-05" in tpl)
    // Omit-empty: no data, no line, never n/a.
    val bare = ArogyaAnmTemplate.build(ArogyaAnmTemplate.Input(
        visit = ArogyaVisit(name = "X", date = "2026-11-03"), dateTime = "2026-11-03"))
    check("omit", "n/a" !in bare.lowercase() && "Household:" !in bare && "HISTORY:" !in bare &&
        "WHAT I HAVE DONE:" !in bare && "GUIDELINE:" !in bare && "WHY I AM WRITING:" !in bare)
    check("bare-need", "WHAT I NEED FROM YOU: Please advise." in bare)
    // Hindi template.
    val htpl = ArogyaAnmTemplate.build(ArogyaAnmTemplate.Input(visit = visit, person = baby,
        concern = "Fever.", ashaName = "Sunita", anmName = "Anita", dateTime = "2026-11-03", hindi = true))
    check("hindi", "तुरंत रेफ़रल" in htpl && "व्यक्ति:" in htpl && "लिखने का कारण:" in htpl && "आपसे क्या चाहिए:" in htpl)
    // Polish verification.
    val signs = ArogyaAnmTemplate.signNames(tpl, visit, false)
    check("verify-pass", ArogyaAnmTemplate.verifyPolished(tpl, tpl, signs) == null)
    check("verify-number", (ArogyaAnmTemplate.verifyPolished(tpl, tpl.replace("2.4", ""), signs) ?: "").startsWith("dropped numbers"))
    check("verify-sign", (ArogyaAnmTemplate.verifyPolished(tpl, tpl.replace("fever / feels hot", "unwell"), signs) ?: "").startsWith("dropped danger sign"))
    // Tokenize round-trip: identifiers leave the cloud prompt, come back for display.
    val (tok, used) = ArogyaAnmTemplate.tokenize(tpl, listOf("Baby of Neha", "Rampur", "9811111111", "Neha", "H12"))
    check("tok-hidden", "Neha" !in tok && "Rampur" !in tok && "9811111111" !in tok && "[P1]" in tok)
    check("tok-back", ArogyaAnmTemplate.detokenize(tok, used) == tpl)
    check("verify-tok", (ArogyaAnmTemplate.verifyPolished(tok, tok.replace("[P1]", "[P1x]"),
        ArogyaAnmTemplate.signNames(tok, visit, false), used.mapIndexed { i, _ -> "[P${i + 1}]" }) ?: "").startsWith("dropped placeholder"))
    // Refill + prompt.
    val ref = ArogyaAnmTemplate.refillMessage("Sunita", "Rampur", "", "Anita", "2026-11-03", "- ORS: 8 (min 20)", false)
    check("refill", "refill request" in ref && "From: ASHA Sunita, Rampur" in ref && "- ORS: 8 (min 20)" in ref)
    val long = "Take rest. ".repeat(400)
    val pr = ArogyaAiExtras.anmPrompt(long, false)
    check("prompt-polish", "Keep every fact" in pr && pr.contains(long))
    println("anmtemplate: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
