package com.example.arfas

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 benchmark: all 19 questions from docs/benchmark/questions.md (with the owner's
 * page corrections) through the real Ask SOP path. Mode comes from `-e mode offline|online`;
 * the script sets airplane mode + policy to match. Human scoring (completeness,
 * correctness 0–2) is done later from the saved full answer texts.
 */
@RunWith(AndroidJUnit4::class)
class M1BenchmarkTest {
    data class Q(val id: Int, val lang: String, val text: String, val docs: List<String>, val pages: List<String>, val bothPages: Boolean = false, val ban: List<String> = emptyList())

    // Doc hints match clean TITLES (not file stems): verified against baseline sources.
    private val QUESTIONS = listOf(
        Q(1, "en", "How many home visits should an ASHA make for a home-delivered newborn, and on which days?", listOf("incent", "asha facilitators"), listOf("2", "7")),
        Q(2, "en", "What JSY incentive does an ASHA get on completing a pregnant woman's ANC check-ups in a rural area?", listOf("jsy"), listOf("1")),
        Q(3, "en", "What is the zinc dose for a 4-month-old baby with diarrhoea, and for how many days?", listOf("hbyc", "diarrhoea"), listOf("72", "48")),
        Q(4, "en", "How should a mother prepare ORS at home for a child with diarrhoea?", listOf("hbyc"), listOf("68", "69")),
        Q(5, "en", "What should be applied to the umbilical cord stump of a newborn?", listOf("part b"), listOf("18")),
        Q(6, "en", "What should the ASHA check about breastfeeding during a home visit?", listOf("hbyc"), listOf("37")),
        Q(7, "en", "What are the danger signs in a newborn that need immediate referral?", listOf("part b", "asha facilitators"), listOf("18", "58", "59", "60", "61")),
        Q(8, "en", "A 7-day-old baby has fever and is feeding less. What should the ASHA do, and what before reaching the hospital?", listOf("part b"), listOf("41", "55")),
        Q(9, "en", "Which spacing contraceptive methods are available in the family planning programme?", listOf("hbyc"), listOf("55", "56")),
        Q(10, "en", "Which vaccines are due at birth, and what is the BCG dose?", listOf("immun"), listOf("33")),
        Q(11, "en", "What is the weekly IFA dose for a school-going adolescent girl under Anaemia Mukt Bharat?", listOf("anemia mukt"), listOf("27", "48"), bothPages = true),
        Q(12, "en", "A baby weighs 2,200 grams. How often should the ASHA visit?", listOf("high-risk"), listOf("1")),
        Q(13, "hi", "इस मॉड्यूल में कौन-कौन से गैर-संचारी रोग शामिल हैं?", listOf("asha", "ncd"), listOf("5"), ban = listOf("anm")),
        Q(14, "hi", "नवजात शिशु में खतरे के लक्षण क्या हैं?", listOf("part b", "asha facilitators"), listOf("18", "58", "59", "60", "61")),
        Q(15, "hi", "दस्त में 4 महीने के बच्चे को जिंक की खुराक क्या है?", listOf("hbyc", "diarrhoea"), listOf("72", "48")),
        Q(16, "hi", "घर पर ORS कैसे बनाएं?", listOf("hbyc"), listOf("68", "69")),
        Q(17, "hi", "गर्भवती महिला की ANC जांच कितनी बार होनी चाहिए?", listOf("induction", "part b"), listOf("68", "198")),
        Q(18, "hinglish", "Baby ko diarrhoea ho to ORS kaise dena chahiye?", listOf("hbyc"), listOf("68", "69")),
        Q(19, "oos", "How do I fix a leaking tap?", emptyList(), emptyList()),
    )

