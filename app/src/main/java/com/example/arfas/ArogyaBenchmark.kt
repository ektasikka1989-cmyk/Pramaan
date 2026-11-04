package com.example.arfas

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * M1 answer-quality benchmark runner (Batch 0 test aid, debug builds only).
 * Drives the REAL Ask SOP path ([ArogyaVisitAi.run] with [ArogyaAiTask.EXPLAIN])
 * for each question in docs/benchmark/questions.md and logs one line per
 * question. Joined offline with POCKET_RAG_* lines by timestamp.
 * afterSafetyPrompt=true so danger questions measure the guide answer, not the
 * urgent alert; a fresh visit id per question keeps turns independent.
 */
object ArogyaBenchmark {
    private const val TAG = "ArogyaDemo" // captured by _work/run/logs.bat
    data class Question(val id: Int, val lang: String, val text: String, val expected: String)
    data class Row(val id: Int, val provider: String, val ms: Long, val chars: Int, val sources: String, val safety: Boolean, val error: String, val firstWordsMs: Long = -1)

    /**
     * Item 2 speed check: 3 questions asked as one foreground chat (same visit, so Q2 is a
     * real follow-up using session context, Q3 an independent question testing no-hijack).
     * Run once per mode (Cloud first / offline); each question logs AROGYA_SPEEDCHECK_END
     * with first-words + total, joined with the AROGYA_ASK_BREAKDOWN + stage lines.
     */
    val SPEEDCHECK_QUESTIONS: List<Question> = listOf(
        Question(101, "en", "What is the zinc dose for a 4-month-old baby with diarrhoea, and for how many days?", "hbyc p72 / idcf p48"),
        Question(102, "en", "And how should the mother prepare ORS for the same baby?", "hbyc p68-69, follow-up"),
        Question(103, "en", "What should be applied to the umbilical cord stump of a newborn?", "int_b p18, independent")
    )

    val QUESTIONS: List<Question> = listOf(
        Question(1, "en", "How many home visits should an ASHA make for a home-delivered newborn, and on which days?", "incentives p2 / asha_fac p7"),
        Question(2, "en", "What JSY incentive does an ASHA get on completing a pregnant woman's ANC check-ups in a rural area?", "jsy_anc p1"),
        Question(3, "en", "What is the zinc dose for a 4-month-old baby with diarrhoea, and for how many days?", "hbyc p72 / idcf p48"),
        Question(4, "en", "How should a mother prepare ORS at home for a child with diarrhoea?", "hbyc p68-69"),
        Question(5, "en", "What should be applied to the umbilical cord stump of a newborn?", "int_b p18"),
        Question(6, "en", "What should the ASHA check about breastfeeding during a home visit?", "hbyc p37"),
        Question(7, "en", "What are the danger signs in a newborn that need immediate referral?", "int_b p18 + asha_fac p58-61"),
        Question(8, "en", "A 7-day-old baby has fever and is feeding less. What should the ASHA do, and what before reaching the hospital?", "int_b p41/p55?"),
        Question(9, "en", "Which spacing contraceptive methods are available in the family planning programme?", "hbyc p55-56"),
        Question(10, "en", "Which vaccines are due at birth, and what is the BCG dose?", "imm p33"),
        Question(11, "en", "What is the weekly IFA dose for a school-going adolescent girl under Anaemia Mukt Bharat?", "amb p27 AND p48"),
        Question(12, "en", "A baby weighs 2,200 grams. How often should the ASHA visit?", "highrisk p1 or graceful not-found"),
        Question(13, "hi", "इस मॉड्यूल में कौन-कौन से गैर-संचारी रोग शामिल हैं?", "ncd_hi p5"),
        Question(14, "hi", "नवजात शिशु में खतरे के लक्षण क्या हैं?", "int_b p18 + asha_fac p58-61"),
        Question(15, "hi", "दस्त में 4 महीने के बच्चे को जिंक की खुराक क्या है?", "hbyc p72 / idcf p48"),
        Question(16, "hi", "घर पर ORS कैसे बनाएं?", "hbyc p68-69"),
        Question(17, "hi", "गर्भवती महिला की ANC जांच कितनी बार होनी चाहिए?", "induction_en p68 / int_b p198?"),
        Question(18, "hinglish", "Baby ko diarrhoea ho to ORS kaise dena chahiye?", "hbyc p68-69, simple English"),
        Question(19, "oos", "How do I fix a leaking tap?", "R8 message / labelled general"),
    )

