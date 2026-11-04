import com.example.arfas.*
import java.time.LocalDate

private var n = 0; private var fails = 0
private fun check(name: String, ok: Boolean, info: Any? = null) { if (ok) { n++; println("PASS $name") } else { fails++; println("FAIL $name ${info ?: ""}") } }

fun main() {
    val today = LocalDate.now()
    val s = ArogyaSampleVillage.build(today)
    var r = s.registry
    val baby = r.beneficiaries.first { it.name == "Baby of Neha" }
    val mum = r.beneficiaries.first { it.name == "Neha (mother)" }
    check("neha-identification", mum.relativeName.isNotBlank() && mum.age.isNotBlank() && mum.phone.length == 10 && mum.rchId.isNotBlank() && mum.lmp.isNotBlank(), mum)
    check("baby-rch", baby.rchId.isNotBlank())
    check("birth-vaccines-left-for-card-photo", baby.mcp.vaccines["BCG"] == null)
    check("profile", ArogyaSampleVillage.PROFILE.toMap()["district"] == "Sitapur")
    val (spec, col) = ArogyaOfficialForms.formFor(baby, r.forms, today)
    check("day7-form", spec.id == "annex9" && col == 2, "${spec.id}#$col")
    val values = mapOf("weight" to "2.65", "temp" to "38.4", "s_feed" to "Yes", "s_cry" to "Yes", "s_limp" to "No", "s_chest" to "No", "s_umbilicus" to "No",
        "jaundice" to "No", "eyes" to "No", "m_meals" to "4", "m_pads" to "3", "m_temp" to "37", "m_discharge" to "No", "m_fits" to "No")
    r = ArogyaOfficialForms.apply(r, baby, ArogyaOfficialForms.ANNEX9, 2, values, values.mapValues { "note" }, emptySet(), today)
    val mb = r.forms.firstOrNull { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 2 }
    check("mcp-baby-7th-day", mb != null && mb.values["weight"] == "2.65" && mb.values["temp"] == "38.4" && mb.values["sucking"] == "Poor" && mb.values["cord"] == "Clean, no pus" && mb.values["_src"] == "annex9", mb?.values)
    val mp = r.forms.firstOrNull { it.beneficiaryId == mum.id && it.formId == "mcp_pnc" && it.column == 2 }
    check("mcp-pnc-7th-day", mp != null && mp.values["temp"] == "37" && mp.values["bleeding"] == "Normal" && mp.values["lochia"] == "Healthy", mp?.values)
    // ANM's own day-3 entry (seeded) is never overwritten by an ASHA Day-3 save.
    val anm3 = r.forms.first { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 1 }
    val r2 = ArogyaOfficialForms.apply(r, baby, ArogyaOfficialForms.ANNEX9, 1, mapOf("weight" to "2.70", "temp" to "37.0"), emptyMap(), emptySet(), today)
    check("anm-entry-kept", r2.forms.first { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 1 } == anm3)
    // Re-saving Day 7 replaces the linked entry instead of adding a second one.
    val r3 = ArogyaOfficialForms.apply(r, baby, ArogyaOfficialForms.ANNEX9, 2, values + ("weight" to "2.66"), emptyMap(), emptySet(), today)
    check("resave-replaces", r3.forms.count { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 2 } == 1 &&
        r3.forms.first { it.beneficiaryId == baby.id && it.formId == "mcp_baby" && it.column == 2 }.values["weight"] == "2.66")
    check("day14-not-linked", ArogyaOfficialForms.mcpColumnForHbnc(3) == null && ArogyaOfficialForms.mcpColumnForHbnc(6) == 3)
    val zinc = r.stock.filter { it.item == "Zinc tablets" }.sumOf { it.qty }
    check("zinc-14-left", zinc == 14, zinc)
    // The card shows the ASHA column label.
    val html = ArogyaDocRender.mcpDoc(baby, r.beneficiaries, today, ArogyaDocRender.DocHeader("Uttar Pradesh", "Sitapur", "Mishrikh", "Rampur SC", "Sunita Devi"),
        "04/11/2026", "{{{babyTable}}}|{{state}}|{{district}}", "", forms = r.forms).html
    check("card-label", html.contains("7th Day: recorded at the ASHA's HBNC home visit") && html.contains("Sitapur"), html.take(300))
    println("HbncLinkTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
