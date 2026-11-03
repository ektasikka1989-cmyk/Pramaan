package com.example.arfas

/**
 * Converts only the currently selected KB attachment into a PocketSage-style source packet.
 * Arbitrary uploaded/source-grounded attachments are intentionally invisible here.
 */
class PocketRagEvidenceAssembler(
    private val contextWindowTokens: () -> Int = { 4_096 }
) {
    fun assemble(
        query: String,
        selected: PocketRagSelection,
        attachments: List<PocketRagAttachmentRecord>
    ): PocketRagPacket? {
        val eligible = attachments.filter { a ->
            if (a.attributes["grounding_required"] != "true") return@filter false
            if (a.attributes["knowledge_pack"] != selected.kind) return@filter false
            if (selected.kind == "generic") {
                val packetId = a.attributes["knowledge_pack_id"].orEmpty()
                packetId.isNotBlank() && packetId == selected.id
            } else true
        }
        if (eligible.isEmpty()) return null

        val sources = normalizeSources(selected, eligible)
        val rawBlocks = eligible.flatMap { a -> blocksFor(a, sources) }
        val requestedOutput = GenericKbTokenBudget.desiredOutputTokens(
            query = query,
            clauses = queryPlanClauseCount(query),
            evidence = rawBlocks.sumOf { it.text.length }
        )
        val contextTokens = contextWindowTokens().coerceAtLeast(1_024)
        val desiredOutput = requestedOutput.coerceAtMost((contextTokens * 0.45f).toInt().coerceAtLeast(256))
        /*
         * LiteRT/Gemma tokenisation is substantially denser than the old
         * chars/4 estimate for procurement PDFs (identifiers, tables and
         * punctuation routinely average 1.3–1.8 chars/token).  The previous
         * estimate therefore admitted ~9k chars into a 4,096-token context;
         * the runtime rejected those prompts before generation.  Reserve a
         * prompt/system envelope and use a conservative empirical ratio.  The
         * budget is derived solely from the runtime context and remains
         * corpus/query independent.
         */
        val reservedPromptTokens = 1_050 + desiredOutput.coerceAtMost(768)
        val evidenceTokens = (contextTokens - reservedPromptTokens).coerceAtLeast(768)
        val evidenceBudget = (evidenceTokens * 1.55f).toInt().coerceAtLeast(1_800)
        android.util.Log.d(
            "PocketRagEvidenceAssembler",
            "POCKET_RAG_EVIDENCE_BUDGET contextTokens=$contextTokens reservedPromptTokens=$reservedPromptTokens " +
                "budgetChars=$evidenceBudget rawChars=${rawBlocks.sumOf { it.text.length }}"
        )
        val seen = HashSet<String>()
        val chosen = ArrayList<PocketRagEvidenceBlock>()
        var usedChars = 0
        for (raw in rawBlocks) {
            val clean = raw.text.trim()
            if (clean.isBlank()) continue
            val key = clean.lowercase().replace(Regex("\\s+"), " ").take(1_200)
            if (!seen.add(key)) continue
            val cost = clean.length + 24
            if (chosen.isNotEmpty() && usedChars + cost > evidenceBudget) continue
            if (chosen.isEmpty() && cost > evidenceBudget) {
                val bounded = clean.take(evidenceBudget.coerceAtLeast(200)).trim()
                chosen += raw.copy(id = "E1", text = bounded)
                usedChars += bounded.length
            } else {
                chosen += raw.copy(id = "E${chosen.size + 1}")
                usedChars += cost
            }
        }
        if (chosen.isEmpty()) return null

        val coverageComplete = eligible.all { a ->
            a.attributes["kb_coverage_complete"]?.toBooleanStrictOrNull() != false &&
                a.attributes["kb_evidence_complete_packet"]?.toBooleanStrictOrNull() != false
        }
        val clauseCoverage = decodeClauseCoverage(eligible)
        val complete = if (clauseCoverage.isNotEmpty()) {
            clauseCoverage.all { it.state == GenericKbCoverageState.SUPPORTED }
        } else coverageComplete
        val followUpOf = eligible.firstNotNullOfOrNull { it.attributes["kb_follow_up_of"]?.trim()?.takeIf { f -> f.isNotBlank() } }.orEmpty()
        val bestRelevance = eligible.firstNotNullOfOrNull { it.attributes["kb_best_relevance"]?.toFloatOrNull() }
        val valueConflict = eligible.firstNotNullOfOrNull { it.attributes["kb_value_conflict"]?.trim()?.takeIf { v -> v.isNotBlank() } }.orEmpty()
        val terms = eligible.firstNotNullOfOrNull { it.attributes["kb_terms"]?.trim()?.takeIf { v -> v.isNotBlank() } }.orEmpty()
        val goldReference = eligible.firstNotNullOfOrNull { it.attributes["kb_gold_reference"]?.trim()?.takeIf { v -> v.isNotBlank() } }.orEmpty()
        return PocketRagPacket(selected, query.trim(), chosen, sources, complete, clauseCoverage, followUpOf, bestRelevance, valueConflict, terms, goldReference)
    }

    private fun queryPlanClauseCount(query: String): Int =
        GenericKbQueryPlanner.plan(query, query).clauses.size

    private fun decodeClauseCoverage(
        attachments: List<PocketRagAttachmentRecord>
    ): List<GenericKbClauseCoverage> = attachments.asSequence()
        .flatMap { it.attributes["kb_clause_coverage"].orEmpty().lineSequence() }
        .mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size < 2) return@mapNotNull null
            val state = runCatching { GenericKbCoverageState.valueOf(parts[1]) }.getOrNull()
                ?: return@mapNotNull null
            GenericKbClauseCoverage(
                clauseId = parts[0].trim(),
                state = state,
                sourceIds = parts.getOrNull(2).orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
            )
        }.distinctBy { it.clauseId }
        .toList()

    private fun blocksFor(
        attachment: PocketRagAttachmentRecord,
        sources: List<PocketRagSource>
    ): List<PocketRagEvidenceBlock> {
        val text = attachment.text.trim()
        if (text.isBlank()) return emptyList()
        if (attachment.attributes["knowledge_pack"] == "generic") {
            val matches = Regex("(?m)^Source ID:\\s*(S\\d+)\\s*$").findAll(text).toList()
            if (matches.isNotEmpty()) {
                return matches.mapIndexed { index, match ->
                    val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
                    val block = text.substring(match.range.first, end).trim()
                    val sourceId = match.groupValues[1].uppercase()
                    PocketRagEvidenceBlock("", sourceId, block)
                }
            }
        }

        val sourceHints = sources.map { it.id }.ifEmpty { listOf(attachment.sourceId) }
        val parts = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.length >= 24 }
        if (parts.isEmpty()) return listOf(PocketRagEvidenceBlock("", sourceHints.first(), text))
        return parts.mapIndexed { index, part ->
            PocketRagEvidenceBlock("", sourceHints.getOrElse(index) { sourceHints.last() }, part)
        }
    }

    private fun normalizeSources(
        selected: PocketRagSelection,
        eligible: List<PocketRagAttachmentRecord>
    ): List<PocketRagSource> {
        val out = LinkedHashMap<String, PocketRagSource>()
        eligible.forEach { a ->
            val genericRefs = a.attributes["kb_source_refs"].orEmpty().lineSequence()
                .map { it.trim() }.filter { it.isNotBlank() }.toList()
            genericRefs.forEachIndexed { index, raw ->
                val id = Regex("^\\s*(S\\d+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.uppercase()
                    ?: "S${index + 1}"
                out.putIfAbsent(id, PocketRagSource(id = id, display = raw.take(700)))
            }

            if (genericRefs.isEmpty()) {
                val sourceParts = a.attributes["kb_sources"].orEmpty().split("||")
                    .map { it.trim() }.filter { it.isNotBlank() }
                val recordIds = a.attributes["kb_record_ids"].orEmpty().split(',')
                    .map { it.trim() }.filter { it.isNotBlank() }
                sourceParts.forEachIndexed { index, raw ->
                    val id = "S${index + 1}"
                    out.putIfAbsent(
                        id,
                        PocketRagSource(
                            id = id,
                            display = raw.take(700),
                            recordId = recordIds.getOrNull(index).orEmpty()
                        )
                    )
                }
            }
        }
        if (out.isEmpty()) {
            out["S1"] = PocketRagSource("S1", selected.displayName.ifBlank { selected.id })
        }
        return out.values.toList()
    }
}
