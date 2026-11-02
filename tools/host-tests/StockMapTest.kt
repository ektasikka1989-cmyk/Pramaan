import com.example.arfas.*
import java.time.LocalDate
var sf = 0; var sp = 0
fun s(c: Boolean, m: String) { if (c) { sp++; println("PASS $m") } else { sf++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.of(2026, 11, 2)
    val F = ArogyaOfficialForms
    val items = listOf("ORS", "Zinc tablets", "IFA tablets (adult)", "IFA syrup (child)", "Condoms", "Oral contraceptive pills",
        "Sanitary napkins", "Calcium tablets", "Albendazole", "Paracetamol", "Cotrimoxazole", "Rapid diagnostic test (malaria)",
        "Chloroquine / ACT", "Emergency contraceptive pill", "Pregnancy test kit")
    val pw = Beneficiary(id = "m-pw", name = "M", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-04-06")
    val ch = Beneficiary(id = "m-ch", name = "C", village = "Rampur", category = BenCategory.CHILD, dob = "2025-06-01")
    val ec = Beneficiary(id = "m-ec", name = "E", village = "Rampur", category = BenCategory.OTHER)
    val r0 = RegistryData(beneficiaries = listOf(pw, ch, ec),
        stock = items.map { StockTxn(date = "2026-11-01", item = it, qty = 100, kind = "receive") })
    fun bal(r: RegistryData) = ArogyaRegistryLogic.stockBalance(r.stock)
    // ANC visit: IFA 30 + calcium 60 + deworming
    val r1 = F.apply(r0, pw, F.MCP_ANC, 1, mapOf("ifa" to "30", "calcium" to "60", "albendazole" to "1"), mapOf("ifa" to "asha"), emptySet(), today)
    s(bal(r1)["IFA tablets (adult)"] == 70 && bal(r1)["Calcium tablets"] == 40 && bal(r1)["Albendazole"] == 99, "ANC deducts ifa + calcium + albendazole")
    s(r1.beneficiaries.first { it.id == "m-pw" }.mcp.ifaTablets == 30, "ANC visit adds to IFA total")
    val r2 = F.apply(r1, pw, F.MCP_ANC, 1, mapOf("ifa" to "30", "calcium" to "60", "albendazole" to "1"), mapOf("ifa" to "asha"), emptySet(), today)
    s(bal(r2) == bal(r1), "ANC re-save deducts nothing more")
    val r3 = F.apply(r2, pw, F.MCP_ANC, 1, emptyMap(), emptyMap(), emptySet(), today)
    s(bal(r3)["Calcium tablets"] == 100 && bal(r3)["Albendazole"] == 100, "dropped items clear their lines")
    s(r3.beneficiaries.first { it.id == "m-pw" }.mcp.ifaTablets == 0, "dropped ifa restores the IFA total")
    // Sick child: full treatment set
    val sick = mapOf("ors" to "4", "zinc" to "10", "parac" to "6", "ctz" to "8", "mal_rdt" to "1", "act" to "12")
    val r4 = F.apply(r3, ch, F.SICK, 0, sick, sick.mapValues { "asha" }, emptySet(), today)
    s(bal(r4)["ORS"] == 96 && bal(r4)["Zinc tablets"] == 90 && bal(r4)["Paracetamol"] == 94, "sick visit deducts ors + zinc + paracetamol")
    s(bal(r4)["Cotrimoxazole"] == 92 && bal(r4)["Rapid diagnostic test (malaria)"] == 99 && bal(r4)["Chloroquine / ACT"] == 88, "sick visit deducts ctz + rdt + act")
    val r5 = F.apply(r4, ch, F.SICK, 0, sick, sick.mapValues { "asha" }, emptySet(), today)
    s(bal(r5) == bal(r4), "sick re-save deducts nothing more")
    // Same child, HBYC column: ors lines stay separate per form
    val r6 = F.apply(r5, ch, F.HBYC, 3, mapOf("ors" to "2", "ifa_syrup" to "1", "albendazole" to "2"), mapOf("ors" to "asha"), emptySet(), today)
    s(bal(r6)["ORS"] == 94 && bal(r6)["IFA syrup (child)"] == 99 && bal(r6)["Albendazole"] == 98, "HBYC deducts ors + syrup + albendazole")
    val r7 = F.apply(r6, ch, F.SICK, 0, sick - "ors", sick.mapValues { "asha" }, emptySet(), today)
    s(bal(r7)["ORS"] == 98, "dropping sick ors keeps the HBYC ors line")
    // Eligible couple: supplies
    val fp = mapOf("condoms" to "10", "ocp" to "2", "napkins" to "1", "ecp" to "1", "ptk" to "1")
    val r8 = F.apply(r7, ec, F.FP, 0, fp, fp.mapValues { "asha" }, emptySet(), today)
    s(bal(r8)["Condoms"] == 90 && bal(r8)["Oral contraceptive pills"] == 98 && bal(r8)["Sanitary napkins"] == 99, "FP deducts condoms + ocp + napkins")
    s(bal(r8)["Emergency contraceptive pill"] == 99 && bal(r8)["Pregnancy test kit"] == 99, "FP deducts ecp + ptk")
    val r9 = F.apply(r8, ec, F.FP, 0, fp, fp.mapValues { "asha" }, emptySet(), today)
    s(bal(r9) == bal(r8), "FP re-save deducts nothing more")
    // The fields exist where the spec puts them
    s(F.fieldsFor(F.MCP_ANC, 0).any { it.id == "calcium" } && F.fieldsFor(F.MCP_ANC, 0).any { it.id == "albendazole" }, "ANC has calcium + albendazole")
    s(listOf("parac", "ctz", "mal_rdt", "act").all { id -> F.fieldsFor(F.SICK, 0).any { it.id == id } }, "sick treatment has parac + ctz + rdt + act")
    s(F.fieldsFor(F.FP, 0).any { it.id == "ecp" } && F.fieldsFor(F.FP, 0).any { it.id == "ptk" && it.section == "Medicines given" }, "FP has ecp + Medicines-given ptk")
    s(F.fieldsFor(F.HBYC, 3).any { it.id == "albendazole" } && F.fieldsFor(F.HBYC, 0).none { it.id == "albendazole" }, "HBYC deworming from 12 months only")
    // Catalogue + sample village carry calcium
    s(ArogyaRegistryLogic.catalog(emptyList()).any { it.name == "Calcium tablets" && it.min == 100 }, "calcium in the catalogue")
    val sm = ArogyaSampleVillage.build(today)
    s(ArogyaRegistryLogic.stockBalance(sm.registry.stock)["Calcium tablets"] == 300, "sample village opens calcium at 300")
    println(if (sf == 0) "ALL $sp PASS" else "$sf FAILED, $sp passed")
    if (sf > 0) kotlin.system.exitProcess(1)
}
