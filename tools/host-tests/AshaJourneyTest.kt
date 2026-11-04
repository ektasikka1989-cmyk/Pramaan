import com.example.arfas.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/*
 * The ASHA's day, end to end, through the app's own logic (what the demo's three workflows call):
 * slip photo → registers → right form → voice note (worst case: no AI, fixed rules only; and with a
 * typical AI reply) → danger rules → save (form + MCP card + registers + stock + diary + next due) →
 * official MCP card as rendered from the real template → registers → refill → new stock scan.
 * Each check states what Sunita expects to see.
 */
private var n = 0; private var fails = 0
private fun check(name: String, ok: Boolean, info: Any? = null) { if (ok) { n++; println("PASS $name") } else { fails++; println("FAIL $name ${info ?: ""}") } }

private val TEMPLATES: Pair<String, String> by lazy {
    var d: File? = File(System.getProperty("user.dir"))
    while (d != null && !File(d, "app/src/main/assets/doc_templates/mcp.html").isFile) d = d.parentFile
    val dir = File(d, "app/src/main/assets/doc_templates")
    File(dir, "mcp.html").readText() to File(dir, "doc.css").readText()
}
private val HEADER = ArogyaDocRender.DocHeader("Uttar Pradesh", "Sitapur", "Mishrikh", "Rampur SC", "Sunita Devi")
private fun card(b: Beneficiary, r: RegistryData, today: LocalDate) =
    ArogyaDocRender.mcpDoc(b, r.beneficiaries, today, HEADER, today.toString(), TEMPLATES.first, TEMPLATES.second, forms = r.forms).html
private fun bal(r: RegistryData, item: String) = r.stock.filter { it.item == item }.sumOf { it.qty }
private fun dd(iso: String) = LocalDate.parse(iso).let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) }
/** The printed MCP card writes dates as dd/MM/yy. */
private fun cd(iso: String) = LocalDate.parse(iso).let { "%02d/%02d/%02d".format(it.dayOfMonth, it.monthValue, it.year % 100) }

/** What the save step does (ArogyaVisitSave.save without the Android store/queue). */
private fun save(r: RegistryData, b: Beneficiary, spec: FormSpec, col: Int, v: Map<String, String>, today: LocalDate): Pair<RegistryData, ArogyaVisit> {
    val rec = ArogyaOfficialForms.visitRecord(b, spec, col, v, today)
    val a0 = ArogyaOfficialForms.apply(r, b, spec, col, v, v.mapValues { "voice" }, emptySet(), today)
    return ArogyaOfficialForms.applyNoteMedicines(a0, b, spec, col, v, today) to rec
}

