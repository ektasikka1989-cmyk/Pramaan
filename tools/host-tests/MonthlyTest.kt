import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth
var mf = 0; var mp = 0
fun m(c: Boolean, msg: String) { if (c) { mp++; println("PASS $msg") } else { mf++; println("FAIL $msg") } }
fun main() {
    val month = YearMonth.of(2026, 11)
    val L = ArogyaRegistryLogic
    // A 20-month-old finishing the 16–24 month contact in November
    val toddler = Beneficiary(id = "o-t", name = "T", village = "Rampur", category = BenCategory.CHILD, dob = "2025-03-10",
        mcp = McpCard(vaccines = mapOf("BCG" to "2025-03-10", "OPV0" to "2025-03-10", "HEPB0" to "2025-03-10",
            "OPV1" to "2025-04-21", "PENTA1" to "2025-04-21", "RVV1" to "2025-04-21", "FIPV1" to "2025-04-21", "PCV1" to "2025-04-21",
            "OPV2" to "2025-05-19", "PENTA2" to "2025-05-19", "RVV2" to "2025-05-19",
            "OPV3" to "2025-06-16", "PENTA3" to "2025-06-16", "RVV3" to "2025-06-16", "FIPV2" to "2025-06-16", "PCV2" to "2025-06-16",
            "MR1" to "2025-12-12", "VITA1" to "2025-12-12", "PCVB" to "2025-12-12",
            "MR2" to "2026-11-05", "DPTB1" to "2026-11-05", "OPVB" to "2026-11-05")))
    val pw = Beneficiary(id = "o-pw", name = "P", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-03-01",
        mcp = McpCard(anc = mapOf(1 to "2026-04-20", 2 to "2026-06-15", 3 to "2026-09-10", 4 to "2026-11-03")))
    val ec = Beneficiary(id = "o-ec", name = "E", village = "Rampur", category = BenCategory.ELIGIBLE_COUPLE)
    val data = RegistryData(
        beneficiaries = listOf(toddler, pw, ec),
        diary = listOf(DiaryEntry(date = "2026-11-12", kind = DiaryKind.VHND), DiaryEntry(date = "2026-11-20", kind = DiaryKind.PHC_MEETING)),
        vitals = listOf(VitalEvent(date = "2026-11-08", kind = VitalKind.BIRTH, place = "PHC Rampur"),
            VitalEvent(date = "2026-11-09", kind = VitalKind.BIRTH, place = "Home")),
        forms = listOf(FormEntry(beneficiaryId = "o-t", formId = "sick", column = 0, date = "2026-11-06",
            values = mapOf("rdt" to "Yes", "mal_rdt" to "2", "act" to "12"))))
    val inc = L.incentiveEstimate(data, emptyList(), month)
    fun count(label: String) = inc.lines.first { it.item == label }.count
    m(count("Complete immunization up to 2 years") == 1, "16–24 mo contact done in month = Rs 75 line")
    m(count("DPT booster at 5–6 years") == 0, "5–6 y booster not done = no line")
    m(count("JSY: antenatal care, 4 ANC (rural)") == 1, "4 ANC with last in month = Rs 300")
    m(count("JSY: institutional delivery facilitated (rural)") == 1, "only the PHC birth counts, not home")
    m(count("Malaria RDT testing") == 2, "2 RDT kits used = count 2")
    m(count("ACT complete treatment (RDT positive)") == 1, "RDT-positive + ACT = 1 case")
    m(count("VHND session mobilised") == 1 && count("Monthly meeting at PHC") == 1, "diary meetings counted")
    m(count("Village health register maintained") == 1 && count("Due list of children prepared") == 1, "register flats for village + due list")
    m(count("ANC beneficiaries list prepared") == 1 && count("Eligible couples list prepared") == 1, "register flats for ANC + EC lists")
    m(count("HBNC visits completed (6/7 visits)") == 0, "no HBNC completion this month")
    // Totals equal the sum of the records
    val expected = mapOf("Complete immunization up to 2 years" to 75, "JSY: antenatal care, 4 ANC (rural)" to 300,
        "JSY: institutional delivery facilitated (rural)" to 300, "Malaria RDT testing" to 30, "ACT complete treatment (RDT positive)" to 200,
        "VHND session mobilised" to 200, "Monthly meeting at PHC" to 150, "Village health register maintained" to 300,
        "Due list of children prepared" to 300, "ANC beneficiaries list prepared" to 300, "Eligible couples list prepared" to 300)
    m(inc.total == expected.values.sum(), "total Rs ${inc.total} equals the sum of the records (${expected.values.sum()})")
    m(inc.lines.all { it.amount == it.count * it.rate }, "every line amount = count × rate")
    // Rates are the 2025 table, untouched by AI
    val table = L.INCENTIVE_2025.toMap()
    m(table["hbnc"]?.second == 250 && table["imm2"]?.second == 75 && table["rdt"]?.second == 15, "2025 rates: hbnc 250, imm2 75, rdt 15")
    m(table["jsyAnc"]?.second == 300 && table["act"]?.second == 200 && table["vhr"]?.second == 300, "2025 rates: jsy 300, act 200, vhr 300")
    // The printable claim carries the lines + total + watermark flag
    val rep = L.monthlyReport(data, emptyList(), month)
    val ft = L.monthlyTable(rep, inc, month, false, true)
    m(ft.rows.any { it.size == 4 && it[0] == "JSY: institutional delivery facilitated (rural)" && it[3] == "Rs 300" }, "claim table has the JSY line")
    m(ft.rows.any { it.size == 4 && it[0] == "Estimated total" && it[3] == "Rs ${inc.total}" }, "claim table totals correctly")
    m(ft.sample && ft.sign, "claim PDF gets SAMPLE watermark + signature line")
    println(if (mf == 0) "ALL $mp PASS" else "$mf FAILED, $mp passed")
    if (mf > 0) kotlin.system.exitProcess(1)
}
