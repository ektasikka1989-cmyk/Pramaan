import com.example.arfas.*
import java.time.LocalDate
var rf = 0; var rp = 0
fun r(c: Boolean, m: String) { if (c) { rp++; println("PASS $m") } else { rf++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.of(2026, 11, 2)
    val L = ArogyaRegistryLogic
    val stock = listOf(
        StockTxn(date = "2026-11-01", item = "ORS", qty = 8, kind = "receive"),
        StockTxn(date = "2026-11-01", item = "Zinc tablets", qty = 0, kind = "receive"),
        StockTxn(date = "2026-11-01", item = "Paracetamol", qty = 50, kind = "receive"),
        StockTxn(date = "2026-11-01", item = "Albendazole", qty = 21, kind = "receive"))
    val low = L.lowItems(stock, emptyList(), today)
    r(low.any { it.name == "ORS" && it.balance == 8 && it.status == "LOW" }, "below minimum is LOW")
    r(low.any { it.name == "Zinc tablets" && it.balance == 0 && it.status == "OUT" }, "zero is OUT")
    r(low.any { it.name == "Paracetamol" && it.status == "LOW" }, "at minimum counts as LOW")
    r(low.none { it.name == "Albendazole" }, "above minimum is not listed")
    r(low.none { it.name == "Condoms" }, "unopened catalogue items are not listed")
    val text = L.refillRequest(stock, emptyList(), today, false)
    println("   " + text.replace("\n", " | "))
    r(text.contains("ORS") && text.contains("8") && text.contains("min 20"), "refill text names item + balance + min")
    r(text.contains("Zinc tablets"), "refill text lists every low item")
    r(!text.contains("Albendazole"), "refill text skips healthy items")
    r(L.refillRequest(listOf(StockTxn(date = "2026-11-01", item = "ORS", qty = 50, kind = "receive")), emptyList(), today, false)
        .contains("sufficient"), "healthy kit says sufficient")
    r(L.refillRequest(stock, emptyList(), today, true).contains("रिफिल"), "hindi refill text")
    // Sample village really has a low item for the demo card
    val sm = ArogyaSampleVillage.build(today)
    val smLow = L.lowItems(sm.registry.stock, sm.registry.stockItems, today)
    r(smLow.any { it.name == "Zinc tablets" }, "sample village lists low zinc")
    println(if (rf == 0) "ALL $rp PASS" else "$rf FAILED, $rp passed")
    if (rf > 0) kotlin.system.exitProcess(1)
}
