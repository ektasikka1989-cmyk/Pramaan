import com.example.arfas.*
import java.io.File
var fails = 0; var n = 0
fun check(name: String, ok: Boolean, detail: String = "") { n++; if (!ok) { fails++; println("FAIL $name $detail") } }
fun main() {
    // Part C1: verified-answers parsing, scoring, cache codec.
    val tiny = """{"items":[{"id":"T-1","question":{"en":"What is ORS?","hi":"ओआरएस क्या है?"},
        "keywords":["ors","dehydration"],"answer":{"en":"ORS is oral rehydration salts.","hi":"x"},
        "sources":[{"chunk_id":7,"source_name":"s.pdf","title":"Guide","authority":"A","page":3,"source_url":"","quote":"ORS treats dehydration."}]}]}"""
    val one = ArogyaVerifiedAnswers.parseJson(tiny)
    check("parse-one", one.size == 1 && one[0].id == "T-1" && one[0].qEn == "What is ORS?" && one[0].qHi == "ओआरएस क्या है?")
    check("parse-src", one[0].sources.size == 1 && one[0].sources[0].chunkId == 7 && one[0].sources[0].page == 3 && one[0].keywords == listOf("ors", "dehydration"))
    check("parse-bad", ArogyaVerifiedAnswers.parseJson("not json").isEmpty())
    var dir: File? = File(System.getProperty("user.dir"))
    var assetFile = File("app/src/main/assets/verified_answers.json")
    while (dir != null && !File(dir, "app/src/main/assets/verified_answers.json").isFile) dir = dir.parentFile
    if (dir != null) assetFile = File(dir, "app/src/main/assets/verified_answers.json")
    check("asset-present", assetFile.isFile, System.getProperty("user.dir"))
    val asset = assetFile.readBytes()
    val items = ArogyaVerifiedAnswers.parseJson(asset.decodeToString())
    check("asset-100", items.size == 100)
    check("asset-va001", items[0].id == "VA-001" && items[0].qEn == "What should be applied on the baby's cord stump?" && items[0].sources.any { it.chunkId == 1672 && it.page == 18 })
    check("tokens", ArogyaVerifiedAnswers.tokens("Baby's Cord!") == setOf("baby", "s", "cord"))
    check("hinglish", ArogyaVerifiedAnswers.HINGLISH["naal"] == "cord" && ArogyaVerifiedAnswers.HINGLISH["tika"] == "vaccine")
    check("kw-exact", ArogyaVerifiedAnswers.keywordOverlap("What should be applied on the baby's cord stump?", items[0]) == 1.0)
    val hw = ArogyaVerifiedAnswers.keywordOverlap("baby ki naal par kya lagana chahiye", items[0])
    check("kw-hinglish", hw > 0.2, "overlap=$hw")
    val va = FloatArray(384) { 1f }
    val vb = FloatArray(384) { 1f }
    check("cos-same", ArogyaVerifiedAnswers.cosine(va, vb) == 1.0)
    val za = FloatArray(384) { if (it % 2 == 0) 1f else 0f }
    val zb = FloatArray(384) { if (it % 2 == 0) 0f else 1f }
    check("cos-orth", ArogyaVerifiedAnswers.cosine(za, zb) == 0.0)
    check("cos-size", ArogyaVerifiedAnswers.cosine(FloatArray(3) { 1f }, va) == 0.0)
    val sc = ArogyaVerifiedAnswers.score("What should be applied on the baby's cord stump?", items[0], va, vb, null)
    check("score-blend", Math.abs(sc - 1.0) < 1e-9, "score=$sc")
    // Cache codec round-trip + version guard.
    val vecs = mapOf("VA-001" to (va to vb))
    val cache = ArogyaVerifiedAnswers.encodeCache(asset, vecs)
    val back = ArogyaVerifiedAnswers.decodeCache(asset, cache)
    check("codec-roundtrip", back != null && back!!.size == 1 && back["VA-001"]!!.first.contentEquals(va))
    check("codec-tamper", ArogyaVerifiedAnswers.decodeCache(asset + 0.toByte(), cache) == null)
    val broken = cache.copyOf()
    broken[2] = (broken[2] + 1).toByte()
    check("codec-corrupt", ArogyaVerifiedAnswers.decodeCache(asset, broken) == null)
    // Live match: keyword-only, then semantic with a deterministic fake embed.
    ArogyaVerifiedAnswers.configure(asset)
    check("match-kw", ArogyaVerifiedAnswers.match("What should be applied on the baby's cord stump?")?.item?.id == "VA-001")
    check("match-blank", ArogyaVerifiedAnswers.match("  ") == null)
    // Final F1: stop-words, 2-word keyword rule, cosine rescale, top1-top2 margin.
    check("f1-stop-en", ArogyaVerifiedAnswers.contentTokens("What is the capital of India?") == setOf("capital", "india"))
    check("f1-stop-hinglish", ArogyaVerifiedAnswers.contentTokens("baby ki naal par kya lagana chahiye") == setOf("baby", "cord", "lagana"))
    check("f1-tok-hindi", ArogyaVerifiedAnswers.tokens("नवजात की नाल").contains("नवजात"))
    check("f1-stop-hindi", ArogyaVerifiedAnswers.contentTokens("नवजात की नाल पर क्या लगाना चाहिए?") == setOf("नवजात", "नाल", "लगाना"))
    check("f1-kw-one-word", ArogyaVerifiedAnswers.score("is the cord ok", items[0], null, null, null) == 0.0)
    check("f1-kw-two-words", ArogyaVerifiedAnswers.score("cord stump", items[0], null, null, null) == 1.0)
    check("f1-cosadj", ArogyaVerifiedAnswers.cosAdj(0.75) == 0.0 && ArogyaVerifiedAnswers.cosAdj(0.70) == 0.0 &&
        Math.abs(ArogyaVerifiedAnswers.cosAdj(0.85) - 0.5) < 1e-9 && ArogyaVerifiedAnswers.cosAdj(0.99) == 1.0)
    val unrelatedVec = FloatArray(384) { if (it < 300) 1f else 0f }   // cos 0.78 with all-ones: typical E5 "unrelated"
    val onesVec = FloatArray(384) { 1f }
    val unrel = ArogyaVerifiedAnswers.score("How do I make tea?", items[0], unrelatedVec, onesVec, onesVec)
    check("f1-unrelated-cos-low", unrel < ArogyaVerifiedAnswers.MEDIUM, "score=$unrel")
    check("f1-margin-tie", ArogyaVerifiedAnswers.classify(0.90, 0.88) == (false to false))
    check("f1-margin-strong", ArogyaVerifiedAnswers.classify(0.90, 0.87) == (true to false))
    check("f1-margin-medium", ArogyaVerifiedAnswers.classify(0.70, 0.60) == (false to true))
    check("f1-margin-none", ArogyaVerifiedAnswers.classify(0.50, 0.10) == (false to false))
    val unrelated = listOf(
        "How do I make masala tea?", "Who won the cricket match yesterday?", "What is the capital of India?",
        "How do I fix my phone battery?", "How do I cook rice?", "How do I book a train ticket?",
        "What is the weather today?", "How do I open a bank account?", "Which movie should I watch tonight?",
        "How do I connect to the wifi?")
    for (q in unrelated) {
        val m = ArogyaVerifiedAnswers.match(q)
        check("f1-nomatch", m == null || (!m.strong && !m.medium), "q=$q top=${m?.item?.id} score=${m?.score}")
    }
    var selfEn = 0
    var selfHi = 0
    for (it in items) {
        val me = ArogyaVerifiedAnswers.match(it.qEn)
        if (me != null && me.item.id == it.id && (me.strong || me.medium)) selfEn++
        val mh = ArogyaVerifiedAnswers.match(it.qHi)
        if (mh != null && mh.item.id == it.id && (mh.strong || mh.medium)) selfHi++
    }
    println("goldmatch: keyword-only self-pin EN $selfEn/100, HI $selfHi/100")
    check("f1-self-en", selfEn >= 90, "selfEn=$selfEn")
    check("f1-self-hi", selfHi >= 90, "selfHi=$selfHi")
    val tmp = File.createTempFile("vaemb", ".bin")
    try {
        fun fakeEmbed(s: String): FloatArray {
            val h = s.hashCode()
            return FloatArray(384) { (((h + it * 31) % 100 + 100) % 100) / 100f }
        }
        ArogyaVerifiedAnswers.warmAsync(asset, tmp) { fakeEmbed(it) }
        var waited = 0
        while (!ArogyaVerifiedAnswers.isSemantic() && waited < 30000) {
            Thread.sleep(200)
            waited += 200
        }
        check("warm-semantic", ArogyaVerifiedAnswers.isSemantic())
        val m = ArogyaVerifiedAnswers.match("What should be applied on the baby's cord stump?", fakeEmbed("query: What should be applied on the baby's cord stump?"))
        check("match-strong", m != null && m!!.item.id == "VA-001" && m.strong && m.score >= 0.80, "score=${m?.score}")
    } finally {
        runCatching { tmp.delete() }
    }
    println("goldmatch: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
