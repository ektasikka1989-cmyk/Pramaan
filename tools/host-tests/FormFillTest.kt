import com.example.arfas.*

private var n = 0; private var fails = 0
private fun check(name: String, ok: Boolean, info: Any? = null) { if (ok) { n++; println("PASS $name") } else { fails++; println("FAIL $name ${info ?: ""}") } }

fun main() {
    val F = ArogyaOfficialForms
    // Long forms: only the rows the note talks about go to the phone model.
    val rel = F.relevantFields(F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN).map { it.id }
    check("anc-relevant-small", rel.size in 6..24, rel)
    check("anc-relevant-has-core", listOf("weight", "bp", "hb", "ifa", "calcium", "d_bleed", "d_headache", "d_fever", "pog").all { it in rel }, rel)
    check("anc-prompt-short", F.prompt(F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN).length < 2600, F.prompt(F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN).length)
    check("short-form-keeps-all", F.relevantFields(F.SICK, 0, "x").size == F.fieldsFor(F.SICK, 0).size)
    // No model at all (worst case on the phone): fixed rules still fill the form from her words.
    fun v(m: Map<String, FilledValue>) = m.mapValues { it.value.value }
    val w1 = v(F.parse("", F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN))
    check("w1-rules", w1["weight"] == "49" && w1["bp"] == "110/70" && w1["hb"] == "10.2" && w1["pog"] == "10" && w1["height"] == "152" && w1["pulse"] == "82" &&
        w1["td"] == "Td-1" && w1["urine_albumin"] == "Nil" && w1["urine_sugar"] == "Nil" && w1["blood_group"] == "B+" && w1["hiv"] == "Yes" && w1["gravida"] == "1" &&
        w1["pallor"] == "No" && w1["oedema"] == "No" && w1["delivery_institution"] == "CHC Mishrikh" && w1["complaints"] == "mild nausea" &&
        w1["ifa"] == null && w1["calcium"] == null &&
        w1["d_bleed"] == "No" && w1["d_headache"] == "No" && w1["d_fever"] == "No", w1)
    val w1h = v(F.parse("", F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_HI))
    check("w1-hindi-rules", w1h["weight"] == "49" && w1h["bp"] == "110/70" && w1h["hb"] == "10.2" && w1h["pog"] == "10" && w1h["height"] == "152" && w1h["pulse"] == "82" &&
        w1h["td"] == "Td-1" && w1h["urine_albumin"] == "Nil" && w1h["urine_sugar"] == "Nil" && w1h["blood_group"] == "B+" && w1h["hiv"] == "Yes" && w1h["gravida"] == "1" &&
        w1h["pallor"] == "No" && w1h["oedema"] == "No" && w1h["delivery_institution"] == "CHC मिश्रिख" &&
        w1h["ifa"] == null && w1h["calcium"] == null &&
        w1h["d_bleed"] == "No" && w1h["d_headache"] == "No" && w1h["d_fever"] == "No", w1h)
    val w2 = v(F.parse("", F.ANNEX9, 2, ArogyaDemoScript.NOTE_EN))
    check("w2-rules", w2["weight"] == "2.65" && w2["temp"] == "38.4" && w2["m_temp"] == "37" && w2["m_meals"] == "4" && w2["m_pads"] == "3" &&
        w2["s_feed"] == "Yes" && w2["s_cry"] == "Yes" && w2["warm"] == "Yes" && w2["eyes"] == "No" && w2["s_umbilicus"] == "No", w2)
    val w2h = v(F.parse("", F.ANNEX9, 2, ArogyaDemoScript.NOTE_HI))
    check("w2-hindi-rules", w2h["weight"] == "2.65" && w2h["temp"] == "38.4" && w2h["m_temp"] == "37" && w2h["m_meals"] == "4" && w2h["m_pads"] == "3" &&
        w2h["s_feed"] == "Yes" && w2h["s_cry"] == "Yes" && w2h["eyes"] == "No" && w2h["s_umbilicus"] == "No", w2h)
    check("w2-danger-by-rules", F.dangers(F.ANNEX9, w2).any { it.contains("38.4") } && F.dangers(F.ANNEX9, w2).any { it.contains("Feeding less") }, F.dangers(F.ANNEX9, w2))
    val w3 = v(F.parse("", F.SICK, 0, ArogyaDemoScript.NOTE_W3_EN))
    check("w3-rules", w3["diarrhoea"] == "Yes" && w3["temp"] == "37.2" && w3["ors"] == "4" && w3["zinc"] == "14" && w3["g_drink"] == "No" &&
        w3["g_vomit"] == "No" && w3["g_fits"] == "No" && w3["g_lethargic"] == "No" && w3["fast_breath"] == "No" && w3["referred"] == "No", w3)
    val w3h = v(F.parse("", F.SICK, 0, ArogyaDemoScript.NOTE_W3_HI))
    check("w3-hindi-rules", w3h["diarrhoea"] == "Yes" && w3h["temp"] == "37.2" && w3h["ors"] == "4" && w3h["zinc"] == "14" && w3h["g_vomit"] == "No" &&
        w3h["g_fits"] == "No" && w3h["g_lethargic"] == "No" && w3h["referred"] == "No", w3h)
    check("w3-no-danger", F.dangers(F.SICK, w3).isEmpty(), F.dangers(F.SICK, w3))
    // Every rule value carries words that are really in the note.
    val all = F.parse("", F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN).values + F.parse("", F.ANNEX9, 2, ArogyaDemoScript.NOTE_EN).values
    check("rule-evidence-in-note", all.all { it.source != "rule" || (ArogyaDemoScript.NOTE_W1_EN + " " + ArogyaDemoScript.NOTE_EN).contains(it.evidence) }, all.map { it.evidence })
    // A reply cut off by the token budget still gives the complete fields before the cut.
    val cut = """{"fields":[{"id":"weight","value":"49 kg","evidence":"weight 49 kg"},{"id":"hb","value":"10.2","evidence":"Hb 10.2"},{"id":"bp","value":"110/7"""
    val pc = F.parse(cut, F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN)
    check("partial-rescue", pc["weight"]?.source == "ai" && pc["hb"]?.source == "ai" && pc["bp"]?.value == "110/70", pc.mapValues { "${it.value.value}/${it.value.source}" })
    // The model wins where it answered; rules only fill gaps.
    val ai = F.parse("""{"fields":[{"id":"weight","value":"49","evidence":"weight 49 kg"}]}""", F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN)
    check("ai-wins", ai["weight"]?.source == "ai" && ai["bp"]?.source == "rule")
    // Nothing invented: a note without these words fills nothing by rules.
    check("no-invention", F.ruleValues(F.MCP_ANC, 0, "Visited the house, family was away.").isEmpty())
    check("negation-not-flipped", F.ruleValues(F.SICK, 0, "Loose stools. No loose stools after ORS? not sure").let { it["diarrhoea"]?.value == "Yes" })
    println("FormFillTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
