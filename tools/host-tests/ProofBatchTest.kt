import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean, detail: String = "") { n++; if (!ok) { fails++; println("FAIL $name $detail") } }
fun main() = kotlinx.coroutines.runBlocking {
    // UI2 speed pass: AnswerProof batches every (statement, passage) pair into one re-ranker run.
    // The batched path must give exactly the same verdicts as one call per statement.
    fun fakeLogit(q: String, p: String): Float {
        val qw = q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
        val pw = p.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
        return if (qw.isEmpty()) -5f else ((qw intersect pw).size.toFloat() / qw.size) * 10f - 4f
    }
    var single = 0
    var batchCalls = 0
    var batchPairs = 0
    val scorer: suspend (String, List<String>) -> FloatArray? = { q, ps -> single++; FloatArray(ps.size) { fakeLogit(q, ps[it]) } }
    val pairScorer: suspend (List<Pair<String, String>>) -> FloatArray? = { pairs -> batchCalls++; batchPairs += pairs.size; FloatArray(pairs.size) { fakeLogit(pairs[it].first, pairs[it].second) } }
    val passages = listOf(
        "S1" to "Give ORS after every loose stool. Continue breastfeeding the child.",
        "S2" to "Give zinc tablets for 14 days to every child with diarrhoea.",
        "S3" to "Refer the child at once if there is blood in the stool or the child cannot drink.",
        "S4" to "Wash hands with soap before preparing food.")
    val answer = "Give ORS after every loose stool [S1].\nGive zinc for 14 days [S2].\nRefer at once if there is blood in the stool [S3].\n" +
        "Give bananas twice daily to the child.\nGive zinc for 21 days to the child."
    val old = AnswerProof.check(answer, passages, scorer)
    val oldSingle = single
    single = 0
    val new = AnswerProof.check(answer, passages, scorer, pairScorer = pairScorer)
    check("same-count", old.statements.size == new.statements.size && old.total > 0, "${old.statements.size} vs ${new.statements.size}")
    check("same-verdicts", old.statements.map { Triple(it.passageId, it.supported, it.numbersFound) } == new.statements.map { Triple(it.passageId, it.supported, it.numbersFound) })
    check("same-scores", old.statements.zip(new.statements).all { (a, b) -> a.score == b.score })
    check("one-batch-call", batchCalls == 1 && single == 0, "batch=$batchCalls single=$single")
    check("old-was-per-statement", oldSingle == old.statements.size, "single=$oldSingle statements=${old.statements.size}")
    check("pairs-capped", batchPairs <= old.statements.size * passages.size, "pairs=$batchPairs")
    check("wrong-number-unsupported", new.statements.any { it.text.contains("21") && !it.supported })
    // A failed batch falls back to the per-statement scorer with the same result.
    val failing: suspend (List<Pair<String, String>>) -> FloatArray? = { null }
    val fb = AnswerProof.check(answer, passages, scorer, pairScorer = failing)
    check("fallback-same", fb.statements.map { it.supported } == old.statements.map { it.supported } && single > 0)
    // No scorer at all: the word-overlap path, untouched by the batch.
    val lex = AnswerProof.check(answer, passages, null, pairScorer = pairScorer)
    check("no-scorer-lexical", lex.statements.all { it.score == null })
    // UI5: a long answer is capped at MAX_PROOF_PAIRS (each statement with its single best passage).
    val many = (1..8).map { "S$it" to "Passage $it says give ORS solution number $it often and continue feeding the child $it." }
    val longAnswer = (1..8).joinToString("\n") { "Give ORS solution often and continue feeding the child as step $it." }
    batchPairs = 0; batchCalls = 0
    val capped = AnswerProof.check(longAnswer, many, scorer, pairScorer = pairScorer)
    check("cap-pairs", batchCalls == 1 && batchPairs <= AnswerProof.MAX_PROOF_PAIRS + 8, "pairs=$batchPairs")
    check("cap-still-scored", capped.statements.isNotEmpty() && capped.statements.all { it.score != null })
    // UI5: a timeout (cancellation) stops the check instead of falling back to per-statement scoring.
    val cancelling: suspend (List<Pair<String, String>>) -> FloatArray? = { throw kotlinx.coroutines.CancellationException("timeout") }
    single = 0
    val threw = runCatching { AnswerProof.check(answer, passages, scorer, pairScorer = cancelling) }.exceptionOrNull()
    check("cancel-propagates", threw is kotlinx.coroutines.CancellationException && single == 0, "threw=$threw single=$single")
    println("proofbatch: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
