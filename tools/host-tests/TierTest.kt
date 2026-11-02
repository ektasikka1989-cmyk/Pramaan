import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    n++
    if (!ok) { fails++; println("FAIL $name $detail") } else println("PASS $name")
}
fun main() {
    // Allowlist: official GoI health domains only (exact or subdomain).
    check("allow-nhm", ArogyaOfficialSources.isAllowed("https://nhm.gov.in/x"))
    check("allow-mohfw-sub", ArogyaOfficialSources.isAllowed("https://www.mohfw.gov.in/y"))
    check("allow-main", ArogyaOfficialSources.isAllowed("https://main.mohfw.gov.in/z"))
    check("allow-pib", ArogyaOfficialSources.isAllowed("https://pib.gov.in/press"))
    check("allow-tbc", ArogyaOfficialSources.isAllowed("https://tbcindia.gov.in/a"))
    check("allow-state-nhm", ArogyaOfficialSources.isAllowed("https://health.mp.nhm.gov.in/b"))
    check("allow-nrhm", ArogyaOfficialSources.isAllowed("https://nrhm-mp.gov.in/c"))
    check("allow-nhsrc", ArogyaOfficialSources.isAllowed("https://nhsrcindia.org/x"))
    check("deny-example", !ArogyaOfficialSources.isAllowed("https://example.com/x"))
    check("deny-lookalike", !ArogyaOfficialSources.isAllowed("https://nhm.gov.in.evil.com/x"))
    check("deny-prefix", !ArogyaOfficialSources.isAllowed("https://evilnhm.gov.in/x"))
    check("deny-junk", !ArogyaOfficialSources.isAllowed("not a url"))
    // Reference parsing: the provider's Sources list, markdown and bare-URL fallbacks.
    val (ans, refs) = ArogyaOfficialSources.parseRefs(
        "The scheme covers mothers.\n\nSources:\n- Scheme details https://nhm.gov.in/scheme\n- Random blog https://example.com/x\n")
    check("refs-split", ans == "The scheme covers mothers." && refs.size == 1, "$ans $refs")
    check("refs-title", refs.getOrNull(0)?.title == "Scheme details")
    val (_, md) = ArogyaOfficialSources.parseRefs("See [Guide](https://mohfw.gov.in/g) now.")
    check("refs-markdown", md.size == 1 && md[0].title == "Guide", md.toString())
    val (_, bare) = ArogyaOfficialSources.parseRefs("See https://pib.gov.in/pib123 now.")
    check("refs-bare", bare.size == 1 && bare[0].title == "pib.gov.in", bare.toString())
    // Page text and dates.
    val html = "<html><head><script>x()</script></head><body><p>Hello <b>World</b></p></body></html>"
    val stripped = ArogyaOfficialSources.stripHtml(html)
    check("html-strip", "Hello World" in stripped && "x()" !in stripped, stripped)
    check("date-find", ArogyaOfficialSources.findDate("Updated 12 March 2025. Text.") == "12 March 2025")
    check("date-key", ArogyaOfficialSources.dateKey("12 March 2025") == "2025-03-12")
    check("date-key2", ArogyaOfficialSources.dateKey("March 1, 2024") == "2024-03-01")
    check("newer", ArogyaOfficialSources.isNewer("12 March 2025", "2023-05-01"))
    check("newer-no", !ArogyaOfficialSources.isNewer("2022-01-01", "2023-05-01"))
    check("newer-unknown", !ArogyaOfficialSources.isNewer("", "2023") && !ArogyaOfficialSources.isNewer("2025-01-01", ""))
    // Citation segments: support, drop, ordering, fallback.
    val page = ArogyaOfficialSources.Fetched("https://nhm.gov.in/s", "Scheme",
        "The Janani scheme provides cash assistance to mothers delivering in government hospitals.", "12 March 2025")
    val segs = ArogyaOfficialAnswer.webSegments(
        "The Janani scheme provides cash assistance to mothers. The moon is made of cheese.", listOf(page), emptyList())
    check("seg-keep-drop", segs.size == 1 && segs[0].sourceIds == listOf("W1"), segs.toString())
    check("seg-all-drop", ArogyaOfficialAnswer.webSegments("The moon is made of cheese.", listOf(page), emptyList()).isEmpty())
    val guides = listOf(ArogyaSource("1", "Doc", "p.1", "The Janani scheme provides cash assistance.", "", emptyList()))
    val mixed = ArogyaOfficialAnswer.webSegments("The Janani scheme provides cash assistance.", listOf(page), guides)
    check("seg-order", mixed.size == 1 && mixed[0].sourceIds == listOf("1", "W1"), mixed.toString())
    check("seg-med-drop", ArogyaOfficialAnswer.webSegments("Give 5ml paracetamol syrup daily.", listOf(page), emptyList()).isEmpty())
    val fb = ArogyaOfficialAnswer.guideSegments("कुछ हिन्दी उत्तर।", guides)
    check("seg-fallback", fb.size == 1 && fb[0].sourceIds == listOf("1"), fb.toString())
    // New-information highlight for the Online tab.
    val news = ArogyaOfficialAnswer.newSentences(
        "Keep the cord clean. Apply nothing.",
        "Keep the cord clean. Give ORS after every loose motion.")
    check("news", "Give ORS after every loose motion." in news && "Keep the cord clean." !in news, news.toString())
    // Tier selection.
    check("tier1", ArogyaOfficialAnswer.selectTier(true, false, false, false) == 1)
    check("tier2", ArogyaOfficialAnswer.selectTier(false, true, true, true) == 2)
    check("tier3-ungrounded", ArogyaOfficialAnswer.selectTier(false, true, true, false) == 3)
    check("tier3-offline", ArogyaOfficialAnswer.selectTier(false, false, false, false) == 3)
    check("tier3-nolive", ArogyaOfficialAnswer.selectTier(false, true, false, true) == 3)
    // Scrub: identifiers out, question words stay.
    val v = ArogyaVisit(name = "Neha Devi", village = "Rampur", birthDate = "2026-01-02")
    val s = ArogyaOnlinePolicy.scrub("Neha Devi of Rampur, phone 9811100001, mail asha@x.com, has fever. Born 02/01/2026.", v)
    check("scrub-name", "Neha" !in s && "Rampur" !in s, s)
    check("scrub-contact", "9811100001" !in s && "asha@x.com" !in s, s)
    check("scrub-date", "02/01/2026" !in s, s)
    check("scrub-keeps", "fever" in s, s)
    // Sentence splitting: decimals, danda, list lines.
    check("split-decimal", ArogyaOfficialAnswer.splitSentences("Give 37.5°C ORS. Next one here.").size == 2)
    check("split-hindi", ArogyaOfficialAnswer.splitSentences("साफ रखें। सूखा रखें।").size == 2)
    check("split-list", ArogyaOfficialAnswer.splitSentences("1. Wash hands.\n2. Dry now.") == listOf("Wash hands.", "Dry now."))
    println("TierTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