    suspend fun runAll(ai: ArogyaVisitAi, onEvent: (String) -> Unit): List<Row> {
        onEvent("Warming up…")
        runCatching { ai.warmUp() }
        Log.i(TAG, "AROGYA_BENCH_GUIDE v=${ai.guideVersion()} want=${ArogyaGuideDb.wantedVersion()}")
        val rows = mutableListOf<Row>()
        QUESTIONS.forEachIndexed { i, q ->
            onEvent("Q${q.id} (${i + 1}/${QUESTIONS.size})…")
            Log.i(TAG, "AROGYA_BENCH_START id=${q.id} lang=${q.lang}")
            val row = try {
                var firstWords = -1L
                val t = System.currentTimeMillis()
                val r = ai.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN, ArogyaVisit(name = "Benchmark", kind = ArogyaKind.FOLLOW_UP), question = q.text, afterSafetyPrompt = true,
                    onProgress = { p -> if (firstWords < 0 && p.stage == "write" && p.draft.isNotBlank()) firstWords = System.currentTimeMillis() - t }))
                val srcs = r.sources.joinToString(";") { it.title + "," + it.locator }.take(400)
                Log.i(TAG, "AROGYA_BENCH_END id=${q.id} online=${r.online} ms=${r.elapsedMs} firstWordsMs=$firstWords chars=${r.text.length} safety=${r.safetyPrompt} sources=$srcs")
                Row(q.id, r.provider, r.elapsedMs, r.text.length, srcs, r.safetyPrompt, "", firstWords)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "AROGYA_BENCH_END id=${q.id} error=${e.message?.take(120)}")
                Row(q.id, "error", 0, 0, "", false, e.message?.take(120).orEmpty())
            }
            rows += row
            onEvent("Q${q.id}: ${row.provider.take(24)} ${row.ms}ms first=${row.firstWordsMs}ms ${row.chars}ch ${row.error}")
        }
        Log.i(TAG, "AROGYA_BENCH_DONE rows=${rows.size}")
        return rows
    }

    /** 3-question foreground speed check (one chat, same visit). See [SPEEDCHECK_QUESTIONS]. */
    suspend fun runSpeedCheck(ai: ArogyaVisitAi, onEvent: (String) -> Unit): List<Row> {
        onEvent("Warming up…")
        runCatching { ai.warmUp() }
        Log.i(TAG, "AROGYA_SPEEDCHECK_GUIDE v=${ai.guideVersion()} profile=${ArogyaFlags.r1Profile}")
        val visit = ArogyaVisit(name = "SpeedCheck", kind = ArogyaKind.FOLLOW_UP)
        val rows = mutableListOf<Row>()
        SPEEDCHECK_QUESTIONS.forEachIndexed { i, q ->
            onEvent("Q${q.id} (${i + 1}/${SPEEDCHECK_QUESTIONS.size})…")
            Log.i(TAG, "AROGYA_SPEEDCHECK_START id=${q.id}")
            val row = try {
                var firstWords = -1L
                val t = System.currentTimeMillis()
                val r = ai.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN, visit, question = q.text, afterSafetyPrompt = true,
                    onProgress = { p -> if (firstWords < 0 && p.stage == "write" && p.draft.isNotBlank()) firstWords = System.currentTimeMillis() - t }))
                val srcs = r.sources.joinToString(";") { it.title + "," + it.locator }.take(400)
                Log.i(TAG, "AROGYA_SPEEDCHECK_END id=${q.id} online=${r.online} ms=${r.elapsedMs} firstWordsMs=$firstWords chars=${r.text.length} safety=${r.safetyPrompt} sources=$srcs")
                Row(q.id, r.provider, r.elapsedMs, r.text.length, srcs, r.safetyPrompt, "", firstWords)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "AROGYA_SPEEDCHECK_END id=${q.id} error=${e.message?.take(120)}")
                Row(q.id, "error", 0, 0, "", false, e.message?.take(120).orEmpty())
            }
            rows += row
            onEvent("Q${q.id}: ${row.provider.take(24)} ${row.ms}ms first=${row.firstWordsMs}ms ${row.chars}ch ${row.error}")
        }
        Log.i(TAG, "AROGYA_SPEEDCHECK_DONE rows=${rows.size}")
        return rows
    }
}
