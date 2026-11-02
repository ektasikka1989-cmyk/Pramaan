import com.example.arfas.*
var sf = 0; var sp = 0
fun s(c: Boolean, m: String) { if (c) { sp++; println("PASS $m") } else { sf++; println("FAIL $m") } }
fun main() {
    val b = Beneficiary(id = "s-1", name = "Kamla Devi", village = "Rampur", category = BenCategory.PREGNANT,
        lmp = "2026-04-06", phone = "9812345670", relativeName = "Ramesh Kumar")
    val v = ArogyaVisit(personId = "s-1", name = "Kamla Devi", village = "Rampur", date = "2026-11-02")
    // Form fill cloud prompt: beneficiary scrub
    val note = "Kamla Devi of Rampur, phone 9812345670, weight 52 kg, fever since 2 days, Hb 9.5"
    val sn = ArogyaOfficialForms.scrub(b, note)
    s(!sn.contains("Kamla") && !sn.contains("Rampur") && !sn.contains("9812345670"), "form scrub removes name, village, phone")
    s(sn.contains("52") && sn.contains("fever") && sn.contains("9.5"), "form scrub keeps clinical facts")
    // Visit scrub: names, village, emails, phone variants
    val facts = "Visit Kamla Devi at Rampur, call +91 98123 45670 or asha@example.org. Baby fed well, temp 99.2."
    val sv = ArogyaOnlinePolicy.scrub(facts, v)
    s(!sv.contains("Kamla") && !sv.contains("Rampur") && !sv.contains("98123") && !sv.contains("example.org"), "visit scrub removes name, village, phone, email")
    s(sv.contains("fed well") && sv.contains("99.2"), "visit scrub keeps clinical facts")
    // Generic scrub (no context): numbers + emails
    val ocr = "Strip label PARACETAMOL batch B12 queries care@example.com helpline 18001234567"
    val sg = ArogyaOnlinePolicy.scrubGeneric(ocr)
    s(!sg.contains("example.com") && !sg.contains("18001234567") && sg.contains("PARACETAMOL") && sg.contains("B12"), "generic scrub removes email + long numbers only")
    val spaced = "Aadhaar-like 1234 5678 9012 must go, batch 123 must stay"
    val ss = ArogyaOnlinePolicy.scrubGeneric(spaced)
    s(!ss.contains("1234 5678 9012") && ss.contains("123"), "generic scrub removes spaced 12-digit runs, keeps short codes")
    // Representative cloud prompts carry no identifiers
    val vp = ArogyaVoiceStock.prompt(ArogyaOnlinePolicy.scrubGeneric("received 100 ORS, call 9812345670"), listOf("ORS"))
    s(!vp.contains("9812345670") && vp.contains("100"), "voice-stock cloud prompt keeps qty, drops phone")
    val mp = ArogyaMedScan.prompt(ArogyaOnlinePolicy.scrubGeneric("CROCIN exp 08/27 phone 9812345670"))
    s(!mp.contains("9812345670") && mp.contains("CROCIN"), "medicine cloud prompt keeps label, drops phone")
    val fp = ArogyaAiExtras.familyPrompt(ArogyaOnlinePolicy.scrubGeneric("Give ORS. Call 9812345670."), "ORS", false)
    s(!fp.contains("9812345670") && fp.contains("ORS"), "family cloud prompt keeps guidance, drops phone")
    println(if (sf == 0) "ALL $sp PASS" else "$sf FAILED, $sp passed")
    if (sf > 0) kotlin.system.exitProcess(1)
}
