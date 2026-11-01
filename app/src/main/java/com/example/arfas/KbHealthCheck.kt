package com.example.arfas

import java.util.Locale

/**
 * Knowledge-base health check (KB validation). Two parts:
 *  1. Search check: passages sampled across the pack's documents are looked up with a question
 *     made from each passage (a key sentence, or a question the phone's model writes); the check
 *     is whether the app's own search returns that passage in its top 5 (recall@5) and first (@1).
 *  2. Data check: missing embeddings, very short passages, duplicates, passages that start
 *     mid-sentence, missing page numbers, document titles that are file names.
 * Score 0-100 (search 60, data 40) with a plain list of what to fix. Pure Kotlin; the probes run in
 * [GenericKnowledgeContextProvider.healthCheck].
 */
object KbHealthCheck {

    data class Stats(
        val rows: Long,
        val documents: Long,
        val missingEmbeddings: Long,
        val shortPassages: Long,
        val duplicatePassages: Long,
        val missingPages: Long,
        /** Of [sampled] passages read, how many start mid-sentence and how many are mostly Hindi. */
        val sampled: Int,
        val midSentence: Int,
        val devanagari: Int,
        val titles: Int,
        val fileNameTitles: Int
    )

    data class Probe(
        val document: String,
        val page: Int?,
        val question: String,
        /** 1-based rank of the sampled passage in the search results, or null when not in the top 5. */
        val rank: Int?,
        val bestRerank: Float?,
        val ms: Long
    )

    data class Report(
        val packName: String,
        val score: Int,
        val recallAt1: Double,
        val recallAt5: Double,
        val probes: List<Probe>,
        val stats: Stats?,
        val findings: List<String>,
        val questionsByModel: Boolean
    )

    /** A search question from a passage: its most informative sentence (8-35 words). */
    fun keySentence(passage: String): String? {
        val candidates = AnswerProof.statements(passage)
            .map { it.trim().trimStart('…', '.', ',', ';', ')', ' ') }
            .filter { s ->
                val words = s.split(Regex("\\s+")).size
                words in 8..35 && !s.contains("http") && s.count { it.isLetter() } >= s.length * 0.6
            }
        return candidates.maxByOrNull { s ->
            val words = s.split(Regex("\\s+"))
            val content = words.count { it.length >= 5 }
            content - kotlin.math.abs(words.size - 18) * 0.3
        }
    }

    fun midSentenceStart(passage: String): Boolean {
        val t = passage.trimStart()
        if (t.isEmpty()) return false
        val c = t.first()
        return t.startsWith("…") || t.startsWith("...") || c in ",;:)]" || (c.isLetter() && c.isLowerCase() && c.code < 0x0250)
    }

    fun mostlyDevanagari(passage: String): Boolean {
        val dev = passage.count { it.code in 0x0900..0x097F }
        val lat = passage.count { it in 'a'..'z' || it in 'A'..'Z' }
        return dev > lat
    }

    fun fileNameLike(title: String?): Boolean {
        val t = title?.trim().orEmpty()
        if (t.isBlank()) return true
        return KnowledgeSourcePresentation.cleanTitle(t) != t
    }

    fun report(packName: String, probes: List<Probe>, stats: Stats?, questionsByModel: Boolean): Report {
        val n = probes.size.coerceAtLeast(1)
        val at1 = probes.count { it.rank == 1 }.toDouble() / n
        val at5 = probes.count { it.rank != null }.toDouble() / n
        val findings = ArrayList<String>()
        var data = 40.0
        if (stats != null && stats.rows > 0) {
            fun share(x: Long) = x.toDouble() / stats.rows
            val mid = if (stats.sampled > 0) stats.midSentence.toDouble() / stats.sampled else 0.0
            if (stats.missingEmbeddings > 0) { data -= 15.0 * minOf(1.0, share(stats.missingEmbeddings) * 10); findings += "${stats.missingEmbeddings} passages have no embedding: meaning-based search cannot find them. Rebuild the pack's embeddings." }
            if (share(stats.duplicatePassages) > 0.02) { data -= 8.0 * minOf(1.0, share(stats.duplicatePassages) * 5); findings += "${pct(share(stats.duplicatePassages))} of passages are exact duplicates (${stats.duplicatePassages}), usually several versions of the same document. ArogyaDoot shows one copy, but keep only the current version so answers cite it (or set knowledge_sources.precedence)." }
            if (share(stats.shortPassages) > 0.05) { data -= 5.0 * minOf(1.0, share(stats.shortPassages) * 4); findings += "${pct(share(stats.shortPassages))} of passages are very short (under 80 characters): merge them with their neighbours." }
            if (mid > 0.15) { data -= 7.0 * minOf(1.0, mid * 2); findings += "${pct(mid)} of passages start mid-sentence (cut at a page or size limit). ArogyaDoot joins the end of the previous passage when it shows one, but re-chunking at sentence ends would give cleaner answers." }
            if (share(stats.missingPages) > 0.05) { data -= 3.0; findings += "${pct(share(stats.missingPages))} of passages have no page number: sources cannot cite a page." }
            if (stats.titles > 0 && stats.fileNameTitles.toDouble() / stats.titles > 0.2) { data -= 2.0; findings += "${stats.fileNameTitles} of ${stats.titles} documents have a file name instead of a title (ArogyaDoot tidies them for display; real titles read better)." }
        } else data = 20.0
        if (at5 < 0.8) findings.add(0, "Search found only ${pct(at5)} of sampled passages in its top 5: check the documents listed below (scanned pages without text, tables flattened into one line, or very similar pages).")
        val score = (at5 * 45 + at1 * 15 + data).toInt().coerceIn(0, 100)
        if (findings.isEmpty()) findings += "No problems found."
        return Report(packName, score, at1, at5, probes, stats, findings, questionsByModel)
    }

    private fun pct(x: Double) = "${(x * 100).toInt()}%"

    fun render(r: Report): String = buildString {
        val verdict = when {
            r.score >= 85 -> "Good"
            r.score >= 70 -> "Usable, with fixes"
            else -> "Needs work"
        }
        append("Knowledge base check: ").append(r.packName).append("\n")
        append("Score ").append(r.score).append("/100 — ").append(verdict).append("\n")
        append("Search: found ").append(pct(r.recallAt5)).append(" of ").append(r.probes.size)
            .append(" sampled passages in the top 5, ").append(pct(r.recallAt1)).append(" first")
            .append(if (r.questionsByModel) " (questions written by the phone's model)" else " (questions made from key sentences)").append("\n")
        r.stats?.let { s ->
            append("Data: ").append(s.rows).append(" passages from ").append(s.documents).append(" documents")
            if (s.sampled > 0) append("; ").append(pct(s.devanagari.toDouble() / s.sampled)).append(" in Hindi")
            append("\n")
        }
        append("\nWhat to fix:\n")
        r.findings.forEach { append("• ").append(it).append("\n") }
        val misses = r.probes.filter { it.rank == null }
        if (misses.isNotEmpty()) {
            append("\nPassages search did not find:\n")
            misses.take(6).forEach { p ->
                append("• ").append(KnowledgeSourcePresentation.cleanTitle(p.document).take(70))
                p.page?.let { append(", page ").append(it) }
                append(" — \"").append(p.question.take(90)).append("\"\n")
            }
        }
        val avg = r.probes.map { it.ms }.average().takeIf { !it.isNaN() }?.toLong()
        if (avg != null) append("\nAverage search time: ").append(String.format(Locale.US, "%.1f", avg / 1000.0)).append(" s")
    }.trim()
}
