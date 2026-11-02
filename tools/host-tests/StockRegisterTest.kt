import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth

var sf = 0; var sp = 0
fun s(c: Boolean, m: String) { if (c) { sp++; println("PASS $m") } else { sf++; println("FAIL $m") } }

// Item 5: drug kit stock register (SYNTHETIC records only).
fun main() {
    val today = LocalDate.of(2026, 11, 1)
    val month = YearMonth.of(2026, 11)
    val cat = ArogyaRegistryLogic.DRUG_KIT_DEFAULTS
    // 1. Opening / received / given / balance for one month.
    val stock = listOf(
        StockTxn(id = "r1", date = "2026-10-05", item = "ORS", qty = 50, note = "Opening", kind = "receive", batch = "B-1", expiry = "2027-09-30", source = "Sub-centre"),
        StockTxn(id = "r2", date = "2026-11-03", item = "ORS", qty = 20, note = "Received", kind = "receive", batch = "B-2", expiry = "2027-10-31", source = "Sub-centre"),
        StockTxn(id = "g1", date = "2026-11-04", item = "ORS", qty = -8, beneficiaryId = "p1", note = "Given", kind = "give"),
        StockTxn(id = "g0", date = "2026-10-20", item = "ORS", qty = -4, beneficiaryId = "p2", note = "Given", kind = "give"))
    val reg = ArogyaRegistryLogic.stockRegister(stock, cat, month, today, false)
    s(reg.headers == listOf("Item", "Unit", "Opening", "Received", "Given", "Balance", "Minimum", "Expiry", "Status"), "stock columns exact")
    val ors = reg.rows.firstOrNull { it.cells[0] == "ORS" }
    s(ors != null && ors.cells[1] == "packets" && ors.cells[2] == "46" && ors.cells[3] == "20" && ors.cells[4] == "8" &&
        ors.cells[5] == "58" && ors.cells[6] == "20" && ors.cells[7] == "2027-09-30" && ors.status == "OK", "ORS opening 46 / received 20 / given 8 / balance 58 / OK")
    // 2. Statuses: OUT, LOW, EXPIRED, EXPIRING.
    val z = ArogyaRegistryLogic.stockRegister(
        listOf(StockTxn(date = "2026-10-05", item = "Zinc tablets", qty = 100, kind = "receive", expiry = "2027-06-30"),
            StockTxn(date = "2026-11-04", item = "Zinc tablets", qty = -85, beneficiaryId = "p1", kind = "give")), cat, month, today, false)
    s(z.rows.first { it.cells[0] == "Zinc tablets" }.status == "LOW", "zinc 15 <= min 30 -> LOW")
    val out = ArogyaRegistryLogic.stockRegister(
        listOf(StockTxn(date = "2026-10-05", item = "ORS", qty = 5, kind = "receive"),
            StockTxn(date = "2026-11-04", item = "ORS", qty = -5, beneficiaryId = "p1", kind = "give")), emptyList(), month, today, false)
    s(out.rows.first { it.cells[0] == "ORS" }.status == "OUT", "balance 0 -> OUT")
    val exp = ArogyaRegistryLogic.stockRegister(
        listOf(StockTxn(date = "2026-10-05", item = "ORS", qty = 5, kind = "receive", expiry = "2026-09-01")), emptyList(), month, today, false)
    s(exp.rows.first { it.cells[0] == "ORS" }.status == "EXPIRED", "past batch expiry -> EXPIRED")
    val soon = ArogyaRegistryLogic.stockRegister(
        listOf(StockTxn(date = "2026-10-05", item = "ORS", qty = 50, kind = "receive", expiry = "2026-12-20")), emptyList(), month, today, false)
    s(soon.rows.first { it.cells[0] == "ORS" }.status == "EXPIRING", "expiry within 90 days -> EXPIRING")
    // 3. Adjustments/write-offs move the balance, not received/given.
    val adj = ArogyaRegistryLogic.stockRegister(
        listOf(StockTxn(date = "2026-11-04", item = "ORS", qty = 50, kind = "receive"),
            StockTxn(date = "2026-11-05", item = "ORS", qty = -3, kind = "adjust", reason = "spillage"),
            StockTxn(date = "2026-11-06", item = "ORS", qty = -2, kind = "writeoff", reason = "damaged")), emptyList(), month, today, false)
    val ar = adj.rows.first { it.cells[0] == "ORS" }
    s(ar.cells[3] == "50" && ar.cells[4] == "0" && ar.cells[5] == "45", "adjust/writeoff move balance only")
    // 4. Legacy lines (no kind) derive: positive -> receive, negative+person -> give.
    s(ArogyaRegistryLogic.txnKind(StockTxn(item = "ORS", qty = 5, note = "Received")) == "receive", "legacy positive -> receive")
    s(ArogyaRegistryLogic.txnKind(StockTxn(item = "ORS", qty = -2, beneficiaryId = "p1")) == "give", "legacy negative+person -> give")
    s(ArogyaRegistryLogic.txnKind(StockTxn(item = "ORS", qty = -2, note = "counted")) == "adjust", "legacy negative alone -> adjust")
    // 5. History newest-first; refill text names low/out items.
    s(ArogyaRegistryLogic.stockHistory(stock, "ORS").map { it.id } == listOf("g1", "r2", "g0", "r1"), "history newest first")
    val req = ArogyaRegistryLogic.refillRequest(
        listOf(StockTxn(date = "2026-10-05", item = "Zinc tablets", qty = 15, kind = "receive"),
            StockTxn(date = "2026-10-05", item = "ORS", qty = 50, kind = "receive")), cat, today, false)
    s(req.startsWith("Request: drug kit refill:") && req.contains("Zinc tablets") && !req.contains("ORS:"), "refill request lists only low items")
    // 6. Codec round-trip with new fields; legacy JSON without them still decodes.
    val r = RegistryData(stock = stock, stockItems = listOf(StockItem("ORS", "packets", 20), StockItem("Custom syrup", "bottles", 2)))
    val rt = ArogyaRegistryCodec.decode(ArogyaRegistryCodec.encode(r))
    val rtTxn = rt.stock.first { it.id == "r1" }
    s(rtTxn.batch == "B-1" && rtTxn.expiry == "2027-09-30" && rtTxn.source == "Sub-centre" && rtTxn.kind == "receive", "txn fields round-trip")
    s(rt.stockItems.size == 2 && rt.stockItems.any { it.name == "Custom syrup" && it.min == 2 }, "catalogue round-trips")
    s(ArogyaRegistryLogic.catalog(emptyList()).size == 16 && ArogyaRegistryLogic.catalog(rt.stockItems).size == 17, "catalogue = defaults + custom")
    val legacy = ArogyaRegistryCodec.decode("""{"version":1,"beneficiaries":[],"diary":[],"stock":[{"id":"l1","date":"2026-10-01","item":"ORS","qty":50,"ben":"","note":"Received"}],"vitals":[],"forms":[]}""")
    s(legacy.stock.size == 1 && legacy.stockItems.isEmpty() && ArogyaRegistryLogic.catalog(legacy.stockItems).size == 16, "legacy backup decodes, defaults fill in")
    // 7. Sample village: opening for every kit item, no red zeros; zinc low, RDT expiring.
    val sm = ArogyaSampleVillage.build(today).registry
    val smReg = ArogyaRegistryLogic.stockRegister(sm.stock, sm.stockItems, month, today, false)
    s(smReg.rows.size == 16, "sample register covers all 16 kit items")
    s(smReg.rows.all { it.cells[5].toInt() > 0 }, "every sample item has a positive balance")
    s(smReg.rows.first { it.cells[0] == "Zinc tablets" }.status == "LOW", "sample zinc LOW by design")
    s(smReg.rows.first { it.cells[0] == "Rapid diagnostic test (malaria)" }.status == "EXPIRING", "sample RDT EXPIRING by design")
    s(ArogyaRegistryLogic.stockBalance(sm.stock)["ORS"] == 46, "ORS still 46 after seedDemo givens")
    val f5 = ArogyaForms.drugKitCard(sm.stock)
    s(f5.rows.any { it[1] == "ORS" && it.contains("50") }, "Annexure 5 still shows the ORS 50 refill")
    println(if (sf == 0) "ALL $sp PASS" else "$sf FAILED, $sp passed")
    if (sf > 0) kotlin.system.exitProcess(1)
}
