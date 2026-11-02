import com.example.arfas.*
import java.time.LocalDate
var vf = 0; var vp = 0
fun v(c: Boolean, m: String) { if (c) { vp++; println("PASS $m") } else { vf++; println("FAIL $m") } }
fun main() {
    val F = ArogyaOfficialForms
    val today = LocalDate.of(2026, 11, 2)
    // Annexure 9: official rows, order, columns, source
    v(F.ANNEX9.fields.map { it.id } == listOf("m_meals", "m_pads", "warm", "fed", "cry_urine", "m_temp", "m_discharge",
        "m_fits", "m_nomilk", "m_breast", "eyes", "weight", "temp", "pustules", "skin_folds", "jaundice",
        "s_limp", "s_feed", "s_cry", "s_abdomen", "s_cold", "s_chest", "s_umbilicus"), "annex9 has the 23 official rows in order")
    v(F.ANNEX9.fields.first { it.id == "warm" }.label.contains("cold season", ignoreCase = true), "warm row keeps 'cold season'")
    v(F.ANNEX9.fields.first { it.id == "s_limp" }.section == "D. Check now for signs of sepsis", "sepsis section header")
    v(F.ANNEX9.columns == listOf("Day 1", "Day 3", "Day 7", "Day 14", "Day 21", "Day 28", "Day 42"), "annex9 visit columns")
    v(F.ANNEX9.source.contains("pp. 60–63"), "annex9 source line has pages")
    // HBYC card
    v(F.HBYC.source.contains("Annexure 1") && F.HBYC.source.contains("p. 90"), "hbyc source line")
    v(F.HBYC.columns == listOf("3 Months", "6 Months", "9 Months", "12 Months", "15 Months"), "hbyc visit columns")
    v(F.HBYC.fields.first { it.id == "cf" }.label.contains("(app addition)"), "cf summary tagged")
    v(F.HBYC.fields.first { it.id == "albendazole" }.label.contains("(app addition)"), "hbyc albendazole tagged")
    v(F.HBYC.fields.first { it.id == "weight" }.label.contains("app addition"), "hbyc weight tagged")
    v(F.HBYC.fields.first { it.id == "sick" }.label == "Whether child is sick", "hbyc sick label official")
    // Sick child checklist
    v(F.SICK.title.startsWith("Sick child checklist") && F.SICK.title.contains("pp. 63–66"), "sick title + section")
    v(F.SICK.source.contains("pp. 63–66"), "sick source line has pages")
    v(F.SICK.fields.first { it.id == "zinc" }.label.contains("(app addition)"), "zinc tagged")
    v(F.SICK.fields.first { it.id == "diarrhoea" }.hint.contains("3 watery"), "diarrhoea definition in hint")
    // Family planning entry aid
    v(F.FP.title.contains("state formats vary"), "fp title carries the caveat")
    // Printed grid extras
    val b = Beneficiary(id = "v-1", name = "V", village = "Rampur", category = BenCategory.CHILD, dob = "2026-10-01")
    v(F.table(F.ANNEX9, b, emptyList()).rows.first()[0] == "Date of ASHA's visit", "printed grid keeps the visit-date row")
    v(F.table(F.ANNEX9, b, emptyList()).rows.last()[0] == "Next visit due (app addition)", "next-visit row tagged")
    // RI Form 3
    val f3 = ArogyaForms.riForm3(listOf(b), today)
    v(f3.headers[2] == "C Father's name", "ri3 col C is father's name")
    v(f3.source.contains("RI Form 3") && f3.source.contains("pp. 56–57"), "ri3 source line has pages")
    // RI Form 4 + ANM due columns
    val pw0 = Beneficiary(id = "v-p0", name = "P0", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-04-06")
    val pw2 = pw0.copy(id = "v-p2", mcp = McpCard(tdDates = listOf("2026-05-01", "2026-06-01"),
        anc = mapOf(1 to "2026-05-01", 2 to "2026-06-01", 3 to "2026-08-01", 4 to "2026-10-01")))
    val f4 = ArogyaForms.riForm4(listOf(pw0, pw2), today)
    v(f4.headers.takeLast(2) == listOf("TT due", "ANC due"), "ri4 has the ANM due columns")
    v(f4.rows[0].takeLast(2) == listOf("Yes", "Yes") && f4.rows[1].takeLast(2) == listOf("No", "No"), "due columns computed from records")
    v(f4.source.contains("RI Form 4") && f4.source.contains("pp. 57–59"), "ri4 source line has pages")
    // Annexure 5
    val f5 = ArogyaForms.drugKitCard(emptyList())
    v(f5.headers[2] == "Symbol", "annex5 has the symbol column")
    v(f5.source.contains("Annexure 5") && f5.source.contains("p. 53"), "annex5 source line has pages")
    println(if (vf == 0) "ALL $vp PASS" else "$vf FAILED, $vp passed")
    if (vf > 0) kotlin.system.exitProcess(1)
}