    @Test
    fun benchmark() = runBlocking {
        val c = ArogyaSelfTest.ctx()
        val mode = ArogyaSelfTest.args()["mode"] ?: "offline"
        val wantCloud = mode == "online"
        val prevPolicy = ArogyaSelfTest.policy(c)
        ArogyaSelfTest.setPolicy(c, if (wantCloud) AnswerPolicy.CLOUD_FIRST else AnswerPolicy.LOCAL_FIRST)
        val rows = JSONArray()
        var pins = 0
        try {
            val ai = ArogyaSelfTest.newAi(c)
            runCatching { ai.warmUp() }
            QUESTIONS.forEachIndexed { i, q ->
                ArogyaSelfTest.mark("AROGYA_BENCH_START id=${q.id} lang=${q.lang} round=$mode n=${i + 1}/19")
                val t = System.currentTimeMillis()
                val row = JSONObject().put("id", q.id).put("lang", q.lang).put("q", q.text)
                try {
                    val r = ai.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN, ArogyaVisit(name = "M1", kind = ArogyaKind.FOLLOW_UP), question = q.text, afterSafetyPrompt = true))
                    val ms = System.currentTimeMillis() - t
                    val srcText = r.sources.joinToString(" | ") { it.title + " " + it.locator }
                    val gate = ArogyaSelfTest.ownLogLines("POCKET_RAG_LOCAL_GATE", "POCKET_RAG_ACCEPT", "POCKET_RAG_EXTRACTIVE", "POCKET_RAG_INSUFFICIENT", "KB_PHASE14_TRACE").takeLast(4)
                        .joinToString(" // ") { it.substringAfter("PocketRagEngine:").substringAfter("GenericKnowledge").take(120).trim() }.take(400)
                    val pin = if (q.id == 19) {
                        (r.sources.isEmpty() || r.text.contains("not covered", true) || r.text.contains("not in your official guides", true)
                            || r.text.contains("could not", true) || r.text.contains("does not contain", true)).also {
                            row.put("oos_routed", it)
                        }
                    } else {
                        val low = srcText.lowercase()
                        val docOk = q.docs.any { low.contains(it) } && q.ban.none { low.contains(it) }
                        val pagesHit = q.pages.filter { p -> Regex("(^|[^0-9])$p([^0-9]|$)").containsMatchIn(low) }
                        val ok = docOk && (if (q.bothPages) pagesHit.size >= 2 else pagesHit.isNotEmpty())
                        row.put("doc_found", docOk).put("pages_hit", JSONArray(pagesHit))
                        ok
                    }
                    if (pin) pins++
                    row.put("provider", r.provider).put("online", r.online).put("ms", ms).put("chars", r.text.length)
                        .put("sources", srcText.take(500)).put("gate", gate.ifBlank { "unavailable" })
                        .put("devanagari", r.text.any { it in 'ऀ'..'ॿ' }).put("pin", pin).put("text", r.text)
                    ArogyaSelfTest.mark("AROGYA_BENCH_END id=${q.id} online=${r.online} ms=$ms chars=${r.text.length} pin=$pin")
                } catch (e: Exception) {
                    row.put("error", (e.message ?: e.javaClass.simpleName).take(200)).put("pin", false)
                    ArogyaSelfTest.mark("AROGYA_BENCH_END id=${q.id} error=${e.message?.take(120)}")
                }
                rows.put(row)
            }
        } finally {
            ArogyaSelfTest.setPolicy(c, prevPolicy)
        }
        val name = "m1_$mode"
        val md = buildString {
            append("# M1 benchmark ($mode)\n\n- pins: $pins/${QUESTIONS.size}\n\n")
            append("| id | lang | online | ms | chars | pin | sources |\n|---|---|---|---|---|---|---|\n")
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                append("| ${r.getInt("id")} | ${r.optString("lang")} | ${r.optBoolean("online")} | ${r.optLong("ms")} | ${r.optInt("chars")} | ${if (r.optBoolean("pin")) "yes" else "NO"} | ${(r.optString("sources").ifBlank { r.optString("error") }).take(90)} |\n")
            }
            append("\n## Full answers (for human scoring: completeness, correctness 0–2)\n")
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                append("\n### Q${r.getInt("id")} ${r.optString("q")}\n\n${r.optString("text").ifBlank { "(error: ${r.optString("error")})" }}\n")
            }
        }
        ArogyaSelfTest.writeResults(c, name, JSONObject().put("mode", mode).put("pins", pins).put("of", QUESTIONS.size).put("rows", rows), md)
        assertTrue("M1 $mode: no pins at all — retrieval broken?", pins > 0)
    }
}
