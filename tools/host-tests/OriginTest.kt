import com.example.arfas.*
import java.time.LocalDate
var ef = 0; var ep = 0
fun e(c: Boolean, m: String) { if (c) { ep++; println("PASS $m") } else { ef++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.of(2026, 11, 2)
    val F = ArogyaOfficialForms
    val pw = Beneficiary(id = "e-pw", name = "E Mother", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-04-06")
    val r0 = RegistryData(beneficiaries = listOf(pw),
        stock = listOf(StockTxn(date = "2026-11-01", item = "IFA tablets (adult)", qty = 200, kind = "receive", note = "Opening stock"),
            StockTxn(date = "2026-11-01", item = "ORS", qty = 50, kind = "receive", note = "Opening stock")))
    val r1 = F.apply(r0, pw, F.MCP_ANC, 1, mapOf("ifa" to "30"), mapOf("ifa" to "ai"), emptySet(), today)
    val r2 = F.applyGive(r1, pw, "ORS", 4, "g5", today = today)
    // Stock lines
    val vt = r1.stock.first { it.qty < 0 }
    val vo = ArogyaOrigin.stock(vt, "E Mother", false)
    e(vo.lines.any { it.contains("Given at a home visit") } && vo.lines.any { it.contains("ANC 2") }, "visit stock line names the form + column")
    e(vo.lines.any { it.contains("2 Nov") } && vo.lines.any { it.startsWith("✓ Confirmed") }, "visit stock line has date + confirmation")
    val gt = r2.stock.first { F.giveKey(it.note) == "g5" }
    val go = ArogyaOrigin.stock(gt, "E Mother", false)
    e(go.lines.any { it.contains("Given to E Mother") } && go.lines.any { it.contains("Typed by the ASHA") }, "give line shows person + manual origin")
    val wt = ArogyaVoiceStock.toTxn(VoiceStockLine("ORS", 100, "receive", "ors · 100"), 46, "2026-11-02")
    val wo = ArogyaOrigin.stock(wt, "", false)
    e(wo.lines.any { it.contains("voice note", ignoreCase = true) } && wo.lines.any { it.contains("ors · 100") }, "voice line shows the transcript evidence")
    val st = StockTxn(date = "2026-11-02", item = "ORS", qty = 20, kind = "receive", note = "Scan: Received")
    e(ArogyaOrigin.stock(st, "", false).lines.any { it.contains("photo scan", ignoreCase = true) }, "scan line recognised")
    val mt = r0.stock.first()
    e(ArogyaOrigin.stock(mt, "", false).lines.any { it.contains("Stock received") }, "plain receive is manual")
    // Diary lines
    val vd = r1.diary.first { it.kind == DiaryKind.HOME_VISIT }
    val vdo = ArogyaOrigin.diary(vd, r1.stock, false)
    e(vdo.lines.any { it.contains("automatically") } && vdo.lines.any { it.startsWith("✓ Confirmed") }, "visit diary is automatic + confirmed")
    val gd = r2.diary.first { it.kind == DiaryKind.MEDICINE }
    e(ArogyaOrigin.diary(gd, r2.stock, false).lines.any { it.contains("Typed by the ASHA") }, "give diary links the give origin")
    val rd = DiaryEntry(date = "2026-11-02", beneficiaryId = "e-pw", note = "Registered: E Mother · from photo\nSaved ✓ — Registered: E Mother")
    val rdo = ArogyaOrigin.diary(rd, emptyList(), false)
    e(rdo.lines[0] == "Registered: E Mother" && rdo.lines.any { it == "Source: from photo" }, "registration diary shows its source")
    // Form cells
    val c1 = ArogyaOrigin.formCell("Iron (IFA) tablets given", "30", "ai", "2026-11-02", false, false)
    e(c1.lines.any { it.contains("AI proposal") } && c1.lines.any { it.startsWith("✓ Confirmed") }, "ai cell is a confirmed proposal")
    e(ArogyaOrigin.formCell("X", "1", "rule", "2026-11-02", false, false).lines.any { it.contains("Fixed government rule") }, "rule cell is not AI")
    val c3 = ArogyaOrigin.formCell("X", "1", "demo", "2026-11-02", true, false)
    e(c3.lines.any { it.contains("Sample data") } && c3.lines.none { it.startsWith("✓ Confirmed") }, "sample cell says so")
    // MCP card contributions
    val b2 = r2.beneficiaries.first { it.id == "e-pw" }.copy(mcp = r2.beneficiaries.first { it.id == "e-pw" }.mcp.copy(
        weights = listOf(WeightEntry("2026-11-02", 52.0)), vaccines = mapOf("BCG" to "2026-11-02")))
    val mo = ArogyaOrigin.mcp(b2, r2.forms, r2.stock, false)
    e(mo.any { it.label.startsWith("Weight 52.0") } && mo.any { it.label.startsWith("BCG") } && mo.any { it.label.startsWith("ANC 2") }, "mcp lists weight + vaccine + anc")
    e(mo.any { it.label.startsWith("IFA total 30") && it.info.lines.any { l -> l.contains("ANC 2") } }, "mcp ifa total traces to its ANC visit")
    // Hindi
    e(ArogyaOrigin.stock(vt, "E Mother", true).lines.any { it.contains("घर विज़िट") }, "hindi visit origin")
    e(ArogyaOrigin.confirmed(true).contains("ASHA"), "hindi confirmation line")
    println(if (ef == 0) "ALL $ep PASS" else "$ef FAILED, $ep passed")
    if (ef > 0) kotlin.system.exitProcess(1)
}
