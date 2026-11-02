import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth

var rf = 0; var rp = 0
fun ck(c: Boolean, m: String) { if (c) { rp++; println("PASS $m") } else { rf++; println("FAIL $m") } }

// SYNTHETIC records only.
fun main() {
    val today = LocalDate.of(2026, 10, 31)
    // 1. HBNC: institutional vs home birth; SNCU discharge counted as day 1
    val inst = Beneficiary(id = "c1", name = "Demo Baby One", category = BenCategory.CHILD, sex = "F", dob = "2026-10-25", deliveryPlace = "INSTITUTION")
    val hb = ArogyaSchedule.forBeneficiary(inst, emptyList(), today).filter { it.kind == "HBNC" }
    ck(hb.map { it.due.toString() } == listOf("2026-10-27", "2026-10-31", "2026-11-07", "2026-11-14", "2026-11-21", "2026-12-05"), "institutional HBNC = days 3,7,14,21,28,42 (birth = day 1)")
    val home = inst.copy(id = "c2", deliveryPlace = "HOME")
    ck(ArogyaSchedule.forBeneficiary(home, emptyList(), today).filter { it.kind == "HBNC" }.first().due.toString() == "2026-10-25", "home birth adds day-1 visit")
    val sncu = inst.copy(id = "c3", sncuDischarge = "2026-10-28")
    ck(ArogyaSchedule.forBeneficiary(sncu, emptyList(), today).first { it.kind == "HBNC" }.due.toString() == "2026-10-30", "SNCU: discharge = day 1, day 3 = discharge+2")
    // 2. HBYC months 3,6,9,12,15; LBW flagged high-risk
    val hy = ArogyaSchedule.forBeneficiary(inst, emptyList(), today).filter { it.kind == "HBYC" }
    ck(hy.map { it.due.toString() } == listOf("2027-01-25", "2027-04-25", "2027-07-25", "2027-10-25", "2028-01-25"), "HBYC at 3,6,9,12,15 months")
    val lbw = inst.copy(id = "c4", birthWeightKg = 2.1)
    ck(ArogyaSchedule.forBeneficiary(lbw, emptyList(), today).filter { it.kind == "HBYC" }.take(4).all { it.label.contains("LBW", true) }, "LBW quarterly follow-ups flagged")
    ck(ArogyaRegistryLogic.isHighRisk(lbw) && !ArogyaRegistryLogic.isHighRisk(inst), "high-risk = LBW (<2.5 kg) / preterm / SNCU")
    // 3. Done detection from completed visits linked by personId
    val visitDay7 = ArogyaVisit(personId = "c1", name = "Demo Baby One", date = "2026-10-31", birthDate = "2026-10-25", completed = true)
    val withVisit = ArogyaSchedule.forBeneficiary(inst, listOf(visitDay7), today).filter { it.kind == "HBNC" }
    ck(withVisit[1].done && !withVisit[0].done, "completed visit on day 7 marks day-7 visit done, day 3 still missed")
    ck(withVisit[0].overdue(today), "missed day-3 visit is overdue")
    // 4. Vaccines grouped by contact, done from MCP card
    val vac = ArogyaSchedule.forBeneficiary(inst.copy(mcp = McpCard(vaccines = mapOf("BCG" to "2026-10-25", "OPV0" to "2026-10-25", "HEPB0" to "2026-10-25"))), emptyList(), today).filter { it.kind == "VACCINE" }
    ck(vac.first().done && vac.first().label.contains("Birth"), "birth-dose contact done when BCG/OPV0/HepB0 recorded")
    ck(vac.any { it.label.contains("6 weeks") && it.due.toString() == "2026-12-06" }, "6-week vaccines due at 42 days")
    ck(vac.any { it.label.contains("9 months") }, "9-month contact present")
    // 5. ANC windows from LMP; EDD
    val preg = Beneficiary(id = "m1", name = "Demo Mother One", category = BenCategory.PREGNANT, sex = "F", lmp = "2026-08-01")
    ck(ArogyaRegistryLogic.edd(preg)?.toString() == "2027-05-08", "EDD = LMP + 280 days")
    val anc = ArogyaSchedule.forBeneficiary(preg, emptyList(), today).filter { it.kind == "ANC" }
    ck(anc.size == 4 && anc[0].windowEnd.toString() == "2026-10-24" && anc[1].due.toString() == "2026-11-07", "ANC windows: ≤12w, 14–26w, 28–34w, 36w–term")
    ck(anc[0].overdue(today), "ANC-1 not recorded by 12 weeks is overdue")
    // 6. Due list across 1000 patients is fast and sorted (overdue first)
    val many = (1..1000).map { i ->
        if (i % 3 == 0) Beneficiary(id = "p$i", name = "Synthetic Mother $i", category = BenCategory.PREGNANT, sex = "F", lmp = today.minusDays((i % 250).toLong()).toString(), village = "V${i % 7}")
        else Beneficiary(id = "p$i", name = "Synthetic Child $i", category = BenCategory.CHILD, sex = if (i % 2 == 0) "F" else "M", dob = today.minusDays((i % 700).toLong()).toString(), deliveryPlace = if (i % 5 == 0) "HOME" else "INSTITUTION", village = "V${i % 7}")
    }
    val t0 = System.nanoTime()
    val due = ArogyaSchedule.dueList(many, emptyList(), today, days = 7)
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("   due list: ${due.size} items for 1000 patients in $ms ms")
    ck(due.isNotEmpty() && ms < 2000, "due list for 1000 patients under 2 s on host ($ms ms)")
    ck(due.takeWhile { it.overdue(today) }.size == due.count { it.overdue(today) }, "overdue items listed first")
    // 7. Codec round trip with 1000 patients + registers
    val data = RegistryData(
        beneficiaries = many,
        diary = (1..600).map { DiaryEntry(id = "d$it", date = today.minusDays((it % 30).toLong()).toString(), kind = DiaryKind.values()[it % DiaryKind.values().size], beneficiaryId = "p$it", note = "synthetic") },
        stock = listOf(StockTxn("s1", "2026-10-01", "ORS", 50, "", "received from SC"), StockTxn("s2", "2026-10-20", "ORS", -3, "p3", "diarrhoea")),
        vitals = listOf(VitalEvent("v1", "2026-10-25", VitalKind.BIRTH, "c1", "PHC", "synthetic"), VitalEvent("v2", "2026-10-26", VitalKind.AEFI, "p4", "home", "mild fever after vaccine")))
    val t1 = System.nanoTime()
    val json = ArogyaRegistryCodec.encode(data)
    val back = ArogyaRegistryCodec.decode(json)
    val ms2 = (System.nanoTime() - t1) / 1_000_000
    println("   codec: ${json.length / 1024} KB, round trip $ms2 ms")
    ck(back == data, "registry round-trips exactly (1000 patients, 600 diary, stock, vitals)")
    ck(ms2 < 3000, "codec round trip under 3 s on host")
    // 8. Drug stock balance
    ck(ArogyaRegistryLogic.stockBalance(data.stock)["ORS"] == 47, "ORS balance = 50 received − 3 given")
    // 9. Monthly report counts
    val visits = listOf(visitDay7, ArogyaVisit(personId = "p1", name = "x", date = "2026-10-10", completed = true), ArogyaVisit(personId = "p2", name = "y", date = "2026-09-10", completed = true))
    val rep = ArogyaRegistryLogic.monthlyReport(data, visits, YearMonth.of(2026, 10))
    ck(rep.homeVisits == 2, "monthly report counts October home visits only")
    ck(rep.distributed["ORS"] == 3 && rep.received["ORS"] == 50, "monthly drug distribution and receipts")
    ck(rep.vitals[VitalKind.BIRTH] == 1 && rep.vitals[VitalKind.AEFI] == 1, "monthly vital events")
    ck(rep.text.contains("Home visits: 2"), "report text for incentive claim / RCH entry")
    // 10. CSV export escapes commas/quotes
    val csv = ArogyaRegistryLogic.rchCsv(listOf(preg.copy(name = "Demo, \"Mother\"")))
    ck(csv.lines()[1].startsWith("\"m1\",\"Demo, \"\"Mother\"\"\""), "RCH CSV escapes commas and quotes")
    // 11. Password backup: round trip, wrong password rejected, tampering rejected
    val payload = "{\"registry\":${json.take(50).length}}".toByteArray()
    val enc = ArogyaBackupCrypto.encrypt(payload, "correct horse", iterations = 2000)
    ck(ArogyaBackupCrypto.decrypt(enc, "correct horse").contentEquals(payload), "backup decrypts with the right password")
    ck(runCatching { ArogyaBackupCrypto.decrypt(enc, "wrong") }.isFailure, "wrong password rejected")
    val bad = enc.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    ck(runCatching { ArogyaBackupCrypto.decrypt(bad, "correct horse") }.isFailure, "tampered backup rejected")
    ck(runCatching { ArogyaBackupCrypto.encrypt(payload, "short") }.isFailure, "backup password must be at least 8 characters")
    println(if (rf == 0) "ALL $rp PASS" else "$rf FAILED, $rp passed")
    if (rf > 0) kotlin.system.exitProcess(1)
}
