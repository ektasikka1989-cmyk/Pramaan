import com.example.arfas.*
import java.time.LocalDate
var of = 0; var op = 0
fun o(c: Boolean, m: String) { if (c) { op++; println("PASS $m") } else { of++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.now()
    val sm = ArogyaSampleVillage.build(today)
    val bens = sm.registry.beneficiaries
    // RI Form 3 — exact official columns
    val f3 = ArogyaForms.riForm3(bens, today)
    o(f3.headers == listOf("A House No.", "B Head of household", "C Father's name", "D Total members", "E Pregnant woman", "F Newborn (<1 month)", "G Child 1 month–1 year", "H Child 1–2 years"), "RI Form 3 has the official columns A–H")
    o(f3.rows.size == ArogyaRegistryLogic.byHousehold(bens).size, "one row per household (${f3.rows.size})")
    o(f3.rows.count { it[4] == "Yes" } == 8, "8 households with a pregnant woman")
    // RI Form 4
    val f4 = ArogyaForms.riForm4(bens, today)
    o(f4.headers.containsAll(listOf("Name of the pregnant woman", "Age", "Husband's name", "Mobile", "MCP card", "EDD / LMP", "TT/Td-1", "TT/Td-2", "TT-Booster", "1st ANC", "2nd ANC", "3rd ANC", "4th ANC")), "RI Form 4 official columns")
    o(f4.rows.size == 8 && f4.rows.all { it[1].isNotBlank() }, "8 pregnant women listed with names")
    // Annexure 9 home visit form for a newborn with visits
    val nb = bens.first { it.name.startsWith("Baby of Priya") }
    val f9 = ArogyaForms.homeVisitForm(nb, sm.visits)
    println("   " + f9.headers + "\n   " + f9.rows.take(3))
    o(f9.headers == listOf("Ask / Examine", "Day 1", "Day 3", "Day 7", "Day 14", "Day 21", "Day 28", "Day 42"), "Annexure 9 columns Day 1..42")
    o(f9.rows.first()[0] == "Date of ASHA's visit" && f9.rows.first()[2].isNotBlank() && f9.rows.first()[4].isNotBlank(), "visit dates filled for done days (3, 14)")
    val n9 = ArogyaOfficialForms.table(ArogyaOfficialForms.ANNEX9, nb, sm.registry.forms)
    o(n9.rows.any { it[0].startsWith("Baby's temperature") && it.drop(1).any { c -> c.contains("36.8") } }, "temperature on the filled Annexure 9 form")
    o(f9.rows.any { it[0].startsWith("Weight") }, "weight row present")
    // Annexure 5 drug kit stock card
    val f5 = ArogyaForms.drugKitCard(sm.registry.stock)
    o(f5.headers.take(2) == listOf("S. No.", "Name of drug") && f5.headers.any { it.startsWith("Balance") } && f5.headers.any { it.startsWith("Refill") }, "Annexure 5 columns")
    o(f5.rows.any { it[1] == "ORS" && it.contains("50") }, "ORS refill of 50 shown")
    // MCP card sections in official order
    val mother = bens.first { it.category == BenCategory.PREGNANT && it.mcp.anc.isNotEmpty() }
    val card = ArogyaForms.mcpCard(mother, bens, sm.visits, today)
    o(card.map { it.first }.take(3) == listOf("1. Mother and family identification", "2. Institutional identification and referral", "3. Pregnancy record and antenatal care"), "MCP card sections follow the official order")
    val child = bens.first { it.name.startsWith("Infant Aarav") }
    val ccard = ArogyaForms.mcpCard(child, bens, sm.visits, today)
    o(ccard.any { it.first.startsWith("10. Child immunization") && it.second.any { r -> r.startsWith("BCG") } }, "child card has immunization table with BCG")
    println(if (of == 0) "ALL $op PASS" else "$of FAILED, $op passed")
    if (of > 0) kotlin.system.exitProcess(1)
}
