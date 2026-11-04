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
    // MCP sections (Final A): row labels, order, × cells, source lines.
    val anc = ArogyaOfficialForms.MCP_ANC
    v(anc.source.contains("MCP Card p.5") && anc.source.contains("Guidebook p.12"), "mcp-anc source line")
    v(anc.columns == listOf("ANC 1", "ANC 2", "ANC 3", "ANC 4", "ANC 5 (PMSMA)"), "mcp-anc columns incl PMSMA")
    v(anc.fields.map { it.section }.distinct() == listOf("Pregnancy record", "Obstetric complication in previous pregnancy",
        "Past history", "Examination", "Antenatal visits", "Abdominal examination", "Essential investigations",
        "Blood Group & Rh", "Optional investigations", "Checkup", "Danger signs", "Birth preparedness"), "mcp-anc section order")
    v(anc.fields.map { it.id } == listOf("gravida", "prev_live_births", "last_delivery_at", "delivery_institution", "preg_outcome",
        "oc_aph", "oc_eclampsia", "oc_pih", "oc_anaemia", "oc_obstructed", "oc_pph", "oc_lscs", "oc_anomaly", "oc_abortion", "oc_other",
        "ph_tb", "ph_hyp", "ph_heart", "ph_diabetes", "ph_asthma", "ph_other", "height", "heart", "lungs", "breasts",
        "pog", "weight", "pulse", "bp", "pallor", "oedema", "jaundice", "complaints",
        "fundal_height", "lie", "fetal_mov", "fhr", "pv",
        "hb", "urine_albumin", "urine_sugar", "hiv", "syphilis", "usg", "gdm", "blood_group", "bg_date",
        "tsh", "hbsag", "blood_sugar", "opt_other", "td", "ifa", "calcium", "albendazole",
        "d_bleed", "d_anaemia", "d_fever", "d_headache", "d_labour", "d_water", "bp_facility", "jsy"), "mcp-anc full row order")
    v(anc.fields.first { it.id == "td" }.label == "Td (TT on older cards)" &&
        anc.fields.first { it.id == "td" }.choices == listOf("None", "Td-1", "Td-2", "Booster"), "mcp-anc Td label + choices")
    v(anc.fields.first { it.id == "hiv" }.type == QType.YESNO &&
        anc.fields.first { it.id == "fetal_mov" }.choices == listOf("Normal", "Reduced", "Absent"), "mcp-anc HIV Yes/No + fetal choices")
    val pnc = ArogyaOfficialForms.MCP_PNC
    v(pnc.source.contains("MCP Card p.7") && pnc.columns == listOf("1st Day", "3rd Day", "7th Day", "6th Week"), "mcp-pnc source + ANM columns")
    v(pnc.fields.map { it.id }.take(8) == listOf("del_place_detail", "pnc_outcome", "term", "stay_days", "del_complications", "cried", "bf_1hr", "vitk"), "mcp-pnc delivery record")
    v(pnc.fields.map { it.id }.takeLast(13) == listOf("complaints", "pallor", "pulse", "bp", "temp", "breasts", "nipples", "uterus", "bleeding", "lochia", "episiotomy", "fp_counsel", "complications_ref"), "mcp-pnc PPC rows")
    val mbb = ArogyaOfficialForms.MCP_BABY
    v(mbb.columns == listOf("1st Day", "3rd Day", "7th Day", "6th Week") && mbb.note.contains("2.5 kg"), "mcp-baby columns + LBW footnote")
    v(mbb.fields.map { it.id } == listOf("weight", "urine", "stool", "diarrhoea", "vomiting", "convulsions", "activity", "sucking", "breathing", "chest_indrawing", "temp", "jaundice", "cord"), "mcp-baby row order")
    val mh = ArogyaOfficialForms.MCP_HBYC
    v(mh.source.contains("MCP Card p.8"), "mcp-hbyc source line")
    v(mh.fields.first { it.id == "cf1" }.onlyColumns == setOf(1, 2, 3, 4) &&
        mh.fields.first { it.id == "measles" }.onlyColumns == setOf(2, 3, 4) &&
        mh.fields.first { it.id == "vita" }.onlyColumns == setOf(2, 4) &&
        mh.fields.first { it.id == "c_ebf" }.onlyColumns == setOf(0, 1) &&
        mh.fields.first { it.id == "ifa_syrup" }.onlyColumns == setOf(1, 2, 3, 4), "mcp-hbyc x cells as printed")
    val mi = ArogyaOfficialForms.MCP_IMM
    v(mi.source.contains("p.36–37") && mi.columns.size == 9, "mcp-imm source + 9 age columns")
    v(mi.fields.filter { it.onlyColumns == setOf(0) }.map { it.id } == listOf("opv0", "hepb0", "bcg") &&
        mi.fields.filter { it.onlyColumns == setOf(4) }.map { it.id } == listOf("mr1", "je1", "vita1", "pcvb") &&
        mi.fields.filter { it.onlyColumns == setOf(7) }.map { it.id } == listOf("td10") &&
        mi.fields.filter { it.onlyColumns == setOf(8) }.map { it.id } == listOf("td16"), "mcp-imm antigens per column")
    v(listOf("vita3", "vita9", "sia_vaccine", "miss_name", "miss_next", "next_vac_date").all { id -> mi.fields.any { it.id == id } }, "mcp-imm VitA/SIA/missed/next rows")
    val mfa = ArogyaOfficialForms.MCP_IFA
    v(mfa.source.contains("MCP Card p.27") && mfa.fields.first { it.id == "alb_dose1" }.onlyColumns == setOf(1, 2, 3, 4), "mcp-ifa source + albendazole ages")
    v(ArogyaOfficialForms.FP_TIMING.size == 8 && ArogyaOfficialForms.FP_TIMING.all { it.size == 4 } &&
        ArogyaOfficialForms.FP_TIMING[0][0] == "Female Sterilization", "fp timing table 8x4")
    // Neha's household shows filled MCP records, all consistent.
    val sm = ArogyaSampleVillage.build(java.time.LocalDate.of(2026, 11, 2)).registry
    val nm = sm.beneficiaries.first { it.name == "Neha (mother)" }
    val nb = sm.beneficiaries.first { it.name == "Baby of Neha" }
    v(sm.forms.count { it.beneficiaryId == nm.id && it.formId == "mcp_anc" } == 4, "neha ANC x4")
    v(sm.forms.count { it.beneficiaryId == nm.id && it.formId == "mcp_pnc" } == 2, "neha PNC x2")
    v(sm.forms.count { it.beneficiaryId == nb.id && it.formId == "mcp_baby" } == 2, "baby BABY x2")
    // Birth-dose vaccines are on the family's paper card only: the demo adds them from the card photo (Workflow 2).
    v(sm.forms.none { it.beneficiaryId == nb.id && it.formId == "mcp_imm" }, "baby IMM left for the card photo")
    v(setOf("BCG", "OPV0", "HEPB0").none { nb.mcp.vaccines.containsKey(it) }, "baby birth vaccines come from the card photo")
    println(if (vf == 0) "ALL $vp PASS" else "$vf FAILED, $vp passed")
    if (vf > 0) kotlin.system.exitProcess(1)
}
