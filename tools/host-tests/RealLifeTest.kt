import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth

/*
 * Real-life ASHA scenarios beyond the demo, through the app's own logic (save = ArogyaVisitSave's steps):
 * repeat visits weeks later, delivery and the HBNC series, missed and repeated visits, danger signs said only in
 * words (pregnancy, sick child) with NO AI, medicine with an empty kit, duplicate registration, Hindi notes,
 * backup round trip, monthly report and incentives.
 */
private var n = 0; private var fails = 0
private fun check(name: String, ok: Boolean, info: Any? = null) { if (ok) { n++; println("PASS $name") } else { fails++; println("FAIL $name ${info ?: ""}") } }
private val F = ArogyaOfficialForms
private fun save(r: RegistryData, b: Beneficiary, spec: FormSpec, col: Int, note: String, day: LocalDate, extra: Map<String, String> = emptyMap()): Pair<RegistryData, ArogyaVisit> {
    val v = F.parse("", spec, col, note).mapValues { it.value.value } + extra + (F.NOTE_KEY to note)     // worst case: no AI
    val rec = F.visitRecord(b, spec, col, v, day)
    return F.applyNoteMedicines(F.apply(r, b, spec, col, v, v.mapValues { "voice" }, emptySet(), day), b, spec, col, v, day) to rec
}
private fun bal(r: RegistryData, item: String) = r.stock.filter { it.item == item }.sumOf { it.qty }