fun main() {
    val today = LocalDate.now()
    val F = ArogyaOfficialForms
    var r = ArogyaSampleVillage.build(today).registry

    // ══════════ WORKFLOW 1 · New pregnancy (Sarita) ══════════
    println("── W1 new pregnancy")
    // 1. Slip photo: ML Kit lines with their positions, as the generated slip is laid out (slight tilt).
    fun L(t: String, x: Int, y: Int, w: Int = 150, h: Int = 30) = ArogyaTableOcr.Line(t, x, y, x + w, y + h)
    val lmp = today.minusWeeks(10)
    val slip = listOf(L("PREGNANCY REGISTRATION - VILLAGE RAMPUR", 150, 195, 1000, 44), L("Sub-centre Rampur | ASHA: Sunita Devi | Aadhaar seen: 0000 1111 2222", 150, 255, 900),
        L("Name", 160, 340), L("Husband", 455, 338), L("Age", 738, 336, 60), L("Mobile", 826, 334), L("House No.", 1070, 331), L("Village", 1256, 329), L("Category", 1431, 327), L("LMP", 1626, 324, 60),
        L("Sarita Yadav", 163, 445, 190), L("Ramesh Yadav", 456, 441, 210), L("23", 740, 440, 40), L("9000000301", 827, 438, 190), L("H-051", 1071, 433, 90),
        L("Rampur", 1257, 431, 115), L("Pregnant", 1432, 429, 130), L(dd(lmp.toString()), 1627, 425, 170),
        L("Family consent taken: Yes", 153, 555, 300), L("Signature: ____________", 520, 553, 320))
    val rows = ArogyaTableOcr.people(slip)
    check("w1-slip-one-person", rows.size == 1, rows)
    val row = ArogyaTableOcr.readPerson(slip)
    check("w1-no-ai-needed", ArogyaTableOcr.CORE.all { !row[it].isNullOrBlank() }, row)
    val p0 = ArogyaPersonCsv.toBeneficiary(row)
    check("w1-slip-all-fields", p0.name == "Sarita Yadav" && p0.relativeName == "Ramesh Yadav" && p0.age == "23" && p0.phone == "9000000301" &&
        p0.household == "H-051" && p0.village == "Rampur" && p0.category == BenCategory.PREGNANT && p0.lmp == lmp.toString(), p0)
    check("w1-aadhaar-kept-masked", p0.aadhaar == "000011112222" && ArogyaPersonExtract.masked(p0.aadhaar) == "XXXX XXXX 2222", p0.aadhaar)
    check("w1-aadhaar-never-to-cloud", !ArogyaOnlinePolicy.scrubGeneric("Aadhaar 0000 1111 2222").contains("2222"))
    val sarita = p0.copy(id = "demo-person-test", demo = true)
    r = r.copy(beneficiaries = r.beneficiaries + sarita)
    // 2. Registers show her.
    check("w1-village-register", ArogyaRegisterTables.villageTable(r.beneficiaries, today, false).rows.any { it.beneficiaryId == sarita.id })
    val ancRow = ArogyaRegisterTables.ancTable(r.beneficiaries, today, false).rows.firstOrNull { it.beneficiaryId == sarita.id }
    check("w1-anc-register-with-edd", ancRow != null && ancRow.cells.any { it.contains(lmp.plusDays(280).let { e -> e.dayOfMonth.toString() }) }, ancRow?.cells)
    check("w1-due-list", ArogyaSchedule.forBeneficiary(sarita, emptyList(), today).any { !it.done }, ArogyaSchedule.forBeneficiary(sarita, emptyList(), today).map { it.label })
    // 3. Right form: MCP ANC, visit 1.
    val (s1, c1) = F.formFor(sarita, r.forms, today)
    check("w1-form-anc-1", s1.id == "mcp_anc" && c1 == 0 && F.columnFor(sarita, "mcp_anc", r.forms, today) == 0, "${s1.id}#$c1")
    // 4. Voice note with NO model (worst case on the phone): rules only.
    val v1 = F.parse("", F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN).mapValues { it.value.value }
    check("w1-voice-rules", listOf("weight", "bp", "hb", "pog", "height", "pulse", "td", "urine_albumin", "blood_group", "hiv", "d_bleed", "d_headache", "d_fever").all { it in v1 } && "ifa" !in v1, v1)
    // 5. Danger: none.
    check("w1-no-danger", ArogyaDemoScript.dangerLines(ArogyaDemoScript.NOTE_W1_EN, F.MCP_ANC, v1, ArogyaVisit(name = sarita.name, date = today.toString()), false).isEmpty())
    // 6. Save.
    val ifa0 = bal(r, "IFA tablets (adult)"); val cal0 = bal(r, "Calcium tablets"); val ptk0 = bal(r, "Pregnancy test kit")
    val (r1, rec1) = save(r, sarita, F.MCP_ANC, 0, v1 + (F.NOTE_KEY to ArogyaDemoScript.NOTE_W1_EN), today); r = r1
    check("w1-note-kept", rec1.notes.contains("Visit note:") && r.forms.any { it.beneficiaryId == sarita.id && it.values[F.NOTE_KEY] == ArogyaDemoScript.NOTE_W1_EN })
    check("w1-unused-sentence-shown", F.unusedSentences(ArogyaDemoScript.NOTE_W1_EN, F.parse("", F.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN)).any { it.contains("14 weeks") })
    val s1b = r.beneficiaries.first { it.id == sarita.id }
    val e1 = r.forms.firstOrNull { it.beneficiaryId == sarita.id && it.formId == "mcp_anc" && it.column == 0 }
    check("w1-form-entry", e1 != null && e1.values["weight"] == "49" && e1.values["bp"] == "110/70" && e1.values["hb"] == "10.2", e1?.values)
    check("w1-card-anc-ticked", s1b.mcp.anc[1] == today.toString() && s1b.mcp.ancDetails[1]?.bp == "110/70" && s1b.mcp.ancDetails[1]?.weightKg == "49", s1b.mcp)
    // First trimester: Td-1 on the card; IFA / calcium not yet (they start in the second trimester); 1 pregnancy test kit used.
    check("w1-card-td1", s1b.mcp.tdDates.contains(today.toString()) && s1b.mcp.ifaTablets == 0, s1b.mcp)
    check("w1-stock-test-kit", bal(r, "Pregnancy test kit") == ptk0 - 1 && bal(r, "IFA tablets (adult)") == ifa0 && bal(r, "Calcium tablets") == cal0,
        "${bal(r, "Pregnancy test kit")} vs ${ptk0 - 1}; IFA ${bal(r, "IFA tablets (adult)")}")
    check("w1-stock-line-to-person", r.stock.any { it.beneficiaryId == sarita.id && it.item == "Pregnancy test kit" && it.qty == -1 })
    check("w1-diary", r.diary.any { it.beneficiaryId == sarita.id && it.kind == DiaryKind.HOME_VISIT && it.date == today.toString() })
    check("w1-next-due", rec1.nextDate.isNotBlank() && rec1.referral == ArogyaReferral.NONE, "${rec1.nextDate} ${rec1.nextReason} ${rec1.referral}")
    // 7. The official MCP card, from the real template.
    val h1 = card(s1b, r, today)
    check("w1-card-header", listOf("Uttar Pradesh", "Sitapur", "Mishrikh", "Rampur SC", "Sunita Devi").all { h1.contains(it) })
    check("w1-card-identity", listOf("Sarita Yadav", "Ramesh Yadav", "9000000301", "H-051, Rampur", ">23<").all { h1.contains(it) } && h1.contains(cd(lmp.toString())), "identity")
    check("w1-card-anc-values", listOf(">49<", ">110/70<", ">10.2<").all { h1.contains(it) }, "anc grid")
    check("w1-card-edd", h1.contains(cd(lmp.plusDays(280).toString())), "EDD")
    // 8. Form table (what "Open official form" shows).
    val ft1 = F.table(F.MCP_ANC, s1b, r.forms)
    check("w1-form-table", ft1.rows.any { row1 -> row1.any { it == "49" || it.startsWith("49") } } && ft1.rows.any { row1 -> row1.any { it.contains("110/70") } }, ft1.rows.take(3))

    // ══════════ WORKFLOW 2 · Newborn day 7 (Baby of Neha) ══════════
    println("── W2 newborn day 7")
    val baby = r.beneficiaries.first { it.name == "Baby of Neha" }
    val mum = r.beneficiaries.first { it.name == "Neha (mother)" }
    val dob = LocalDate.parse(baby.dob)
    // 1. Card photo: a table photo read row by row (positions), then a typical model reply through the real parser.
    val cardLines = listOf(L("MOTHER AND CHILD PROTECTION CARD", 200, 200, 900), L("Child name:", 220, 400, 180), L("Baby of Neha", 510, 398, 220),
        L("Date of birth:", 222, 508, 190), L(dd(dob.toString()), 512, 505, 190),
        L("Age", 240, 712, 60), L("Vaccine", 500, 708), L("Date given", 788, 702),
        L("Birth", 241, 772, 70), L("OPV-0", 501, 768, 90), L(dd(dob.plusDays(2).toString()), 790, 760, 190),
        L("Birth", 242, 834, 70), L("Hep B", 503, 830, 90), L(dd(dob.plusDays(2).toString()), 791, 822, 190),
        L("Birth", 243, 896, 70), L("BCG", 504, 891, 60), L(dd(dob.plusDays(2).toString()), 792, 883, 190),
        L("1½ months", 245, 957, 130), L("OPV-1", 505, 952, 90))
    val cardText = ArogyaTableOcr.rowText(cardLines)
    check("w2-card-rows", cardText.lines().any { it.contains("OPV-0") && it.contains(dd(dob.plusDays(2).toString())) }, cardText)
    val reply = """{"child_name":"Baby of Neha","birth_date":"${dd(dob.toString())}","vaccines":[{"name":"OPV-0","date":"${dd(dob.plusDays(2).toString())}"},{"name":"Hep B","date":"${dd(dob.plusDays(2).toString())}"},{"name":"BCG","date":"${dd(dob.plusDays(2).toString())}"}],"weights":[]}"""
    val fields = ArogyaAiExtras.parseCard(reply, cardText)
    check("w2-card-read-dated", fields.count { it.key == "vaccine" && !it.value.contains("not readable") } == 3, fields.map { it.value })
    // Column-by-column text (ML Kit's plain order) must never record a vaccine as given today.
    val columnText = "Age\nBirth\nBirth\nBirth\nVaccine\nOPV-0\nHep B\nBCG\nDate given\n" + List(3) { dd(dob.plusDays(2).toString()) }.joinToString("\n")
    val colFields = ArogyaAiExtras.parseCard(reply, columnText)
    val colApplied = ArogyaCardApply.apply(baby, colFields)
    check("w2-no-invented-vaccine-date", colApplied.mcp.vaccines.values.none { it == today.toString() }, colApplied.mcp.vaccines)
    val babyV = ArogyaCardApply.apply(baby, fields)
    check("w2-card-applied", setOf("BCG", "OPV0", "HEPB0").all { babyV.mcp.vaccines[it] == dob.plusDays(2).toString() }, babyV.mcp.vaccines)
    r = r.copy(beneficiaries = r.beneficiaries.map { if (it.id == baby.id) babyV else it })
    // Birth doses done → nothing due this month, so she leaves the month's immunization due list (correct).
    check("w2-due-list-updated", ArogyaSchedule.forBeneficiary(babyV, emptyList(), today).none { !it.done && it.label.contains("BCG") })
    // 2. Right form: HBNC Day 7.
    val (s2, c2) = F.formFor(babyV, r.forms, today)
    check("w2-form-hbnc-day7", s2.id == "annex9" && c2 == 2, "${s2.id}#$c2")
    // 3. Voice, no model.
    val v2 = F.parse("", F.ANNEX9, 2, ArogyaDemoScript.NOTE_EN).mapValues { it.value.value }
    check("w2-voice-rules", v2["weight"] == "2.65" && v2["temp"] == "38.4" && v2["s_feed"] == "Yes" && v2["m_temp"] == "37" && v2["m_pads"] == "3", v2)
    // 4. Danger: refer.
    val d2 = ArogyaDemoScript.dangerLines(ArogyaDemoScript.NOTE_EN, F.ANNEX9, v2, ArogyaVisit(name = baby.name, kind = ArogyaKind.NEWBORN, date = today.toString(), birthDate = baby.dob), false)
    check("w2-danger-refer", d2.any { it.startsWith("⚠") }, d2)
    // 5. Save + the mother's medicines (Give to person).
    val ifa1 = bal(r, "IFA tablets (adult)"); val cal1 = bal(r, "Calcium tablets")
    val (r2, rec2) = save(r, babyV, F.ANNEX9, 2, v2 + (F.NOTE_KEY to ArogyaDemoScript.NOTE_EN), today); r = r2
    // Re-saving the same visit must not take the mother's medicines out twice.
    r = F.applyNoteMedicines(r, babyV, F.ANNEX9, 2, v2 + (F.NOTE_KEY to ArogyaDemoScript.NOTE_EN), today)
    check("w2-hbnc-entry", r.forms.any { it.beneficiaryId == baby.id && it.formId == "annex9" && it.column == 2 && it.values["temp"] == "38.4" })
    val mb = r.forms.firstOrNull { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 2 }
    check("w2-card-baby-7th-day", mb != null && mb.values["weight"] == "2.65" && mb.values["temp"] == "38.4" && mb.values["sucking"] == "Poor", mb?.values)
    val mp = r.forms.firstOrNull { it.beneficiaryId == mum.id && it.formId == "mcp_pnc" && it.column == 2 }
    check("w2-card-mother-7th-day", mp != null && mp.values["temp"] == "37" && mp.values["bleeding"] == "Normal", mp?.values)
    check("w2-growth-weight", r.beneficiaries.first { it.id == baby.id }.mcp.weights.any { it.date == today.toString() && it.kg == 2.65 })
    check("w2-referral-recorded", rec2.referral == ArogyaReferral.NEEDED && rec2.referralNote.isNotBlank(), rec2.referralNote)
    check("w2-next-due-day14", rec2.nextReason.contains("14"), "${rec2.nextDate} ${rec2.nextReason}")
    check("w2-mother-stock", bal(r, "IFA tablets (adult)") == ifa1 - 30 && bal(r, "Calcium tablets") == cal1 - 60 && r.beneficiaries.first { it.id == mum.id }.mcp.ifaTablets == mum.mcp.ifaTablets + 30,
        "${bal(r, "IFA tablets (adult)")} ${bal(r, "Calcium tablets")} ${r.beneficiaries.first { it.id == mum.id }.mcp.ifaTablets}")
    val h2 = card(r.beneficiaries.first { it.id == baby.id }, r, today)
    check("w2-card-render", listOf(">2.65<", ">38.4<", "Poor", cd(dob.plusDays(2).toString()), "7th Day: recorded at the ASHA's HBNC home visit", "Sitapur").all { h2.contains(it) },
        listOf(">2.65<", ">38.4<", "Poor", cd(dob.plusDays(2).toString()), "7th Day: recorded", "Sitapur").filterNot { h2.contains(it) })
    check("w2-card-mother-identity", h2.contains("Rajesh Kumar") && h2.contains("Neha"), "mother identity")

    // ══════════ WORKFLOW 3 · Sick child (Rohan) ══════════
    println("── W3 sick child")
    val rohan = r.beneficiaries.first { it.name == "Child Rohan" }
    val c3 = F.columnFor(rohan, "sick", r.forms, today)
    val v3 = F.parse("", F.SICK, c3, ArogyaDemoScript.NOTE_W3_EN).mapValues { it.value.value }
    check("w3-voice-rules", v3["ors"] == "4" && v3["zinc"] == "14" && v3["diarrhoea"] == "Yes" && v3["temp"] == "37.2", v3)
    check("w3-no-danger", ArogyaDemoScript.dangerLines(ArogyaDemoScript.NOTE_W3_EN, F.SICK, v3, ArogyaVisit(name = rohan.name, kind = ArogyaKind.CHILD, date = today.toString()), false).isEmpty())
    val ors0 = bal(r, "ORS"); val zinc0 = bal(r, "Zinc tablets")
    check("w3-zinc-before", zinc0 == 14, zinc0)
    val (r3, rec3) = save(r, rohan, F.SICK, c3, v3 + (F.NOTE_KEY to ArogyaDemoScript.NOTE_W3_EN), today); r = r3
    check("w3-stock-after", bal(r, "ORS") == ors0 - 4 && bal(r, "Zinc tablets") == 0, "ORS ${bal(r, "ORS")} zinc ${bal(r, "Zinc tablets")}")
    check("w3-follow-up-2-days", rec3.nextDate == today.plusDays(2).toString(), rec3.nextDate)
    val low = ArogyaRegistryLogic.lowItems(r.stock, r.stockItems, today)
    check("w3-zinc-out", low.any { it.name == "Zinc tablets" && it.status == "OUT" }, low)
    check("w3-refill-text", ArogyaRegistryLogic.refillRequest(r.stock, r.stockItems, today, false).contains("Zinc"), ArogyaRegistryLogic.refillRequest(r.stock, r.stockItems, today, false))
    // New zinc strip photo → scan (on-phone rules) → stock received.
    val stripText = "ZINC SULPHATE\nDISPERSIBLE TABLETS IP\n20 mg\nBatch No. ZN2611A\nMfg. 08/2026\nExp. 07/2028\n30 Tablets"
    val scan = ArogyaMedScan.parse(stripText)
    val item = ArogyaMedScan.matchItem(scan.name, ArogyaRegistryLogic.catalog(r.stockItems).map { it.name })
    check("w3-scan", item == "Zinc tablets" && scan.batch == "ZN2611A" && scan.expiry.contains("2028") && scan.qty == 30, "$scan → $item")
    val ifaScan = ArogyaMedScan.parse("IRON & FOLIC ACID\nTABLETS IP (IFA)\nBatch No. IFA2610C\nMfg. 04/2026\nExp. 03/2028\n10 Tablets\nGovt. supply")
    check("w3-ifa-scan", ArogyaMedScan.matchItem(ifaScan.name, ArogyaRegistryLogic.catalog(r.stockItems).map { it.name }) == "IFA tablets (adult)" && ifaScan.batch == "IFA2610C" && ifaScan.qty == 10, ifaScan)
    val pcmScan = ArogyaMedScan.parse("PARACETAMOL\nTABLETS IP 500 mg\nB. No. PCM2611B\nExp. 05/2028\n10 Tablets")
    check("w3-pcm-scan", ArogyaMedScan.matchItem(pcmScan.name, ArogyaRegistryLogic.catalog(r.stockItems).map { it.name }) == "Paracetamol" && pcmScan.batch == "PCM2611B", pcmScan)
    r = r.copy(stock = r.stock + StockTxn(item = item, qty = scan.qty, kind = "receive", batch = scan.batch, expiry = ArogyaMedScan.normExpiry(scan.expiry), note = "scan"))
    check("w3-stock-received", bal(r, "Zinc tablets") == 30)
    val sreg = ArogyaRegistryLogic.stockRegister(r.stock, r.stockItems, YearMonth.from(today), today, false)
    check("w3-stock-register-row", sreg.rows.any { it.cells[0] == "Zinc tablets" }, sreg.headers)
    check("w3-form-table", F.table(F.SICK, r.beneficiaries.first { it.id == rohan.id }, r.forms).rows.any { row3 -> row3.any { it == "14" } })

    // A danger sign said in the note but caught by no form row still makes a referral (fixed rules on her words).
    val nb = r.beneficiaries.first { it.name == "Baby of Ritu" }
    val recD = F.visitRecord(nb, F.ANNEX9, 3, mapOf("weight" to "3.0", F.NOTE_KEY to "Baby had fits this morning and is not feeding."), today)
    check("note-danger-refers", recD.referral == ArogyaReferral.NEEDED, recD.notes)
    println("AshaJourneyTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
