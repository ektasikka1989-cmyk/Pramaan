import com.example.arfas.*
var af = 0; var ap = 0
fun a(c: Boolean, m: String) { if (c) { ap++; println("PASS $m") } else { af++; println("FAIL $m") } }
fun main() {
    val m = mapOf("BCG" to "BCG", "OPV-0" to "OPV0", "OPV 2" to "OPV2", "Penta-1" to "PENTA1", "Pentavalent 3" to "PENTA3", "Hep B" to "HEPB0", "Rota 2" to "RVV2",
        "fIPV-1" to "FIPV1", "IPV 2" to "FIPV2", "PCV-1" to "PCV1", "PCV booster" to "PCVB", "MR-1" to "MR1", "Measles 2" to "MR2", "DPT Booster-1" to "DPTB1", "DPT B2" to "DPTB2", "Vitamin A" to "VITA1", "OPV booster" to "OPVB")
    m.forEach { (k, v) -> a(ArogyaCardApply.vaccineCode(k) == v, "$k -> $v (got ${ArogyaCardApply.vaccineCode(k)})") }
    a(ArogyaCardApply.vaccineCode("Paracetamol") == null, "unknown name -> null")
    val b = Beneficiary(id = "x", name = "Demo", category = BenCategory.CHILD, dob = "")
    val out = ArogyaCardApply.apply(b, listOf(ArogyaCardField("birth_date", "Date of birth", "2026-09-12", "e"), ArogyaCardField("birth_weight", "Birth weight", "2.6 kg", "e"),
        ArogyaCardField("vaccine", "Vaccine", "BCG — 2026-09-12", "e"), ArogyaCardField("vaccine", "Vaccine", "Penta-1", "e"), ArogyaCardField("weight", "Weight", "3.1 kg on 2026-10-24", "e")))
    a(out.dob == "2026-09-12" && out.birthWeightKg == 2.6, "DOB and birth weight applied")
    a(out.mcp.vaccines["BCG"] == "2026-09-12" && !out.mcp.vaccines.containsKey("PENTA1"), "dated vaccine applied; undated one never gets today's date")
    a(out.mcp.weights.any { it.date == "2026-10-24" && it.kg == 3.1 }, "weight applied with date")
    a(ArogyaAiCatalog.features.count { it.metaModel } >= 8 && ArogyaAiCatalog.features.any { !it.metaModel && it.model.contains("ML Kit") }, "catalog labels Meta and non-Meta models")
    println(if (af == 0) "ALL $ap PASS" else "$af FAILED, $ap passed")
    if (af > 0) kotlin.system.exitProcess(1)
}
