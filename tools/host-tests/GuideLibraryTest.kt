import com.example.arfas.*

// A1: official-guides library data (grouping, links, totals). Synthetic docs only;
// live DB counts (68 docs / 5389 passages) were verified separately via dbschema.py.
var gf = 0; var gp = 0
fun g(c: Boolean, m: String) { if (c) { gp++; println("PASS $m") } else { gf++; println("FAIL $m") } }

fun doc(title: String, authority: String, sourceUrl: String = "", registerUrl: String = "",
    language: String = "English", passages: Int = 10, pages: Int = 5): GuideDoc =
    GuideDoc(title, title, authority, "", language, sourceUrl, registerUrl, "", "", passages, pages)

fun main() {
    // 1. Grouping by issuing body.
    g(ArogyaGuideLibrary.group("Ministry of Health and Family Welfare") == "MoHFW", "MoHFW grouped")
    g(ArogyaGuideLibrary.group("National Health Systems Resource Centre") == "NHSRC/NHM", "NHSRC grouped")
    g(ArogyaGuideLibrary.group("National Health Mission") == "NHM", "NHM grouped")
    g(ArogyaGuideLibrary.group("National Center for Vector Borne Diseases Control") == "NCVBDC", "NCVBDC grouped")
    g(ArogyaGuideLibrary.group("Central TB Division") == "Central TB Division", "TB division grouped")
    g(ArogyaGuideLibrary.group("RBSK") == "RBSK", "RBSK grouped")
    g(ArogyaGuideLibrary.group("Some New Board") == "Some New Board", "unknown body keeps its name")
    g(ArogyaGuideLibrary.group("") == "Other", "blank authority -> Other")
    // 2. Link fallback: source_url, then register_url, then none.
    val both = doc("A", "MoHFW", "https://mohfw.gov.in/a.pdf", "https://x.in/reg")
    val regOnly = doc("B", "NHM", "", "https://nhm.gov.in/reg")
    val none = doc("C", "NHM")
    g(ArogyaGuideLibrary.link(both) == "https://mohfw.gov.in/a.pdf", "source_url wins")
    g(ArogyaGuideLibrary.link(regOnly) == "https://nhm.gov.in/reg", "register_url fallback")
    g(ArogyaGuideLibrary.link(none).isEmpty(), "no link -> blank (UI says Link not recorded)")
    g(ArogyaGuideLibrary.domain(doc("D", "NHM", "https://www.nhm.gov.in/doc.pdf")) == "nhm.gov.in", "domain shown without www")
    g(ArogyaGuideLibrary.domain(none).isEmpty(), "domain blank without link")
    // 3. Totals and group order.
    val docs = listOf(
        doc("Zebra care", "National Health Mission", pages = 4, passages = 40),
        doc("Apple care", "National Health Mission", pages = 6, passages = 60),
        doc("TB guide", "Central TB Division", pages = 10, passages = 100))
    val (n, pages, passages) = ArogyaGuideLibrary.totals(docs)
    g(n == 3 && pages == 20 && passages == 200, "totals (docs, pages, passages)")
    g(ArogyaGuideLibrary.totals(emptyList()) == Triple(0, 0, 0), "empty DB -> zero totals")
    val grouped = ArogyaGuideLibrary.grouped(docs)
    g(grouped.map { it.first } == listOf("NHM", "Central TB Division"), "groups in display order")
    g(grouped[0].second.map { it.title } == listOf("Apple care", "Zebra care"), "titles sorted inside group")
    // 4. Deep links into the official copy.
    g(ArogyaGuideLibrary.pageUrl("https://x.in/g.pdf", "page 18") == "https://x.in/g.pdf#page=18", "PDF opens at cited page")
    g(ArogyaGuideLibrary.pageUrl("https://x.in/g.pdf", "p. 5") == "https://x.in/g.pdf#page=5", "p. N works")
    g(ArogyaGuideLibrary.pageUrl("https://x.in/guide", "page 18") == "https://x.in/guide", "non-PDF unchanged")
    g(ArogyaGuideLibrary.pageUrl("https://x.in/g.pdf", "no locator") == "https://x.in/g.pdf", "no page number unchanged")
    println("GuideLibraryTest: $gp passed, $gf failed")
    if (gf > 0) kotlin.system.exitProcess(1)
}
