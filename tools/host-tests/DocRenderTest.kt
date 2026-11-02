import com.example.arfas.*
import java.io.File
import java.time.LocalDate
var fails = 0; var n = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    n++
    if (!ok) { fails++; println("FAIL $name $detail") } else println("PASS $name")
}
fun tmpl(name: String): String = File("../../app/src/main/assets/doc_templates/$name").readText(Charsets.UTF_8)
fun main() {
    val css = tmpl("doc.css")
    val tableT = tmpl("table.html")
    val mcpT = tmpl("mcp.html")
    val ansT = tmpl("answer.html")
    check("templates-load", css.isNotBlank() && tableT.isNotBlank() && mcpT.isNotBlank() && ansT.isNotBlank())
    // Engine: escaping, raw, sections, nesting.
    check("fill-esc", ArogyaDocRender.fill("A{{x}}B", mapOf("x" to "<b>&")) == "A&lt;b&gt;&amp;B")
    check("fill-raw", ArogyaDocRender.fill("A{{{x}}}B", mapOf("x" to "<b>")) == "A<b>B")
    check("fill-once", ArogyaDocRender.fill("{{#s}}<i>{{s}}</i>{{/s}}", mapOf("s" to "hi")) == "<i>hi</i>")
    check("fill-skip", ArogyaDocRender.fill("A{{#s}}<i>{{s}}</i>{{/s}}B", mapOf("s" to "")) == "AB")
    check("fill-list", ArogyaDocRender.fill("{{#r}}[{{a}}{{b}}]{{/r}}",
        mapOf("r" to listOf(mapOf("a" to "1", "b" to "2"), mapOf("a" to "3", "b" to "4")))) == "[12][34]")
    check("fill-nested", ArogyaDocRender.fill("{{#o}}O{{#i}}{{v}}{{/i}}{{/o}}",
        mapOf("o" to "1", "i" to listOf(mapOf("v" to "x")))) == "Ox")
    check("fill-unknown", ArogyaDocRender.fill("A{{nope}}B", emptyMap()) == "AB")
    // Table doc: no placeholders left, orientation, sections, status, sign, watermark.
    val h = ArogyaDocRender.DocHeader("Bihar", "Gaya", "Belaganj", "SC Rampur", "Sunita Devi")
    val wide = FormTable("T", "S", (1..8).map { "C$it" }, listOf(listOf("a") + List(7) { "b" }), sample = true)
    val dw = ArogyaDocRender.tableDoc(wide, h, "Date", "02/11/2026", tableT, css)
    check("table-wide-landscape", dw.landscape && "A4 landscape" in dw.html)
    check("table-noleft", "{{" !in dw.html, dw.html.substring(0, 200))
    check("table-watermark", "SAMPLE" in dw.html)
    check("table-header", "SC Rampur" in dw.html && "Sunita Devi" in dw.html && "02/11/2026" in dw.html)
    val narrow = FormTable("T", "S", listOf("A", "B", "C", "D"),
        listOf(listOf("Section one", "", "", ""), listOf("x", "Overdue", "Done", "Due now"), listOf("y", "ok", "ok", "ok")))
    val dn = ArogyaDocRender.tableDoc(narrow, h, "Date", "02/11/2026", tableT, css)
    check("table-narrow-portrait", !dn.landscape && "size: A4;" in dn.html)
    check("table-section", "colspan=\"4\">Section one" in dn.html)
    check("table-status", "class=\"overdue\">Overdue" in dn.html && "class=\"done\">Done" in dn.html && "class=\"due\">Due now" in dn.html)
    check("table-zebra", "class=\"zebra\"" in dn.html)
    check("table-nosign", "ASHA signature" !in dn.html)
    val sign = ArogyaDocRender.tableDoc(narrow.copy(sign = true), h, "Date", "02/11/2026", tableT, css)
    check("table-sign", "ASHA signature" in sign.html)
    check("table-nowatermark", "SAMPLE" !in dn.html)
    val blankH = ArogyaDocRender.DocHeader("", "", "", "", "")
    val db = ArogyaDocRender.tableDoc(narrow, blankH, "Date", "02/11/2026", tableT, css)
    check("table-blank-header", "________" in db.html)
    // Rules.
    check("landscape-rule", !ArogyaDocRender.landscapeFor(6) && ArogyaDocRender.landscapeFor(7))
    check("wm-rule", ArogyaDocRender.watermarkFor(listOf("demo-x")) && ArogyaDocRender.watermarkFor(listOf("p1"), true) &&
        !ArogyaDocRender.watermarkFor(listOf("p1", "p2")))
    // MCP card: shape, tables, watermark for sample ids.
    val baby = Beneficiary(id = "demo-baby", name = "Baby of Neha", relativeName = "Neha", village = "Rampur",
        household = "H1", sex = "F", category = BenCategory.CHILD, dob = "2026-10-26", birthWeightKg = 2.9,
        deliveryPlace = "INSTITUTION", demo = true,
        mcp = McpCard(vaccines = mapOf("BCG" to "2026-10-26"), weights = listOf(WeightEntry("2026-10-26", 2.9))))
    val mom = Beneficiary(id = "demo-neha", name = "Neha", relativeName = "Ramesh", village = "Rampur",
        household = "H1", sex = "F", category = BenCategory.MOTHER, phone = "9811100001", demo = true)
    val dm = ArogyaDocRender.mcpDoc(baby, listOf(baby, mom), LocalDate.parse("2026-11-02"), h, "02/11/2026", mcpT, css)
    check("mcp-noleft", "{{" !in dm.html)
    check("mcp-shape", "Mother and Child Protection Card" in dm.html && "Immunization" in dm.html && "Neha" in dm.html)
    check("mcp-vaccine", "BCG" in dm.html && "26/10/26" in dm.html)
    check("mcp-weight", "2.9 kg" in dm.html)
    check("mcp-watermark", "SAMPLE" in dm.html)
    check("mcp-portrait", !dm.landscape)
    val pw = Beneficiary(name = "Meera", village = "Rampur", category = BenCategory.PREGNANT, sex = "F",
        lmp = "2026-08-10", mcp = McpCard(anc = mapOf(1 to "2026-10-01"),
            ancDetails = mapOf(1 to AncDetail("55", "110/70", "11.2")), tdDates = listOf("2026-10-01"), ifaTablets = 30))
    val dp = ArogyaDocRender.mcpDoc(pw, listOf(pw), LocalDate.parse("2026-11-02"), h, "02/11/2026", mcpT, css)
    check("mcp-anc", "Antenatal visits" in dp.html && "110/70" in dp.html && "Antenatal visits" !in dm.html)
    check("mcp-edd", "17/05/27" in dp.html && "EDD" in dp.html)
    // Answer doc: cited segments + numbered sources.
    val segs = listOf(ArogyaOfficialAnswer.CitedSegment("Keep the cord clean.", listOf("1")),
        ArogyaOfficialAnswer.CitedSegment("The scheme started in 2025.", listOf("W1")))
    val srcs = listOf(ArogyaSource("1", "HBNC Guide", "p.12", "Keep the cord clean and dry.", "https://nhm.gov.in/g",
        listOf("Keep the cord clean.")),
        ArogyaSource("W1", "PIB release", "Web page", "The scheme started in 2025.", "https://pib.gov.in/x",
        emptyList(), kind = "web", date = "12 March 2025", domain = "pib.gov.in"))
    val da = ArogyaDocRender.answerDoc("Cord care?", segs, "", srcs, h, "02/11/2026", ansT, css)
    check("ans-noleft", "{{" !in da.html)
    check("ans-markers", "[1]" in da.html && "[W1]" in da.html)
    check("ans-webmeta", "pib.gov.in" in da.html && "12 March 2025" in da.html)
    check("ans-link", "https://pib.gov.in/x" in da.html)
    val df = ArogyaDocRender.answerDoc("Q?", emptyList(), "Plain answer here.", emptyList(), h, "02/11/2026", ansT, css)
    check("ans-fallback", "Plain answer here." in df.html && "Sources" !in df.html)
    println("DocRenderTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
