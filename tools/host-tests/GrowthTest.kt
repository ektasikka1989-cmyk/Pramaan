import com.example.arfas.*
import java.io.File
import java.time.LocalDate
var fails = 0; var n = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    n++
    if (!ok) { fails++; println("FAIL $name $detail") } else println("PASS $name")
}
fun main() {
    // WHO table asset (parsed from the official WHO simplified field tables).
    val json = File("../../app/src/main/assets/growth/who_wfa.json").readText(Charsets.UTF_8)
    val t = ArogyaGrowth.parseTable(json)
    check("asset-ok", t.ok)
    check("parse-boys0", t.boys[0] == ArogyaGrowth.Row(3.3, 2.5, 2.1), "${t.boys[0]}")
    check("parse-girls12", t.girls[12] == ArogyaGrowth.Row(8.9, 7.0, 6.3), "${t.girls[12]}")
    check("parse-bad", !ArogyaGrowth.parseTable("nope").ok)
    // Age in completed months.
    check("age-newborn", ArogyaGrowth.ageMonths("2026-10-26", "2026-11-02") == 0)
    check("age-12", ArogyaGrowth.ageMonths("2026-10-26", "2027-10-26") == 12)
    check("age-clamp", ArogyaGrowth.ageMonths("2016-01-01", "2026-11-02") == 60)
    check("age-bad", ArogyaGrowth.ageMonths("", "2026-11-02") == -1)
    // Zones: strictly below -2 SD = yellow, strictly below -3 SD = red. Boys 12 mo: 9.6/7.7/6.9.
    check("zone-green", ArogyaGrowth.zone("M", 12, 9.6, t) == ArogyaGrowth.Zone.GREEN)
    check("zone-yellow", ArogyaGrowth.zone("M", 12, 7.0, t) == ArogyaGrowth.Zone.YELLOW)
    check("zone-red", ArogyaGrowth.zone("M", 12, 6.5, t) == ArogyaGrowth.Zone.RED)
    check("zone-boundary", ArogyaGrowth.zone("M", 12, 7.7, t) == ArogyaGrowth.Zone.GREEN &&
        ArogyaGrowth.zone("M", 12, 6.9, t) == ArogyaGrowth.Zone.YELLOW)
    // Girls 12 mo: 8.9/7.0/6.3.
    check("zone-girl", ArogyaGrowth.zone("F", 12, 8.9, t) == ArogyaGrowth.Zone.GREEN &&
        ArogyaGrowth.zone("F", 12, 6.5, t) == ArogyaGrowth.Zone.YELLOW &&
        ArogyaGrowth.zone("F", 12, 6.0, t) == ArogyaGrowth.Zone.RED)
    check("zone-no-table", ArogyaGrowth.zone("M", 12, 9.6, ArogyaGrowth.Table(emptyList(), emptyList())) == null)
    // Layout: axis growth, normalised dots.
    val nb = listOf(WeightEntry("2026-10-26", 2.9), WeightEntry("2026-11-02", 3.0))
    val l = ArogyaGrowth.layout("F", "2026-10-26", nb, t)
    check("layout-newborn", l != null && l!!.xMax == 12 && l.dots.size == 2 &&
        l.dots.all { it.x in 0f..1f && it.y in 0f..1f }, "$l")
    check("layout-curves", l != null && l!!.median.size == 13 && l.m2.size == 13 && l.m3.size == 13)
    check("layout-empty", ArogyaGrowth.layout("F", "2026-10-26", emptyList(), t) == null &&
        ArogyaGrowth.layout("F", "", nb, t) == null)
    val l15 = ArogyaGrowth.layout("M", "2025-08-01", listOf(WeightEntry("2026-11-01", 10.0)), t)
    check("layout-xmax", l15 != null && l15!!.xMax == 24, "$l15")
    // Fell/gained line reuses the existing fixed rule.
    check("note-fell", (ArogyaGrowth.weightNote(listOf(WeightEntry("2026-10-26", 2.9), WeightEntry("2026-11-02", 2.7)))
        ?: "").contains("fell") && (ArogyaGrowth.weightNote(
        listOf(WeightEntry("2026-10-26", 2.9), WeightEntry("2026-11-02", 2.7))) ?: "").contains("tell the ANM"))
    check("note-gained", (ArogyaGrowth.weightNote(nb) ?: "").contains("gained"))
    check("note-single", ArogyaGrowth.weightNote(listOf(WeightEntry("2026-10-26", 2.9))) == null)
    // ANC trend across ANC 1..4.
    val det = mapOf(1 to AncDetail("45", "110/70", "10.2"), 2 to AncDetail("47", "", "11.0"))
    check("trend", ArogyaGrowth.ancTrend(det) == "Weight 45 → 47 kg (+2); Hb 10.2 → 11 (+0.8)",
        ArogyaGrowth.ancTrend(det))
    check("trend-single", ArogyaGrowth.ancTrend(mapOf(1 to AncDetail("45", "", ""))) == "")
    // SVG for the MCP card PDF.
    val svg = ArogyaGrowth.svg("F", "2026-10-26", nb, t)
    check("svg", "<svg" in svg && "circle" in svg && "#e8710a" in svg && "#ec407a" in svg && "</svg>" in svg)
    check("svg-empty", ArogyaGrowth.svg("F", "2026-10-26", emptyList(), t) == "")
    // MCP card doc renders the chart section with no placeholders left.
    val mcpT = File("../../app/src/main/assets/doc_templates/mcp.html").readText(Charsets.UTF_8)
    val css = File("../../app/src/main/assets/doc_templates/doc.css").readText(Charsets.UTF_8)
    val h = ArogyaDocRender.DocHeader("Bihar", "Gaya", "Belaganj", "SC Rampur", "Sunita Devi")
    val baby = Beneficiary(id = "demo-baby", name = "Baby of Neha", relativeName = "Neha", village = "Rampur",
        household = "H1", sex = "F", category = BenCategory.CHILD, dob = "2026-10-26", birthWeightKg = 2.9,
        deliveryPlace = "INSTITUTION", demo = true, mcp = McpCard(weights = nb))
    val dg = ArogyaDocRender.mcpDoc(baby, listOf(baby), LocalDate.parse("2026-11-02"), h, "02/11/2026",
        mcpT, css, svg, ArogyaGrowth.weightNote(nb).orEmpty(), "")
    check("mcp-growth", "{{" !in dg.html && "<svg" in dg.html && "Growth Chart" in dg.html)
    val pw = Beneficiary(id = "demo-pw", name = "Meena Devi", village = "Rampur", sex = "F",
        category = BenCategory.PREGNANT, lmp = "2026-04-01", demo = true,
        mcp = McpCard(anc = mapOf(1 to "2026-06-01", 2 to "2026-09-01"), ancDetails = det))
    val da = ArogyaDocRender.mcpDoc(pw, listOf(pw), LocalDate.parse("2026-11-02"), h, "02/11/2026",
        mcpT, css, "", "", ArogyaGrowth.ancTrend(det))
    check("mcp-trend", "{{" !in da.html && "Trend:" in da.html && "45 → 47" in da.html)
    val dn = ArogyaDocRender.mcpDoc(baby, listOf(baby), LocalDate.parse("2026-11-02"), h, "02/11/2026", mcpT, css)
    check("mcp-nochart", "{{" !in dn.html && "<svg" !in dn.html)
    println("GrowthTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
