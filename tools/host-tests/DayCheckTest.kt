import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth
var df = 0; var dp = 0
fun d(c: Boolean, m: String) { if (c) { dp++; println("PASS $m") } else { df++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.of(2026, 11, 2)
    val L = ArogyaRegistryLogic
    // A scripted working day: 2 visits, 1 give, 1 voice receipt, 1 new person.
    val pw = Beneficiary(id = "day-pw", name = "Day Mother", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-04-06")
    val ch = Beneficiary(id = "day-ch", name = "Day Child", village = "Rampur", category = BenCategory.CHILD, dob = "2026-10-26", sex = "F")
    val r0 = RegistryData(beneficiaries = listOf(pw, ch),
        stock = listOf(
            StockTxn(date = "2026-11-01", item = "IFA tablets (adult)", qty = 200, kind = "receive"),
            StockTxn(date = "2026-11-01", item = "ORS", qty = 50, kind = "receive"),
            StockTxn(date = "2026-11-01", item = "Calcium tablets", qty = 100, kind = "receive")))
    // Visit 1: ANC with IFA 30 + calcium 60.
    var r = ArogyaOfficialForms.apply(r0, pw, ArogyaOfficialForms.MCP_ANC, 1,
        mapOf("ifa" to "30", "calcium" to "60"), mapOf("ifa" to "voice", "calcium" to "voice"), emptySet(), today)
    d(r.forms.size == 1, "visit 1 stored one form entry")
    d(L.stockBalance(r.stock)["IFA tablets (adult)"] == 170, "visit 1: IFA 200 -> 170")
    d(L.stockBalance(r.stock)["Calcium tablets"] == 40, "visit 1: calcium 100 -> 40")
    d(r.beneficiaries.first { it.id == "day-pw" }.mcp.ifaTablets == 30, "visit 1: MCP IFA total 30")
    d(r.beneficiaries.first { it.id == "day-pw" }.mcp.anc[2] == "2026-11-02", "visit 1: ANC 2 ticked on the card")
    // Visit 2: Annexure 9 day-7 weight for the child.
    r = ArogyaOfficialForms.apply(r, ch, ArogyaOfficialForms.ANNEX9, 2,
        mapOf("weight" to "2.9"), mapOf("weight" to "voice"), emptySet(), today)
    d(r.forms.count { it.values["_src"] != "annex9" } == 2, "visit 2 stored; two form entries")
    d(r.forms.any { it.beneficiaryId == ch.id && it.formId == "mcp_baby" && it.values["weight"] == "2.9" }, "visit 2 also on MCP card p.7 (7th Day)")
    d(r.forms.none { it.beneficiaryId == pw.id && it.formId == "mcp_pnc" }, "a pregnant woman in the house gets no PNC entry")
    d(r.beneficiaries.first { it.id == "day-ch" }.mcp.weights.any { it.date == "2026-11-02" && it.kg == 2.9 },
        "visit 2: weight on the MCP card")
    // Give ORS 4 to the child (keyed id).
    r = ArogyaOfficialForms.applyGive(r, ch, "ORS", 4, "day-g1", today = today)
    d(L.stockBalance(r.stock)["ORS"] == 46, "give: ORS 50 -> 46")
    // Voice receipt "received 100 ORS".
    val cat = L.DRUG_KIT_DEFAULTS.map { it.name } + "Calcium tablets"
    val spoken = "received 100 ORS from the sub-centre"
    val lines = ArogyaVoiceStock.ground(ArogyaVoiceStock.parseRules(spoken, cat), spoken, cat)
    d(lines.size == 1 && lines[0].qty == 100 && lines[0].op == "receive", "voice receipt parses ORS 100")
    r = r.copy(stock = r.stock + lines.map { ArogyaVoiceStock.toTxn(it, L.stockBalance(r.stock)["ORS"] ?: 0, today.toString()) })
    d(L.stockBalance(r.stock)["ORS"] == 146, "voice receipt: ORS 46 -> 146")
    // New person: a pregnant woman; she appears with ANC dues.
    val np = Beneficiary(id = "day-np", name = "Day New", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-08-01")
    r = r.copy(beneficiaries = r.beneficiaries + np)
    d(ArogyaSchedule.forBeneficiary(np, emptyList(), today).any { it.kind == "ANC" && !it.done },
        "new person has ANC dues")
    d(ArogyaChangeSummary.forNewPeople(listOf("Day New"), false).contains("Day New"),
        "new-person summary names her")
    // Cross-register agreement after the whole day.
    d(r.diary.count { it.beneficiaryId == "day-pw" && it.kind == DiaryKind.HOME_VISIT } == 1, "diary: ANC visit line")
    d(r.diary.count { it.beneficiaryId == "day-ch" } == 2, "diary: child visit + give lines")
    d(r.stock.all { it.kind.isNotBlank() }, "every stock line carries its kind")
    d(ArogyaChangeSummary.diff(r0, r, "day-pw", false).isNotBlank(), "what-changed summary for the ANC visit")
    val inc = L.incentiveEstimate(r, emptyList(), YearMonth.of(2026, 11))
    d(inc.total == inc.lines.sumOf { it.amount } && inc.lines.all { it.rate > 0 && it.count >= 0 },
        "monthly report internally consistent (Rs ${inc.total})")
    // Re-saving visit 1 changes nothing anywhere.
    val r2 = ArogyaOfficialForms.apply(r, pw, ArogyaOfficialForms.MCP_ANC, 1,
        mapOf("ifa" to "30", "calcium" to "60"), mapOf("ifa" to "voice", "calcium" to "voice"), emptySet(), today)
    d(r2.forms.count { it.values["_src"] != "annex9" } == 2 && L.stockBalance(r2.stock)["IFA tablets (adult)"] == 170 &&
        L.stockBalance(r2.stock)["Calcium tablets"] == 40 &&
        r2.beneficiaries.first { it.id == "day-pw" }.mcp.ifaTablets == 30, "re-save: forms, stock and card unchanged")
    println("DayCheckTest: $dp passed, $df failed")
    if (df > 0) kotlin.system.exitProcess(1)
}
