import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth

var ff = 0; var fp = 0
fun t(c: Boolean, m: String) { if (c) { fp++; println("PASS $m") } else { ff++; println("FAIL $m") } }

// SYNTHETIC records only.
fun main() {
    val today = LocalDate.of(2026, 10, 31)
    val m = YearMonth.of(2026, 10)
    val h1 = "H-12 Ram Lal"; val h2 = "H-13 Shyam"
    val baby = Beneficiary(id = "c1", name = "Demo Baby", household = h1, village = "Rampur", category = BenCategory.CHILD, dob = "2026-08-20", deliveryPlace = "INSTITUTION",
        mcp = McpCard(vaccines = mapOf("BCG" to "2026-08-20", "OPV0" to "2026-08-20", "HEPB0" to "2026-08-20")))
    val mother = Beneficiary(id = "m1", name = "Demo Mother", household = h1, village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-06-01", mcp = McpCard(anc = mapOf(1 to "2026-07-20")))
    val ec = Beneficiary(id = "e1", name = "Demo Couple", household = h2, village = "Rampur", category = BenCategory.ELIGIBLE_COUPLE, familyPlanning = "Condom")
    val data = RegistryData(beneficiaries = listOf(baby, mother, ec),
        diary = listOf(DiaryEntry(date = "2026-10-05", kind = DiaryKind.VHND), DiaryEntry(date = "2026-10-19", kind = DiaryKind.VHND),
            DiaryEntry(date = "2026-10-10", kind = DiaryKind.PHC_MEETING), DiaryEntry(date = "2026-10-12", kind = DiaryKind.VHSNC_MEETING)))
    // 1. Households / village health register
    val vhr = ArogyaRegistryLogic.villageRegister(data.beneficiaries)
    t(vhr.households == 2 && vhr.people == 3, "village register: 2 households, 3 people")
    t(ArogyaRegistryLogic.byHousehold(data.beneficiaries)[h1]?.size == 2, "people grouped by household")
    t(ArogyaRegistryCodec.decode(ArogyaRegistryCodec.encode(data)) == data, "household survives save/load")
    // 2. Monthly lists (incentive items 4c–4e)
    val imm = ArogyaRegistryLogic.immunizationDueList(data.beneficiaries, YearMonth.of(2026, 11), today)
    println("   immunization due: " + imm.map { it.label + " " + it.due })
    t(imm.any { it.beneficiaryId == "c1" && it.label.contains("6 weeks") }, "6-week vaccines (due 1 Oct, not given) on November due list as pending")
    t(imm.none { it.label.contains("Birth") }, "given birth doses not on due list")
    val anc = ArogyaRegistryLogic.ancList(data.beneficiaries, today)
    t(anc.size == 1 && anc[0].first.id == "m1" && anc[0].second.contains("ANC 2"), "ANC list shows next ANC for pregnant woman")
    t(ArogyaRegistryLogic.eligibleCouples(data.beneficiaries).map { it.id } == listOf("e1"), "eligible couple list")
    // 3. Incentive estimate from diary + visits
    val v1 = ArogyaVisit(personId = "c1", name = "Demo Baby", date = "2026-10-25", completed = true)   // ~month 2: not an HBYC slot
    val inc = ArogyaRegistryLogic.incentiveEstimate(data, listOf(v1), m)
    inc.lines.forEach { println("   ${it.item}: ${it.count} x Rs ${it.rate} = ${it.amount}") }
    t(inc.lines.first { it.item.startsWith("VHND") }.amount == 400, "2 VHND sessions = Rs 400")
    t(inc.lines.first { it.item.startsWith("Monthly meeting at PHC") }.amount == 150, "PHC monthly meeting = Rs 150")
    t(inc.lines.first { it.item.startsWith("VHSNC") }.amount == 150, "VHSNC meeting = Rs 150")
    t(inc.total == inc.lines.sumOf { it.amount }, "total = sum of lines")
    // HBNC complete: 6 visits in windows for institutional birth
    val nb = Beneficiary(id = "c9", name = "Demo Newborn", category = BenCategory.CHILD, dob = "2026-09-01", deliveryPlace = "INSTITUTION")
    val hbncVisits = listOf(3L, 7L, 14L, 21L, 28L, 42L).map { d -> ArogyaVisit(personId = "c9", name = "Demo Newborn", date = LocalDate.of(2026, 9, 1).plusDays(d - 1).toString(), completed = true) }
    val inc2 = ArogyaRegistryLogic.incentiveEstimate(RegistryData(beneficiaries = listOf(nb)), hbncVisits, YearMonth.of(2026, 10))
    t(inc2.lines.first { it.item.startsWith("HBNC") }.count == 1 && inc2.lines.first { it.item.startsWith("HBNC") }.amount == 250, "all 6 HBNC visits done (last in October) = Rs 250 in October")
    println(if (ff == 0) "ALL $fp PASS" else "$ff FAILED, $fp passed")
    if (ff > 0) kotlin.system.exitProcess(1)
}
