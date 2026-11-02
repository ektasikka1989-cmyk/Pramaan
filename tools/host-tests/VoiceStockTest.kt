import com.example.arfas.*
var vf = 0; var vp = 0
fun v(c: Boolean, m: String) { if (c) { vp++; println("PASS $m") } else { vf++; println("FAIL $m") } }
fun main() {
    val cat = ArogyaRegistryLogic.DRUG_KIT_DEFAULTS.map { it.name } + "Calcium tablets"
    fun p(t: String) = ArogyaVoiceStock.ground(ArogyaVoiceStock.parseRules(t, cat), t, cat)
    // The three spec examples
    val r1 = p("received 100 ORS and 60 zinc from the sub-centre")
    v(r1.size == 2, "two lines from one receipt note")
    v(r1.any { it.item == "ORS" && it.qty == 100 && it.op == "receive" }, "ORS 100 received")
    v(r1.any { it.item == "Zinc tablets" && it.qty == 60 && it.op == "receive" }, "zinc 60 inherits receive")
    val r2 = p("10 ORS packets damaged")
    v(r2 == listOf(VoiceStockLine("ORS", 10, "writeoff", "ors · 10")), "damaged maps to write-off")
    val r3 = p("counted 45 IFA tablets")
    v(r3.size == 1 && r3[0].item == "IFA tablets (adult)" && r3[0].qty == 45 && r3[0].op == "correction", "counted maps to correction")
    // Hindi verbs, digits and number words
    v(p("100 ORS मिला").firstOrNull()?.op == "receive", "मिला = receive")
    v(p("45 IFA गिना").firstOrNull()?.op == "correction", "गिना = correction")
    v(p("10 ORS ख़राब").firstOrNull()?.op == "writeoff", "ख़राब = write-off")
    v(p("१०० ORS मिला").firstOrNull()?.qty == 100, "Devanagari digits read")
    v(p("received fifty ORS").firstOrNull()?.qty == 50, "English number word read")
    v(p("पचास ORS मिला").firstOrNull()?.qty == 50, "Hindi number word read")
    // Item disambiguation
    v(p("received 2 emergency pills").firstOrNull()?.item == "Emergency contraceptive pill", "emergency pill is ECP, not OCP")
    v(p("received 5 pill cycles").firstOrNull()?.item == "Oral contraceptive pills", "pill cycles are OCP")
    v(p("received 30 calcium tablets").firstOrNull()?.item == "Calcium tablets", "calcium matched")
    // Nothing invented
    v(p("received 100").isEmpty(), "no item, no line")
    v(p("received ORS").isEmpty(), "no number, no line")
    v(p("hello there").isEmpty(), "chit-chat gives nothing")
    // Model output parsing + grounding: numbers not spoken are rejected
    val m = ArogyaVoiceStock.parseModel("RECEIVED | ORS | 100\nDAMAGED | Zinc tablets | 5")
    v(m.size == 2 && m[0].op == "receive" && m[1].op == "writeoff", "model lines parsed")
    v(ArogyaVoiceStock.ground(listOf(VoiceStockLine("ORS", 999, "receive", "ors · 999")), "received 100 ORS", cat).isEmpty(), "unspoken number rejected")
    v(ArogyaVoiceStock.ground(listOf(VoiceStockLine("Aspirin", 10, "receive", "aspirin · 10")), "received 10 aspirin", cat).isEmpty(), "unknown item rejected")
    v(ArogyaVoiceStock.ground(listOf(VoiceStockLine("ORS", 50, "receive", "ors · 50")), "received fifty ORS", cat).size == 1, "number-word quantity accepted")
    // Transactions
    val t1 = ArogyaVoiceStock.toTxn(VoiceStockLine("ORS", 100, "receive", "ors · 100"), 8, "2026-11-02")
    v(t1.qty == 100 && t1.kind == "receive" && t1.note.startsWith("Voice:"), "receive txn +100")
    val t2 = ArogyaVoiceStock.toTxn(VoiceStockLine("ORS", 10, "writeoff", "ors · 10"), 8, "2026-11-02")
    v(t2.qty == -10 && t2.kind == "writeoff" && t2.reason.isNotBlank(), "write-off txn −10 with reason")
    val t3 = ArogyaVoiceStock.toTxn(VoiceStockLine("IFA tablets (adult)", 45, "correction", "ifa · 45"), 50, "2026-11-02")
    v(t3.qty == -5 && t3.kind == "adjust" && t3.note.contains("was 50"), "correction stores the difference")
    v(ArogyaVoiceStock.opOf("60 zinc") == "receive", "bare quantity defaults to receive")
    println(if (vf == 0) "ALL $vp PASS" else "$vf FAILED, $vp passed")
    if (vf > 0) kotlin.system.exitProcess(1)
}