fun main() {
    val today = LocalDate.now()
    var r = ArogyaSampleVillage.build(today).registry
    val visits = ArrayList<ArogyaVisit>()

    // ── 1. Pregnancy over months: registration, ANC 1, ANC 2 five weeks later ──
    var pw = Beneficiary(id = "rl-pw", name = "Sarita Yadav", relativeName = "Ramesh Yadav", age = "23", phone = "9000000301", household = "H-051", village = "Rampur",
        category = BenCategory.PREGNANT, lmp = today.minusWeeks(10).toString(), demo = true)
    r = r.copy(beneficiaries = r.beneficiaries + pw)
    check("dup-detected", ArogyaPersonDup.find(pw.copy(id = "rl-pw-2"), r.beneficiaries)?.id == pw.id)
    val (s1, c1) = F.formFor(pw, r.forms, today)
    var (r1, v1) = save(r, pw, s1, c1, ArogyaDemoScript.NOTE_W1_EN, today); r = r1; visits += v1
    val d2 = today.plusWeeks(5)
    val (s2, c2) = F.formFor(r.beneficiaries.first { it.id == pw.id }, r.forms, d2)
    check("anc2-next-column", s2.id == "mcp_anc" && c2 == 1, "${s2.id}#$c2")
    check("anc2-last-time-shown", F.previous(s2, pw, r.forms, c2)["weight"]?.first == "49", F.previous(s2, pw, r.forms, c2))
    val (r2, v2) = save(r, r.beneficiaries.first { it.id == pw.id }, s2, c2, "15 weeks pregnant. Weight 51 kg, BP 112/72, Hb 10.8. No bleeding. Gave 30 IFA tablets and 60 calcium tablets.", d2); r = r2; visits += v2
    pw = r.beneficiaries.first { it.id == pw.id }
    check("anc2-card", pw.mcp.anc[2] == d2.toString() && pw.mcp.ancDetails[2]?.weightKg == "51" && pw.mcp.ifaTablets == 30, pw.mcp)

    // ── 2. Pregnancy danger said only in words (no AI): bleeding + headache with blurred vision → refer ──
    val dNote = "7 months pregnant. Bleeding since morning, no fever, severe headache and blurred vision."
    val dv = F.parse("", F.MCP_ANC, 2, dNote).mapValues { it.value.value }
    check("anc-danger-rules", dv["d_bleed"] == "Yes" && dv["d_headache"] == "Yes" && dv["d_fever"] == "No", dv)
    check("anc-danger-refers", F.visitRecord(pw, F.MCP_ANC, 2, dv + (F.NOTE_KEY to dNote), today).referral == ArogyaReferral.NEEDED)
    val dHi = F.parse("", F.MCP_ANC, 2, "7 महीने की गर्भवती। सुबह से खून आ रहा है, तेज़ सिरदर्द और धुंधला दिख रहा है, बुखार नहीं।").mapValues { it.value.value }
    check("anc-danger-hindi", dHi["d_bleed"] == "Yes" && dHi["d_headache"] == "Yes" && dHi["d_fever"] == "No", dHi)
    check("no-but-negation", F.parse("", F.MCP_ANC, 2, "No bleeding but severe headache.").mapValues { it.value.value }.let { it["d_bleed"] == "No" && it["d_headache"] == "Yes" })

    // ── 3. Delivery at home, baby registered, HBNC Day 1 → Day 3 missed → Day 7; re-save same day ──
    val dob = today
    pw = pw.copy(category = BenCategory.MOTHER, deliveryDate = dob.toString(), deliveryPlace = "HOME")
    val baby = Beneficiary(id = "rl-baby", name = "Baby of Sarita", relativeName = "Sarita Yadav", household = "H-051", village = "Rampur", category = BenCategory.CHILD,
        sex = "F", dob = dob.toString(), deliveryPlace = "HOME", birthWeightKg = 2.9, demo = true)
    r = r.copy(beneficiaries = r.beneficiaries.map { if (it.id == pw.id) pw else it } + baby)
    val (h1, k1) = F.formFor(baby, r.forms, dob)
    check("hbnc-day1-home-birth", h1.id == "annex9" && k1 == 0, "${h1.id}#$k1")
    var (r3, v3) = save(r, baby, h1, k1, "Day 1. Baby temperature 36.8, feeding well, kept warm. Mother 4 meals, 4 pads, temperature 37.", dob); r = r3; visits += v3
    val (h7, k7) = F.formFor(r.beneficiaries.first { it.id == baby.id }, r.forms, dob.plusDays(6))
    check("hbnc-day7-after-missed-day3", h7.id == "annex9" && k7 == 2, "${h7.id}#$k7")
    val day7 = "Day 7. Baby weight 3000 grams, temperature 37.0, feeding well. No pus on the cord. Mother temperature 37, 2 pads. Gave mother 30 IFA tablets."
    val ifa0 = bal(r, "IFA tablets (adult)")
    val (r4, v4) = save(r, baby, h7, k7, day7, dob.plusDays(6)); r = r4; visits += v4
    val (r5, _) = save(r, baby, h7, k7, day7, dob.plusDays(6)); r = r5   // the ASHA saves the same visit again
    check("hbnc-resave-no-duplicates", r.forms.count { it.beneficiaryId == baby.id && it.formId == "annex9" && it.column == 2 } == 1 &&
        r.forms.count { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 2 } == 1)
    check("hbnc-mother-ifa-once", bal(r, "IFA tablets (adult)") == ifa0 - 30, "${bal(r, "IFA tablets (adult)")} vs ${ifa0 - 30}")
    check("hbnc-day3-left-blank", r.forms.none { it.beneficiaryId == baby.id && it.formId == "annex9" && it.column == 1 })
    check("hbnc-linked-day1-and-day7", r.forms.any { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 0 } &&
        r.forms.any { it.beneficiaryId == pw.id && it.formId == "mcp_pnc" && it.column == 2 })
    check("hbnc-next-due-day14", v4.nextReason.contains("14"), v4.nextReason)
    check("hbnc-no-referral-when-well", v4.referral == ArogyaReferral.NONE, v4.referralNote)

    // ── 4. Sick child, danger said only in words (no AI): fast breathing + chest indrawing → refer ──
    val rohan = r.beneficiaries.first { it.name == "Child Rohan" }
    val sickNote = "Child Rohan, 14 months. Cough for 3 days with fast breathing and chest indrawing. Not able to drink. Temperature 38.6."
    val sv = F.parse("", F.SICK, 0, sickNote).mapValues { it.value.value }
    check("sick-danger-rules", sv["fast_breath"] == "Yes" && sv["g_drink"] == "Yes" && sv["temp"] == "38.6", sv)
    check("sick-danger-refers", F.dangers(F.SICK, sv).isNotEmpty() && F.visitRecord(rohan, F.SICK, 0, sv + (F.NOTE_KEY to sickNote), today).referral == ArogyaReferral.NEEDED,
        F.dangers(F.SICK, sv))
    check("sick-well-not-flagged", F.dangers(F.SICK, F.parse("", F.SICK, 0, ArogyaDemoScript.NOTE_W3_EN).mapValues { it.value.value }).isEmpty())

    // ── 5. Medicine with an empty kit: warned before saving ──
    r = r.copy(stock = r.stock + StockTxn(item = "Zinc tablets", qty = -bal(r, "Zinc tablets"), kind = "give", note = "test empties zinc"))
    val short = F.stockShortfalls(r, mapOf("ors" to "4", "zinc" to "14"))
    check("empty-kit-warning", short.any { it.first == "Zinc tablets" && it.second == 14 && it.third == 0 } && short.none { it.first == "ORS" }, short)
    check("note-medicine-shortfall", F.stockShortfalls(r, mapOf(F.NOTE_KEY to "Gave 20 zinc tablets.")).any { it.first == "Zinc tablets" })

    // ── 6. Backup round trip keeps everything (forms, the visit note, Aadhaar, stock) ──
    val back = ArogyaRegistryCodec.decode(ArogyaRegistryCodec.encode(r))
    check("backup-forms", back.forms.size == r.forms.size && back.forms.any { it.values[F.NOTE_KEY]?.contains("Day 7") == true })
    check("backup-stock", back.stock.groupBy { it.item }.mapValues { (_, l) -> l.sumOf { it.qty } } == r.stock.groupBy { it.item }.mapValues { (_, l) -> l.sumOf { it.qty } })

    // ── 7. Monthly report and incentives count real visits (linked MCP entries are not extra visits) ──
    val month = YearMonth.from(today)
    val rep = ArogyaRegistryLogic.monthlyReport(r, visits, month)
    val inMonth = visits.count { YearMonth.from(LocalDate.parse(it.date)) == month }
    check("monthly-home-visits", rep.text.contains("Home visits: $inMonth"), rep.text.lines().take(6))
    check("incentive-runs", ArogyaRegistryLogic.incentiveEstimate(r, visits, month).lines.isNotEmpty())

    // ── 8. Hindi notes fill the same rows as English ──
    val w2en = F.parse("", F.ANNEX9, 2, ArogyaDemoScript.NOTE_EN).mapValues { it.value.value }
    val w2hi = F.parse("", F.ANNEX9, 2, ArogyaDemoScript.NOTE_HI).mapValues { it.value.value }
    check("hindi-equals-english", listOf("weight", "temp", "m_temp", "m_meals", "m_pads", "s_feed", "s_cry").all { w2en[it] == w2hi[it] }, "$w2en\n$w2hi")

    println("RealLifeTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
