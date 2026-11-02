import com.example.arfas.*
import java.time.LocalDate
var lf = 0; var lp = 0
fun l(c: Boolean, m: String) { if (c) { lp++; println("PASS $m") } else { lf++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.of(2026, 11, 2)
    val pw = Beneficiary(id = "t-pw", name = "Test Mother", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-04-06")
    val ch = Beneficiary(id = "t-ch", name = "Test Child", village = "Rampur", category = BenCategory.CHILD, dob = "2026-09-01")
    val r0 = RegistryData(beneficiaries = listOf(pw, ch),
        stock = listOf(StockTxn(date = "2026-11-01", item = "IFA tablets (adult)", qty = 200, kind = "receive"),
            StockTxn(date = "2026-11-01", item = "ORS", qty = 50, kind = "receive")))
    // Give IFA 30: stock, MCP card, diary, record — once
    val r1 = ArogyaOfficialForms.applyGive(r0, pw, "IFA tablets (adult)", 30, "g1", today = today)
    l(ArogyaRegistryLogic.stockBalance(r1.stock)["IFA tablets (adult)"] == 170, "give deducts stock once (200 -> 170)")
    l(r1.beneficiaries.first { it.id == "t-pw" }.mcp.ifaTablets == 30, "give updates MCP IFA total (0 -> 30)")
    l(r1.diary.count { it.beneficiaryId == "t-pw" && it.kind == DiaryKind.MEDICINE } == 1, "give adds one diary line")
    l(r1.beneficiaries.first { it.id == "t-pw" }.updatedAt >= pw.updatedAt, "give touches the record")
    l(r1.stock.count { ArogyaOfficialForms.giveKey(it.note) == "g1" } == 1, "give line carries the keyed id")
    l(r1.stock.first { ArogyaOfficialForms.giveKey(it.note) == "g1" }.source == "manual", "give records its origin")
    // Re-save with the same id: nothing doubles
    val r2 = ArogyaOfficialForms.applyGive(r1, pw, "IFA tablets (adult)", 30, "g1", today = today)
    l(ArogyaRegistryLogic.stockBalance(r2.stock)["IFA tablets (adult)"] == 170, "re-save keeps stock at 170")
    l(r2.beneficiaries.first { it.id == "t-pw" }.mcp.ifaTablets == 30, "re-save keeps IFA total at 30")
    l(r2.diary.count { it.beneficiaryId == "t-pw" } == 1, "re-save keeps one diary line")
    // Edit the same give to 40: delta applied, still one line each
    val r3 = ArogyaOfficialForms.applyGive(r2, pw, "IFA tablets (adult)", 40, "g1", today = today)
    l(ArogyaRegistryLogic.stockBalance(r3.stock)["IFA tablets (adult)"] == 160, "edit adjusts stock to 160")
    l(r3.beneficiaries.first { it.id == "t-pw" }.mcp.ifaTablets == 40, "edit adjusts IFA total to 40")
    l(r3.stock.count { it.beneficiaryId == "t-pw" } == 1 && r3.diary.count { it.beneficiaryId == "t-pw" } == 1, "edit keeps one stock + one diary line")
    // A second give with a new id accumulates
    val r4 = ArogyaOfficialForms.applyGive(r3, pw, "IFA tablets (adult)", 30, "g2", today = today)
    l(r4.beneficiaries.first { it.id == "t-pw" }.mcp.ifaTablets == 70, "second give accumulates (40 + 30)")
    // Non-IFA give: stock + diary, card untouched
    val r5 = ArogyaOfficialForms.applyGive(r4, ch, "ORS", 4, "g3", today = today)
    l(ArogyaRegistryLogic.stockBalance(r5.stock)["ORS"] == 46, "ORS give deducts stock")
    l(r5.diary.any { it.beneficiaryId == "t-ch" && it.note.contains("ORS") }, "ORS give adds a diary line")
    l(r5.beneficiaries.first { it.id == "t-ch" }.mcp == ch.mcp, "non-IFA give leaves the card alone")
    // Key parsing
    l(ArogyaOfficialForms.giveKey("Given to X [give#abc-123]") == "abc-123", "giveKey parses the id")
    l(ArogyaOfficialForms.giveKey("Given to X") == null, "unkeyed legacy note has no id")
    // Visit saves still work and now stamp their origin
    val r6 = ArogyaOfficialForms.apply(r5, pw, ArogyaOfficialForms.MCP_ANC, 1, mapOf("ifa" to "30"), mapOf("ifa" to "asha"), emptySet(), today)
    l(r6.stock.any { it.beneficiaryId == "t-pw" && it.source == "visit" && it.qty == -30 }, "visit stock line stamped with origin")
    l(r6.beneficiaries.first { it.id == "t-pw" }.mcp.ifaTablets == 100, "visit IFA adds to give IFA (70 + 30)")
    println(if (lf == 0) "ALL $lp PASS" else "$lf FAILED, $lp passed")
    if (lf > 0) kotlin.system.exitProcess(1)
}
