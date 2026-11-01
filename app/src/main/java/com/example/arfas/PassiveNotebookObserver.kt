package com.example.arfas

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Passive, process-scoped session Notebook.
 *
 * Contract:
 * - every accepted turn is stored verbatim with provider attribution;
 * - it never routes, retrieves, or decides the answer;
 * - the UI observes [state] directly, so Notebook visibility does not depend on
 *   a transient turn-event callback;
 * - structured fields are evidence-conservative and use no disease/topic lists.
 */
class PassiveNotebookObserver(context: Context) : AcceptedTurnObserver {
    companion object {
        private const val TAG = "PassiveNotebook"
        private const val PREFS = "phase1_passive_notebook"
        private const val KEY_ROWS = "accepted_rows_v1"
        private const val KEY_SOURCES = "accepted_sources_v1"
        private const val KEY_VERIFIED_VISUALS = "verified_visuals_v1"
        private const val KEY_CURRENT_GOAL = "current_goal_v1"
        private const val KEY_REJECTED_THEORIES = "rejected_theories_v1"
        private const val KEY_TYPED_ENTRIES = "typed_entries_v2"
        private const val KEY_THEORY = "working_theory_v1"
        private const val KEY_TS_RULED_OUT = "troubleshooting_ruled_out_v1"
        /** Provenance of troubleshooting step results (see recordCheckResult). */
        private const val TROUBLESHOOTING_STEP = "TROUBLESHOOTING_STEP"
        private const val MAX_TURNS = 40
        private const val MAX_TYPED_ENTRIES = 80
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile private var conversationId: String = "default"

    fun setConversationId(id: String) = synchronized(lock) {
        conversationId = id.trim().ifBlank { "default" }
        publishLocked()
        Log.d(TAG, "NOTEBOOK_CONVERSATION_SWITCH id=${conversationId.take(12)}")
    }

    fun pruneConversationIds(validIds: Set<String>) = synchronized(lock) {
        if (validIds.isEmpty()) return@synchronized
        val editor = prefs.edit()
        prefs.all.keys.forEach { storedKey ->
            val split = storedKey.lastIndexOf("__")
            if (split > 0) {
                val id = storedKey.substring(split + 2)
                if (id.isNotBlank() && id !in validIds) editor.remove(storedKey)
            }
        }
        editor.apply()
    }

    private fun key(base: String): String = "${base}__${conversationId}"

    data class Row(val user: String, val assistant: String, val provider: String)

    private data class StoredVisualObservation(
        val timestampMs: Long,
        val source: String,
        val text: String
    )

    private val _state = MutableStateFlow(InvestigationNotebook())
    val state: StateFlow<InvestigationNotebook> = _state.asStateFlow()

    init {
        _state.value = snapshot()
    }

    override suspend fun onAcceptedTurn(
        userText: String,
        assistantText: String,
        provider: AndroBaselineRuntime.Provider
    ) {
        synchronized(lock) {
            val rows = readRowsLocked().toMutableList()
            val cleanUser = userText.trim()
            rows += Row(cleanUser, assistantText.trim(), provider.name)
            val bounded = rows.takeLast(MAX_TURNS)
            val edit = prefs.edit().putString(key(KEY_ROWS), encodeRows(bounded))

            if (prefs.getString(key(KEY_CURRENT_GOAL), "").orEmpty().isBlank() && cleanUser.isNotBlank()) {
                edit.putString(key(KEY_CURRENT_GOAL), encode(cleanUser.take(240)))
            }
            edit.apply()
            publishLocked()
            Log.d(TAG, "NOTEBOOK_ACCEPTED turns=${bounded.size} provider=$provider")
        }
    }

    /** Source/provenance only. A filename is never promoted to clinical/visual evidence. */
    fun recordAttachmentSource(label: String) = synchronized(lock) {
        val clean = label.trim().take(120)
        if (clean.isBlank()) return@synchronized
        val existing = prefs.getString(key(KEY_SOURCES), "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        if (clean !in existing) existing += clean
        prefs.edit().putString(key(KEY_SOURCES), existing.takeLast(12).joinToString("\n")).apply()
        Log.d(TAG, "NOTEBOOK_SOURCE_ACCEPTED label=${clean.take(60)}")
    }

    /**
     * Only a visual observation already accepted by the dedicated vision path may
     * enter the Notebook as visual evidence.
     */
    fun recordVerifiedVisualObservation(
        observation: String,
        source: String = "CLOUD_VISION"
    ) = synchronized(lock) {
        val clean = FixSightOutputSanitizer.sanitize(observation).trim().take(700)
        if (clean.isBlank()) return@synchronized
        val entry = "${System.currentTimeMillis()}|${source.trim().ifBlank { "CLOUD_VISION" }}|${encode(clean)}"
        val existing = prefs.getString(key(KEY_VERIFIED_VISUALS), "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        val normalized = existing.mapNotNull { decodeVisualLine(it)?.text }.map { it.lowercase() }
        if (clean.lowercase() !in normalized) existing += entry
        prefs.edit().putString(key(KEY_VERIFIED_VISUALS), existing.takeLast(24).joinToString("\n")).apply()
        publishLocked()
        Log.d(TAG, "NOTEBOOK_VISUAL_ACCEPTED source=${source.take(24)} chars=${clean.length}")
    }

    fun recordResolution(
        userAcknowledgement: String,
        resolutionAction: String
    ) = synchronized(lock) {
        val existing = readTypedEntriesLocked().toMutableList()
        val ack = FixSightOutputSanitizer.sanitize(userAcknowledgement).trim().take(500)
        val action = FixSightOutputSanitizer.sanitize(resolutionAction).trim().take(700)
        val content = when {
            action.isNotBlank() -> "Resolution achieved after action: $action. User confirmed the issue was resolved${if (ack.isNotBlank()) ": $ack" else "."}"
            ack.isNotBlank() -> "Resolution confirmed by user: $ack"
            else -> "Resolution confirmed by user."
        }
        val entry = NotebookEntry(
            content = content,
            source = ObservationSource.USER,
            confidence = 0.95f,
            type = NotebookEvidenceType.RESOLUTION,
            provenanceId = "TROUBLESHOOTING_SESSION_END"
        )
        val key = normalKey(content)
        if (existing.none { it.type == NotebookEvidenceType.RESOLUTION && normalKey(it.content) == key }) {
            existing += entry
            prefs.edit().putString(key(KEY_TYPED_ENTRIES), encodeTypedEntries(existing.takeLast(MAX_TYPED_ENTRIES))).apply()
            publishLocked()
        }
        Log.d(TAG, "NOTEBOOK_RESOLUTION_ACCEPTED action=${action.take(120)}")
    }

    fun updateCurrentGoal(goal: String) = synchronized(lock) {
        val clean = FixSightOutputSanitizer.sanitize(goal).trim().take(240)
        if (clean.isBlank()) return@synchronized
        prefs.edit().putString(key(KEY_CURRENT_GOAL), encode(clean)).apply()
        publishLocked()
        Log.d(TAG, "NOTEBOOK_GOAL_UPDATED chars=${clean.length}")
    }

    /** Troubleshooting's current working diagnosis (shown as "Theory" and used in the case report). */
    fun updateTheory(theory: String) = synchronized(lock) {
        val clean = FixSightOutputSanitizer.sanitize(theory).trim().take(260)
        if (clean.isBlank()) return@synchronized
        prefs.edit().putString(key(KEY_THEORY), encode(clean)).apply()
        publishLocked()
        Log.d(TAG, "NOTEBOOK_THEORY_UPDATED chars=${clean.length}")
    }

    /** A step FixSight gave and what the user reported after it (shown under "Tried"). */
    fun recordCheckResult(step: String, result: String) = synchronized(lock) {
        val cleanStep = FixSightOutputSanitizer.sanitize(step).replace(Regex("\\s+"), " ").trim().take(220)
        val cleanResult = FixSightOutputSanitizer.sanitize(result).replace(Regex("\\s+"), " ").trim().take(200)
        if (cleanStep.isBlank() || cleanResult.isBlank()) return@synchronized
        val content = "$cleanStep -> $cleanResult"
        val existing = readTypedEntriesLocked().toMutableList()
        if (existing.none { it.type == NotebookEvidenceType.ACTION_RESULT && normalKey(it.content) == normalKey(content) }) {
            existing += NotebookEntry(
                content = content,
                source = ObservationSource.USER,
                confidence = 0.70f,
                type = NotebookEvidenceType.ACTION_RESULT,
                provenanceId = TROUBLESHOOTING_STEP
            )
            prefs.edit().putString(key(KEY_TYPED_ENTRIES), encodeTypedEntries(existing.takeLast(MAX_TYPED_ENTRIES))).apply()
            publishLocked()
        }
    }

    /** A cause eliminated during camera troubleshooting (shown under "Ruled Out"). */
    fun recordTroubleshootingRuledOut(cause: String) = synchronized(lock) {
        val clean = FixSightOutputSanitizer.sanitize(cause).trim().take(260)
        if (clean.isBlank()) return@synchronized
        val existing = readTroubleshootingRuledOutLocked().toMutableList()
        if (existing.none { it.equals(clean, ignoreCase = true) }) existing += clean
        prefs.edit().putString(key(KEY_TS_RULED_OUT), existing.takeLast(12).joinToString("\n") { encode(it) }).apply()
        publishLocked()
    }

    /** Troubleshooting's working diagnosis and step results, for the troubleshooting prompt only. */
    fun troubleshootingNotes(): String = synchronized(lock) {
        val theory = readTheoryLocked()
        val ruledOut = readTroubleshootingRuledOutLocked()
        val steps = readTypedEntriesLocked().filter { it.type == NotebookEvidenceType.ACTION_RESULT && it.provenanceId.startsWith(TROUBLESHOOTING_STEP) }
        if (theory.isBlank() && ruledOut.isEmpty() && steps.isEmpty()) return@synchronized ""
        buildString {
            if (theory.isNotBlank()) appendLine("Working diagnosis: $theory")
            if (ruledOut.isNotEmpty()) appendLine("Ruled out: ${ruledOut.takeLast(6).joinToString("; ")}")
            if (steps.isNotEmpty()) {
                appendLine("Steps already given -> what the user reported:")
                steps.takeLast(8).forEach { appendLine("- ${it.content}") }
            }
        }.trim()
    }

    /**
     * Snapshot for the selected-knowledge (ANM) provider: identical to what it received before
     * troubleshooting wrote Theory / Tried / Ruled Out, so that data stays inside troubleshooting.
     */
    fun snapshotWithoutTroubleshooting(): InvestigationNotebook = synchronized(lock) {
        withoutTroubleshootingLocked()
    }

    private fun withoutTroubleshootingLocked(): InvestigationNotebook {
        val full = buildSnapshotLocked()
        val tsSteps = readTypedEntriesLocked()
            .filter { it.type == NotebookEvidenceType.ACTION_RESULT && it.provenanceId.startsWith(TROUBLESHOOTING_STEP) }
            .map { it.content }.toSet()
        return full.copy(
            primaryTheory = "",
            checksPerformed = full.checksPerformed.filterNot { it in tsSteps }.toMutableList(),
            rejectedTheories = readRejectedTheoriesLocked().toMutableList(),
            typedEntries = full.typedEntries.filterNot {
                it.type == NotebookEvidenceType.ACTION_RESULT && it.provenanceId.startsWith(TROUBLESHOOTING_STEP)
            }.toMutableList()
        )
    }

    fun recordRejectedTheory(theory: String) = synchronized(lock) {
        val clean = FixSightOutputSanitizer.sanitize(theory).trim().take(260)
        if (clean.isBlank()) return@synchronized
        val existing = readRejectedTheoriesLocked().toMutableList()
        if (existing.none { it.equals(clean, ignoreCase = true) }) existing += clean
        prefs.edit()
            .putString(key(KEY_REJECTED_THEORIES), existing.takeLast(12).joinToString("\n") { encode(it) })
            .apply()
        publishLocked()
        Log.d(TAG, "NOTEBOOK_THEORY_REJECTED chars=${clean.length}")
    }

    /**
     * Canonical accepted-turn investigation memory.  This is additive to the
     * bounded transcript and survives transcript rollover.  User statements are
     * stored as reported claims; model-authored visual interpretations remain
     * OBSERVATIONs and are never promoted to confirmed facts automatically.
     */
    fun recordStructuredAcceptedTurn(
        userText: String,
        assistantText: String,
        provider: AndroBaselineRuntime.Provider,
        visualObservation: String = "",
        sourceIds: List<String> = emptyList()
    ) = synchronized(lock) {
        val existing = readTypedEntriesLocked().toMutableList()
        val provenance = buildString {
            append(provider.name)
            if (sourceIds.isNotEmpty()) append(":").append(sourceIds.take(4).joinToString(","))
        }.take(180)
        fun addUnique(entry: NotebookEntry) {
            val key = normalKey(entry.content)
            if (key.isBlank()) return
            if (existing.none { it.type == entry.type && normalKey(it.content) == key }) existing += entry
        }

        val cleanUser = FixSightOutputSanitizer.sanitize(userText).trim().take(500)
        if (cleanUser.isNotBlank() && !cleanUser.trimEnd().endsWith("?")) {
            addUnique(NotebookEntry(cleanUser, ObservationSource.USER, 0.70f, type = NotebookEvidenceType.USER_STATEMENT, provenanceId = provenance))
        }
        val cleanVisual = FixSightOutputSanitizer.sanitize(visualObservation).trim().take(700)
        if (cleanVisual.isNotBlank()) {
            addUnique(NotebookEntry(cleanVisual, if (provider == AndroBaselineRuntime.Provider.CLOUD) ObservationSource.CLOUD else ObservationSource.LOCAL_MODEL, 0.60f, type = NotebookEvidenceType.OBSERVATION, provenanceId = provenance))
            if (cleanUser.isNotBlank() && VisualEvidenceReconciler.isPotentialConflict(cleanUser, cleanVisual)) {
                addUnique(
                    NotebookEntry(
                        content = VisualEvidenceReconciler.formatConflict(cleanUser, cleanVisual),
                        source = ObservationSource.MANUAL,
                        confidence = 0f,
                        type = NotebookEvidenceType.VISUAL_CONFLICT,
                        provenanceId = provenance
                    )
                )
            }
        }
        val cleanAssistant = FixSightOutputSanitizer.sanitize(assistantText).trim().take(700)
        if (cleanAssistant.isNotBlank()) {
            addUnique(NotebookEntry(cleanAssistant, if (provider == AndroBaselineRuntime.Provider.CLOUD) ObservationSource.CLOUD else ObservationSource.LOCAL_MODEL, 0.50f, type = NotebookEvidenceType.OTHER, provenanceId = provenance))
        }

        prefs.edit().putString(key(KEY_TYPED_ENTRIES), encodeTypedEntries(existing.takeLast(MAX_TYPED_ENTRIES))).apply()
        publishLocked()
        Log.d(TAG, "NOTEBOOK_STRUCTURED_ACCEPTED entries=${existing.size.coerceAtMost(MAX_TYPED_ENTRIES)} visual=${cleanVisual.isNotBlank()} provider=$provider")
    }

    fun clear() = synchronized(lock) {
        prefs.edit()
            .remove(key(KEY_ROWS))
            .remove(key(KEY_SOURCES))
            .remove(key(KEY_VERIFIED_VISUALS))
            .remove(key(KEY_CURRENT_GOAL))
            .remove(key(KEY_REJECTED_THEORIES))
            .remove(key(KEY_TYPED_ENTRIES))
            .remove(key(KEY_THEORY))
            .remove(key(KEY_TS_RULED_OUT))
            .apply()
        _state.value = InvestigationNotebook()
        Log.d(TAG, "NOTEBOOK_CLEARED")
    }

    fun snapshot(): InvestigationNotebook = synchronized(lock) {
        buildSnapshotLocked()
    }

    fun readRows(): List<Row> = synchronized(lock) {
        readRowsLocked()
    }

    /**
     * Compact provider-neutral case memory for Local/Cloud hand-off.
     * The persistent Notebook remains the source of truth; this is only a bounded
     * rendering for the current model turn. Assistant prose is not promoted to fact.
     */
    fun renderTurnContext(maxChars: Int = 3_200, recentTurns: Int = 4): String = synchronized(lock) {
        // Shared turn context for chat / knowledge-base turns: unchanged by troubleshooting's notes.
        val notebook = withoutTroubleshootingLocked()
        val rows = readRowsLocked().takeLast(recentTurns.coerceIn(0, 6))
        buildString {
            if (notebook.currentGoal.isNotBlank()) appendLine("Current goal: ${notebook.currentGoal}")
            if (notebook.confirmedFacts.isNotEmpty()) {
                appendLine("Confirmed user facts:")
                notebook.confirmedFacts.takeLast(12).forEach { appendLine("- $it") }
            }
            if (notebook.userStatements.isNotEmpty()) {
                appendLine("Reported user statements (not visually verified):")
                notebook.userStatements.takeLast(12).forEach { appendLine("- $it") }
            }
            if (notebook.visualFindings.isNotEmpty()) {
                appendLine("Historical visual findings (must be rechecked against the current frame):")
                notebook.visualFindings.takeLast(10).forEach { appendLine("- $it") }
            }
            if (notebook.visualConflicts.isNotEmpty()) {
                appendLine("Visual conflicts requiring verification:")
                notebook.visualConflicts.takeLast(8).forEach { appendLine("- $it") }
            }
            if (notebook.checksPerformed.isNotEmpty()) {
                appendLine("Checks / accepted investigation memory:")
                notebook.checksPerformed.takeLast(10).forEach { appendLine("- $it") }
            }
            if (notebook.rejectedTheories.isNotEmpty()) {
                appendLine("Ruled out / rejected theories:")
                notebook.rejectedTheories.takeLast(8).forEach { appendLine("- $it") }
            }
            if (notebook.questionsAlreadyAsked.isNotEmpty()) {
                appendLine("Questions already asked (do not repeat if answered):")
                notebook.questionsAlreadyAsked.takeLast(8).forEach { appendLine("- $it") }
            }
            if (rows.isNotEmpty()) {
                appendLine("Recent accepted conversation:")
                rows.forEach { row ->
                    appendLine("User: ${row.user.take(220)}")
                    appendLine("ArogyaDoot: ${row.assistant.take(300)}")
                }
            }
        }.take(maxChars)
    }

    private fun publishLocked() {
        _state.value = buildSnapshotLocked()
    }

    private fun buildSnapshotLocked(): InvestigationNotebook {
        val rows = readRowsLocked()
        val verifiedVisuals = readVerifiedVisualsLocked()
        val rejectedTheories = readRejectedTheoriesLocked()
        val typedEntries = readTypedEntriesLocked()

        if (rows.isEmpty() && verifiedVisuals.isEmpty() && typedEntries.isEmpty() &&
            readTheoryLocked().isBlank() && readTroubleshootingRuledOutLocked().isEmpty()) return InvestigationNotebook()

        val problem = rows.firstOrNull { it.user.isNotBlank() }?.user?.take(240).orEmpty()
        val currentGoal = readCurrentGoalLocked().ifBlank { problem }

        /*
         * Evidence-conservative structure:
         * - user statements ending in a question mark stay transcript/questions, not facts;
         * - assistant prose is never promoted to confirmed facts;
         * - no semantic symptom/disease/action keyword lists are used.
         */
        val confirmedFacts = typedEntries
            .filter { it.type == NotebookEvidenceType.CONFIRMED_FACT }
            .map { it.content }
            .distinctBy(::normalKey)
            .let { all -> if (all.size <= 32) all else all.take(12) + all.takeLast(20) }
            .distinctBy(::normalKey)
            .toMutableList()

        val userStatements = (typedEntries
            .filter { it.type == NotebookEvidenceType.USER_STATEMENT }
            .map { it.content } +
            rows.flatMap { splitSentences(it.user) }
                .map { it.trim() }
                .filter { it.length > 2 && !it.trimEnd().endsWith("?") })
            .distinctBy(::normalKey)
            .takeLast(24)
            .toMutableList()

        val questionsAlreadyAsked = rows
            .filter { !it.provider.equals(AndroBaselineRuntime.Provider.NONE.name, ignoreCase = true) }
            .flatMap { splitSentences(it.assistant) }
            .filter { it.trimEnd().endsWith("?") }
            .map { it.take(240) }
            .distinctBy(::normalKey)
            .takeLast(20)
            .toMutableList()

        val userResponses = buildList {
            rows.forEachIndexed { index, row ->
                val user = row.user.trim()
                if (user.isBlank() || index == 0) return@forEachIndexed
                val priorAssistant = rows[index - 1].assistant.trim()
                val priorQuestion = splitSentences(priorAssistant)
                    .lastOrNull { it.trimEnd().endsWith("?") }
                if (priorQuestion != null) {
                    add("Q: ${priorQuestion.take(220)} | A: ${user.take(180)}")
                } else {
                    add(user.take(220))
                }
            }
        }.distinctBy(::normalKey)
            .takeLast(20)
            .toMutableList()

        val visualFindingTexts: List<String> = verifiedVisuals.map { stored ->
            if (stored.timestampMs > 0L) {
                val ageSeconds = ((System.currentTimeMillis() - stored.timestampMs).coerceAtLeast(0L) / 1_000L)
                "${stored.source} (${ageSeconds}s ago): ${stored.text}"
            } else {
                "${stored.source}: ${stored.text}"
            }
        } +
            typedEntries.filter { it.type == NotebookEvidenceType.OBSERVATION }
                .map { "${it.source}: ${it.content}" }

        val visualFindings: MutableList<String> = visualFindingTexts
            .distinctBy { normalKey(it) }
            .takeLast(32)
            .toMutableList()

        val checksPerformed = typedEntries
            .filter { it.type == NotebookEvidenceType.ACTION_RESULT || it.type == NotebookEvidenceType.ACTION }
            .map { it.content }
            .distinctBy(::normalKey)
            .takeLast(24)
            .toMutableList()

        val visualConflicts = typedEntries
            .filter { it.type == NotebookEvidenceType.VISUAL_CONFLICT }
            .map { it.content }
            .distinctBy(::normalKey)
            .takeLast(12)
            .toMutableList()

        val actionsSuggested = rows
            .filter { !it.provider.equals(AndroBaselineRuntime.Provider.NONE.name, ignoreCase = true) }
            .flatMap { splitSentences(it.assistant) }
            .map { it.trim() }
            .filter { it.length in 8..300 && !it.endsWith("?") }
            .distinctBy(::normalKey)
            .takeLast(16)
            .toMutableList()

        val lastProvider = rows.lastOrNull()?.provider.orEmpty()
        val lastAnswerSource = if (lastProvider.equals("CLOUD", ignoreCase = true)) {
            ObservationSource.CLOUD
        } else {
            ObservationSource.LOCAL_MODEL
        }

        return InvestigationNotebook(
            problem = problem,
            currentGoal = currentGoal,
            primaryTheory = readTheoryLocked(),
            secondaryTheory = "",
            confirmedFacts = confirmedFacts,
            visualFindings = visualFindings,
            userStatements = userStatements,
            visualConflicts = visualConflicts,
            checksPerformed = checksPerformed,
            actionsSuggested = actionsSuggested,
            userResponses = userResponses,
            rejectedTheories = (rejectedTheories + readTroubleshootingRuledOutLocked()).distinctBy(::normalKey).toMutableList(),
            questionsAlreadyAsked = questionsAlreadyAsked,
            conversationSummary = rows.takeLast(8).joinToString("\n") {
                "User: ${it.user.take(260)}\nArogyaDoot: ${it.assistant.take(360)}"
            }.take(3_800),
            typedEntries = typedEntries.toMutableList(),
            lastAnswerSource = lastAnswerSource,
            lastAnswerOffline = lastProvider.equals("LOCAL", ignoreCase = true)
        )
    }

    private fun readRowsLocked(): List<Row> {
        val encoded = prefs.getString(key(KEY_ROWS), "").orEmpty()
        if (encoded.isBlank()) return emptyList()
        return encoded.lineSequence().mapNotNull { line ->
            val p = line.split('|', limit = 3)
            if (p.size != 3) null else try {
                Row(decode(p[0]), decode(p[1]), p[2])
            } catch (_: Throwable) {
                null
            }
        }.toList()
    }

    private fun readCurrentGoalLocked(): String {
        val encoded = prefs.getString(key(KEY_CURRENT_GOAL), "").orEmpty()
        if (encoded.isBlank()) return ""
        return runCatching { decode(encoded) }.getOrDefault("")
    }

    private fun readTheoryLocked(): String {
        val encoded = prefs.getString(key(KEY_THEORY), "").orEmpty()
        if (encoded.isBlank()) return ""
        return runCatching { decode(encoded) }.getOrDefault("")
    }

    private fun readTroubleshootingRuledOutLocked(): List<String> =
        prefs.getString(key(KEY_TS_RULED_OUT), "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .mapNotNull { runCatching { decode(it) }.getOrNull() }
            .toList()

    private fun readRejectedTheoriesLocked(): List<String> =
        prefs.getString(key(KEY_REJECTED_THEORIES), "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .mapNotNull { runCatching { decode(it) }.getOrNull() }
            .toList()

    private fun readVerifiedVisualsLocked(): List<StoredVisualObservation> =
        prefs.getString(key(KEY_VERIFIED_VISUALS), "").orEmpty()
            .lineSequence()
            .mapNotNull(::decodeVisualLine)
            .toList()

    private fun readTypedEntriesLocked(): List<NotebookEntry> =
        prefs.getString(key(KEY_TYPED_ENTRIES), "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split('|', limit = 7)
                if (p.size != 7) return@mapNotNull null
                runCatching {
                    val parsed = NotebookEntry(
                        content = decode(p[6]),
                        source = ObservationSource.valueOf(p[0]),
                        confidence = p[1].toFloat(),
                        timestamp = p[2].toLong(),
                        type = NotebookEvidenceType.valueOf(p[3]),
                        provenanceId = decode(p[4]) + if (p[5].isNotBlank()) ":${decode(p[5])}" else ""
                    )
                    // Migrate entries written by the previous implementation:
                    // USER + CONFIRMED_FACT represented a report, not verified
                    // evidence. Keeping the content while changing its type avoids
                    // silently losing history and prevents it from overriding a frame.
                    if (parsed.source == ObservationSource.USER &&
                        parsed.type == NotebookEvidenceType.CONFIRMED_FACT
                    ) {
                        parsed.copy(
                            type = NotebookEvidenceType.USER_STATEMENT,
                            confidence = parsed.confidence.coerceAtMost(0.70f)
                        )
                    } else parsed
                }.getOrNull()
            }.toList()

    private fun encodeTypedEntries(entries: List<NotebookEntry>): String = entries.joinToString("\n") { e ->
        listOf(
            e.source.name,
            e.confidence.toString(),
            e.timestamp.toString(),
            e.type.name,
            encode(e.provenanceId.substringBefore(':')),
            encode(e.provenanceId.substringAfter(':', "")),
            encode(e.content)
        ).joinToString("|")
    }

    private fun decodeVisualLine(line: String): StoredVisualObservation? {
        val parts = line.split('|', limit = 3)
        return try {
            if (parts.size == 3) {
                StoredVisualObservation(
                    timestampMs = parts[0].toLongOrNull() ?: 0L,
                    source = parts[1].ifBlank { "CLOUD_VISION" },
                    text = decode(parts[2])
                )
            } else if (parts.size == 2) {
                // Backward-compatible read of KEY_VERIFIED_VISUALS entries written
                // before timestamp/provenance was added.
                StoredVisualObservation(
                    timestampMs = 0L,
                    source = parts[0].ifBlank { "CLOUD_VISION" },
                    text = decode(parts[1])
                )
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun splitSentences(text: String): List<String> =
        Regex("[^.!?।！？]+(?:[.!?।！？]+|$)")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun normalKey(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim().take(120)

    private fun encodeRows(rows: List<Row>): String = rows.joinToString("\n") {
        "${encode(it.user)}|${encode(it.assistant)}|${it.provider}"
    }

    private fun encode(text: String): String =
        Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )

    private fun decode(text: String): String =
        String(
            Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE),
            Charsets.UTF_8
        )
}
