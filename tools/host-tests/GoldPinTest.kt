import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean, detail: String = "") { n++; if (!ok) { fails++; println("FAIL $name $detail") } }
fun main() {
    // Part C2: gold-pin pure helpers.
    val ref = ArogyaGoldPin.referenceBlock("Apply nothing to the cord.")
    check("ref-mentions-similar", ref.contains("for a similar question"))
    check("ref-uses-answer", ref.contains("Apply nothing to the cord."))
    check("ref-actual-question", ref.contains("Answer the user's actual question"))
    check("ref-cite-rule", ref.contains("carry their citation"))
    check("ref-silent", ref.contains("Do not mention these notes"))
    val b = ArogyaGoldPin.blockText("S1", "Guide.pdf", "keep dry")
    check("block-format", b == "Source ID: S1\nSource: S1 :: Guide.pdf\n\nkeep dry", b)
    val sr = ArogyaGoldPin.sourceRef("S1", "Integrated ASHA Module", "p.18")
    check("sr-format", sr == "S1 :: Integrated ASHA Module \u2014 p.18", sr)
    val sr2 = ArogyaGoldPin.sourceRef("S2", "Guide", "")
    check("sr-noloc", sr2 == "S2 :: Guide", sr2)
    // UI5: a gold source carries its official link like a normal passage (Open official document).
    val sr3 = ArogyaGoldPin.sourceRef("S1", "Integrated ASHA Module", "p.18", "https://x.in/m.pdf")
    check("sr-url", sr3 == "S1 :: Integrated ASHA Module — p.18 | https://x.in/m.pdf", sr3)
    check("sr-url-opens", KnowledgeSourcePresentation.openTarget(sr3) == "https://x.in/m.pdf", KnowledgeSourcePresentation.openTarget(sr3).orEmpty())
    check("sr-nonhttp-dropped", ArogyaGoldPin.sourceRef("S1", "G", "p.2", "file:///x") == "S1 :: G — p.2")
    check("page-once", ArogyaGuideLibrary.pageUrl("https://x.in/m.pdf#page=18", "p.18") == "https://x.in/m.pdf#page=18")
    check("page-gold", ArogyaGuideLibrary.pageUrl("https://x.in/m.pdf", "p.18") == "https://x.in/m.pdf#page=18")
    // Trim: gold kept fully, trailing normal dropped, at least one normal survives.
    val normals = listOf("a".repeat(100), "b".repeat(100), "c".repeat(100))
    check("trim-none", ArogyaGoldPin.trimNormal(0, normals, 10000) == normals)
    val t1 = ArogyaGoldPin.trimNormal(200, normals, 350)
    check("trim-tail", t1.size == 1 && t1[0] == normals[0], t1.size.toString())
    val t2 = ArogyaGoldPin.trimNormal(9000, normals, 100)
    check("trim-keeps-one", t2.size == 1, t2.size.toString())
    check("trim-empty", ArogyaGoldPin.trimNormal(50, emptyList(), 100).isEmpty())
    check("tok", ArogyaGoldPin.estimateTokens(400) == 100 && ArogyaGoldPin.estimateTokens(401) == 101)
    // Pin order: gold blocks precede normal blocks with S1.. numbering.
    val gold = listOf(ArogyaGoldPin.blockText("S1", "d", "gold1"), ArogyaGoldPin.blockText("S2", "d", "gold2"))
    val norm = listOf(ArogyaGoldPin.blockText("S3", "d", "n1"))
    val pinned = gold + norm
    check("pin-order", pinned[0].startsWith("Source ID: S1") && pinned[2].startsWith("Source ID: S3"))
    // Part C3: stored-copy fallback, strong only, labeled, with source pages.
    val tiny3 = """{"items":[{"id":"T-3","question":{"en":"Q?","hi":"Q?"},"keywords":[],
        "answer":{"en":"Apply nothing.","hi":"\u0915\u0941\u091B \u0928 \u0932\u0917\u093e\u090f\u0902।"},
        "sources":[{"chunk_id":7,"source_name":"s.pdf","title":"Guide","authority":"A","page":3,
        "source_url":"http://x/y.pdf","quote":"Keep dry."}]}]}"""
    val item3 = ArogyaVerifiedAnswers.parseJson(tiny3)[0]
    check("ahi-parsed", item3.aHi.isNotBlank())
    val strong3 = ArogyaVerifiedAnswers.Scored(item3, 0.9, strong = true, medium = false)
    val med3 = ArogyaVerifiedAnswers.Scored(item3, 0.7, strong = false, medium = true)
    val en3 = ArogyaGoldPin.storedCopyIfStrong(strong3, false)
    check("sc-en", en3 != null && en3.first.startsWith("From official guidelines (stored copy)") && en3.first.contains("Apply nothing."))
    check("sc-src", en3 != null && en3.second.size == 1 && en3.second[0].locator == "page 3" && en3.second[0].title == "Guide" && en3.second[0].kind == "guide")
    val hi3 = ArogyaGoldPin.storedCopyIfStrong(strong3, true)
    check("sc-hi", hi3 != null && hi3.first.contains("संग्रहीत प्रति") && hi3.first.contains(item3.aHi))
    check("sc-medium-null", ArogyaGoldPin.storedCopyIfStrong(med3, false) == null)
    if (fails > 0) { println("GoldPinTest: $fails/$n FAILED"); kotlin.system.exitProcess(1) }
    println("GoldPinTest: $n PASS")
}
