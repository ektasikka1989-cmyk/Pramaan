import com.example.arfas.*
import java.time.LocalDate
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    val today = LocalDate.parse("2026-10-31")
    val baby = Beneficiary(id = "b1", name = "Baby of Sunita", category = BenCategory.CHILD, dob = "2026-10-25")  // day 7
    val (f1, c1) = ArogyaOfficialForms.formFor(baby, emptyList(), today)
    check("newborn annex9", f1.id == "annex9"); check("day7 col", c1 == 2)
    val baby1 = baby.copy(dob = "2026-10-31"); check("day1 col", ArogyaOfficialForms.formFor(baby1, emptyList(), today).second == 0)
    check("weight hidden day1", ArogyaOfficialForms.fieldsFor(f1, 0).none { it.id == "weight" })
    check("weight shown day7", ArogyaOfficialForms.fieldsFor(f1, 2).any { it.id == "weight" })
    val kid = baby.copy(dob = "2026-04-20")   // ~6 months
    val (f2, c2) = ArogyaOfficialForms.formFor(kid, emptyList(), today); check("hbyc", f2.id == "hbyc"); check("hbyc 6m col", c2 == 1)
    val old = baby.copy(dob = "2024-01-01"); check("older child sick", ArogyaOfficialForms.formFor(old, emptyList(), today).first.id == "sick")
    val preg = Beneficiary(id = "p1", name = "Rekha", category = BenCategory.PREGNANT, lmp = "2026-05-01")
    val e1 = FormEntry(beneficiaryId = "p1", formId = "mcp_anc", column = 0, date = "2026-08-01")
    val (f3, c3) = ArogyaOfficialForms.formFor(preg, listOf(e1), today); check("anc", f3.id == "mcp_anc"); check("anc next col", c3 == 1)
    val mom = Beneficiary(id = "m1", category = BenCategory.MOTHER, deliveryDate = "2026-10-29"); val fm = ArogyaOfficialForms.formFor(mom, emptyList(), today)
    check("mother annex9 day3", fm.first.id == "annex9" && fm.second == 1)
    check("ec fp", ArogyaOfficialForms.formFor(Beneficiary(category = BenCategory.ELIGIBLE_COUPLE), emptyList(), today).first.id == "fp")

    val note = "Baby weight 2450 grams. Baby temperature 101 F. No pus on the cord. Mother changed 6 pads today. Baby feeding well, 8 times."
    val raw = """Here: {"fields":[{"id":"weight","value":"2450 g","evidence":"Baby weight 2450 grams"},
      {"id":"temp","value":"101 F","evidence":"Baby temperature 101 F"},
      {"id":"s_umbilicus","value":"No","evidence":"No pus on the cord"},
      {"id":"m_pads","value":"6","evidence":"Mother changed 6 pads today"},
      {"id":"jaundice","value":"Yes","evidence":"eyes look yellow"},
      {"id":"m_temp","value":"39","evidence":"Baby temperature 101 F"},
      {"id":"fed","value":"Yes","evidence":"Baby feeding well, 8 times"}]}"""
    val v = ArogyaOfficialForms.parse(raw, ArogyaOfficialForms.ANNEX9, 2, note)
    check("weight kg", v["weight"]?.value == "2.45"); check("temp C", v["temp"]?.value == "38.3")
    check("cord no", v["s_umbilicus"]?.value == "No"); check("pads", v["m_pads"]?.value == "6")
    check("ungrounded dropped", "jaundice" !in v); check("number not in evidence dropped", "m_temp" !in v); check("fed", v["fed"]?.value == "Yes")
    val v0 = ArogyaOfficialForms.parse(raw, ArogyaOfficialForms.ANNEX9, 0, note); check("weight not allowed day1", "weight" !in v0)
    val rule = ArogyaOfficialForms.parse("garbage", ArogyaOfficialForms.ANNEX9, 2, "weight 2.1 kg temperature 37 C")
    check("rule fallback weight", rule["weight"]?.source == "rule")

    val d = ArogyaOfficialForms.dangers(ArogyaOfficialForms.ANNEX9, v.mapValues { it.value.value })
    check("danger pads", d.any { it.contains("5 pads") }); check("danger baby temp", d.any { it.contains("37.2") })
    check("no danger cord", d.none { it.contains("umbilicus") })
    check("danger fed No", ArogyaOfficialForms.dangers(ArogyaOfficialForms.ANNEX9, mapOf("fed" to "No")).isNotEmpty())
    check("anc bp", ArogyaOfficialForms.dangers(ArogyaOfficialForms.MCP_ANC, mapOf("bp" to "150/96")).isNotEmpty())
    check("anc hb", ArogyaOfficialForms.dangers(ArogyaOfficialForms.MCP_ANC, mapOf("hb" to "6.5")).isNotEmpty())
    check("anc ok", ArogyaOfficialForms.dangers(ArogyaOfficialForms.MCP_ANC, mapOf("bp" to "110/70", "hb" to "11")).isEmpty())
    check("choice", ArogyaOfficialForms.normalise(ArogyaOfficialForms.FP.fields[0], "condom") == "Condom")

    val prev = FormEntry(beneficiaryId = "b1", formId = "annex9", column = 0, date = "2026-10-25", values = mapOf("temp" to "36.8"))
    val t = ArogyaOfficialForms.table(ArogyaOfficialForms.ANNEX9, baby, listOf(prev), 2 to mapOf("temp" to "38.3"))
    check("headers", t.headers.size == 8 && t.headers[3] == "Day 7")
    check("rows", t.rows.size == ArogyaOfficialForms.ANNEX9.fields.size + 2 && t.rows.last()[0] == "Next visit due (app addition)")
    val tr = t.rows.first { it[0].startsWith("Baby's temperature") }; check("history col", tr[1] == "36.8"); check("current col", tr[3] == "38.3")
    check("date row", t.rows[0][1] == "2026-10-25")

    val r = RegistryData(forms = listOf(prev.copy(sources = mapOf("temp" to "ai"))))
    val back = ArogyaRegistryCodec.decode(ArogyaRegistryCodec.encode(r)).forms
    check("codec", back.size == 1 && back[0].values["temp"] == "36.8" && back[0].column == 0 && back[0].sources["temp"] == "ai")
    check("prompt has fields", ArogyaOfficialForms.prompt(f1, 2, note).contains("weight:"))
    check("next annex9 day14", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.ANNEX9, baby, 2, today) == LocalDate.parse("2026-11-07"))
    check("annex9 complete", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.ANNEX9, baby, 6, today) == null)
    check("next hbyc", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.HBYC, kid, 1, today) == LocalDate.parse("2027-01-20"))
    check("next anc", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.MCP_ANC, preg, 1, today) == LocalDate.parse("2026-11-27"))
    check("anc complete", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.MCP_ANC, preg, 3, today) == null)
    check("next anc not past", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.MCP_ANC, preg, 0, today) == LocalDate.parse("2026-11-01"))
    val t2 = ArogyaOfficialForms.table(ArogyaOfficialForms.ANNEX9, baby, listOf(prev), 2 to mapOf("_next" to "2026-11-07")); check("next row", t2.rows.last()[3] == "2026-11-07")
    val ancE = FormEntry(beneficiaryId = "p1", formId = "mcp_anc", column = 0, date = "2026-08-01", values = mapOf("jsy" to "Yes", "weight" to "52"))
    check("carry jsy", ArogyaOfficialForms.carried(ArogyaOfficialForms.MCP_ANC, preg, listOf(ancE), 1) == mapOf("jsy" to "Yes"))
    check("previous", ArogyaOfficialForms.previous(ArogyaOfficialForms.MCP_ANC, preg, listOf(ancE), 1)["weight"] == ("52" to "ANC 1"))
    check("missing", ArogyaOfficialForms.missing(ArogyaOfficialForms.FP, 0, mapOf("method" to "Condom")).size == 6)
    check("weight fell", ArogyaOfficialForms.weightNote(mapOf("weight" to ("2.5" to "Day 7")), mapOf("weight" to "2.4"))?.contains("fell 100 g") == true)
    check("scrub", ArogyaOfficialForms.scrub(Beneficiary(name = "Rekha Devi", village = "Rampur", phone = "9876543210"), "Rekha Devi of Rampur 9876543210 weight 52 kg") == "[removed] [removed] of [removed] [removed] weight 52 kg")
    val reg0 = RegistryData(beneficiaries = listOf(preg))
    val vals = mapOf("weight" to "54", "bp" to "120/80", "hb" to "11", "ifa" to "30", "td" to "TT/Td-2")
    val reg1 = ArogyaOfficialForms.apply(reg0, preg, ArogyaOfficialForms.MCP_ANC, 1, vals, vals.mapValues { "ai" }, emptySet(), today)
    val p1 = reg1.beneficiaries[0]
    check("apply anc detail", p1.mcp.ancDetails[2]?.bp == "120/80" && p1.mcp.anc[2] == "2026-10-31")
    check("apply ifa", p1.mcp.ifaTablets == 30 && reg1.stock.any { it.item == "IFA tablets (adult)" && it.qty == -30 })
    check("apply td", p1.mcp.tdDates == listOf("2026-10-31"))
    check("apply entry next", reg1.forms.single().values["_next"] == "2026-11-27")
    check("apply diary", reg1.diary.size == 1)
    val reg2 = ArogyaOfficialForms.apply(reg1, p1, ArogyaOfficialForms.MCP_ANC, 1, vals, emptyMap(), emptySet(), today)
    check("apply replace same column", reg2.forms.size == 1 && reg2.diary.size == 1)
    val vr = ArogyaOfficialForms.visitRecord(preg, ArogyaOfficialForms.MCP_ANC, 1, mapOf("bp" to "150/100"), today)
    check("visit record danger", vr.referral == ArogyaReferral.NEEDED && vr.nextDate == "2026-11-27" && vr.nextReason == "ANC 3 visit")
    val sample = ArogyaSampleVillage.build(today).registry
    check("demo history exists", sample.forms.isNotEmpty())
    check("demo history not future", sample.forms.all { !LocalDate.parse(it.date).isAfter(today) })
    // Review: all 6 pregnant women with ANC history are seeded now (was take(5)), so 21 people have forms.
    // Linked save: HBNC visits also write the MCP card p.7 column ("_src" = annex9); those are counted apart.
    val own = sample.forms.filter { it.values["_src"] != "annex9" }
    check("demo 22 patients filled", own.map { it.beneficiaryId }.toSet().size == 22)
    check("demo all 7 form types", own.map { it.formId }.toSet() == setOf("annex9", "hbyc", "mcp_anc", "sick", "fp", "mcp_pnc", "mcp_baby"))
    check("demo HBNC visits linked to MCP p.7", sample.forms.any { it.values["_src"] == "annex9" && it.formId == "mcp_baby" })
    check("demo stock never negative", ArogyaRegistryLogic.stockBalance(sample.stock).values.all { it >= 0 })
    check("demo ANC card matches form", sample.beneficiaries.filter { b -> sample.forms.any { it.beneficiaryId == b.id && it.formId == "mcp_anc" } }.all { b ->
        b.mcp.anc.size == sample.forms.count { it.beneficiaryId == b.id && it.formId == "mcp_anc" } })
    check("demo diary = form-days", sample.diary.count { it.kind == DiaryKind.HOME_VISIT } == own.map { it.beneficiaryId to it.date }.toSet().size)
    val ritu = sample.beneficiaries.first { it.name.startsWith("Baby of Ritu") }
    check("early visit goes to next empty column", ArogyaOfficialForms.formFor(ritu, sample.forms, today).let { it.first.id == "annex9" && it.second == 3 } )
    val mom2 = sample.beneficiaries.first { it.category == BenCategory.MOTHER && it.name.startsWith("Asha") }
    check("mother visited on baby's form", ArogyaOfficialForms.subjectFor(mom2, sample.beneficiaries).name.startsWith("Baby of Asha"))
    println("FormSpecs: ${n - fails}/$n passed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
