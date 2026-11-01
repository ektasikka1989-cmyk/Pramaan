package com.example.arfas

import android.util.Log
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Provider state exposed to Settings and the baseline router. */
enum class GroqProviderStatus { READY, NO_KEY, AUTH_FAILED, RATE_LIMITED, NETWORK_ERROR, UNKNOWN }

/**
 * Minimal OpenAI-compatible Groq chat adapter for the AndroLLM baseline.
 *
 * It owns no FixSight policy, Notebook state, domain logic, visual logic or routing.
 * Accepted conversation is supplied by AndroBaselineRuntime and plain assistant text
 * is returned.  Network cancellation is cooperative so barge-in frees the one runtime
 * lane immediately.
 */
class GroqChatProvider(apiKey: String) {
    companion object {
        private const val TAG = "MetaLlamaProvider"
        
        // Official Meta Llama API Models (https://api.llama.com/compat/v1/chat/completions)
        private const val PRIMARY_TEXT_MODEL = "Llama-4-Scout-17B-16E-Instruct-FP8"      // Meta Llama API, free 10 RPM 250K TPM
        private const val PRIMARY_VISION_MODEL = "Llama-4-Scout-17B-16E-Instruct-FP8"    // 5 images, ~1026 tok/image
        private const val FALLBACK_HQ_MODEL = "Llama-4-Maverick-17B-128E-Instruct-FP8"    // Meta Llama API Maverick fallback
        private const val FALLBACK_VISION_MODEL = "Llama-3.2-11B-Vision-Instruct"       // Meta Llama API Vision fallback
        
        /** Plain-chat reply meaning "this needs a live web search" (new or changed facts). */
        const val NEEDS_WEB = "[[NEEDS_WEB]]"
        private const val COMPOUND_MODEL = "groq/compound-mini"
        private const val FULL_COMPOUND_MODEL = "groq/compound"
        private const val COMPOUND_FALLBACK_MODEL = "groq/compound"
        private const val REQUIRED_BROWSER_SEARCH_MODEL = "openai/gpt-oss-20b"
        private const val BACKUP_BROWSER_SEARCH_MODEL = "openai/gpt-oss-120b"
        private const val KB_SELECTOR_BATCH_SIZE = 4
        // Meta Model API (keys "LLM|…"): Muse Spark replaces the retired Llama API models.
        private const val ENDPOINT = "https://api.meta.ai/v1/chat/completions"
        private const val META_RESPONSES_ENDPOINT = "https://api.meta.ai/v1/responses"
        private const val META_SPARK = "muse-spark-1.3"
        private const val META_SPARK_FALLBACK = "muse-spark-1.2"
        /** Groq engine (Settings → Online AI): Qwen3.8 27B for chat, knowledge answers and photos. */
        private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val GROQ_QWEN_MODEL = "qwen/qwen3.8-27b"
        /** Qwen3.8 27B on Groq reads at most 3 images per request. */
        private const val GROQ_MAX_IMAGES = 3
        private val GROQ_MODEL_NAMES = mapOf(
            PRIMARY_TEXT_MODEL to GROQ_QWEN_MODEL,
            FALLBACK_HQ_MODEL to GROQ_QWEN_MODEL,
            FALLBACK_VISION_MODEL to GROQ_QWEN_MODEL
        )
        private val GROQ_ONLY_MODELS = setOf(COMPOUND_MODEL, FULL_COMPOUND_MODEL, COMPOUND_FALLBACK_MODEL, REQUIRED_BROWSER_SEARCH_MODEL, BACKUP_BROWSER_SEARCH_MODEL)
        private const val HISTORY_CHAR_BUDGET = 6_000
        private const val EXTERNAL_HISTORY_CHAR_BUDGET = 3_500
        private const val EXTERNAL_HISTORY_MESSAGE_CHAR_LIMIT = 1_000
        private const val EXTERNAL_QUERY_CHAR_LIMIT = 4_000
        private const val EXTERNAL_MINIMAL_QUERY_CHAR_LIMIT = 3_000
        private const val EXTERNAL_GROUNDED_CONTEXT_CHAR_LIMIT = 2_500
        // External retrieval uses its own shorter per-attempt read budgets so an
        // early Compound miss cannot consume the whole fresh-information turn and
        // starve the mandatory browser-search fallback.
        // A web search plus answer often takes 10-20 s on Groq; 8 s cut off most real searches
        // ("latest moon missions of India" failed while a quick weather lookup worked).
        private const val EXTERNAL_COMPOUND_READ_TIMEOUT_MS = 22_000L
        private const val EXTERNAL_FULL_COMPOUND_READ_TIMEOUT_MS = 25_000L
        private const val EXTERNAL_BROWSER_READ_TIMEOUT_MS = 15_000L
        private const val EXTERNAL_BROWSER_BACKUP_READ_TIMEOUT_MS = 15_000L
        // When the first web search has not answered after this long, a second search (another
        // model) starts in parallel and the first good answer wins: a slow search no longer
        // makes the user wait for its timeout before the backup even begins.
        private const val EXTERNAL_HEDGE_AFTER_MS = 7_000L
    }

    enum class VisionDetail(val maxLongSide: Int, val jpegQuality: Int) {
        STANDARD(maxLongSide = 1080, jpegQuality = 86),
        HIGH(maxLongSide = 1440, jpegQuality = 92)
    }

    data class FactualVerification(
        val localAccepted: Boolean,
        val correctedAnswer: String = ""
    )

    private val factualOkSentinel = "[[FACTS_OK]]"

    private data class PreparedVisionImage(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val detail: VisionDetail
    )

    private fun prepareVisionImage(bitmap: Bitmap, detail: VisionDetail): PreparedVisionImage {
        val longSide = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val ratio = minOf(1f, detail.maxLongSide.toFloat() / longSide.toFloat())
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = if (targetWidth != bitmap.width || targetHeight != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else bitmap

        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, detail.jpegQuality, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        return PreparedVisionImage(jpeg, targetWidth, targetHeight, detail)
    }

    private val apiKey = apiKey.trim()
    /**
     * Meta Model API (key "LLM|…": Muse Spark 1.3, the current Meta model) or Groq (key "gsk_…":
     * Qwen3.8 27B). A Groq key sent to Meta's endpoint is what produced the old 401 AUTH_FAILED.
     */
    private val isGroqKey = this.apiKey.startsWith("gsk_")
    /** The model answering online, for the Settings screen. */
    val engineLabel: String get() = when { apiKey.isBlank() -> "none"; isGroqKey -> "Groq · Qwen3.8 27B"; else -> "Meta · Muse Spark" }
    private val endpoint = if (isGroqKey) GROQ_ENDPOINT else ENDPOINT
    /** Live web search: Groq's search models, or Muse Spark's web_search tool on the Meta Model API. */
    val webSearchAvailable: Boolean get() = apiKey.isNotBlank()
    private val client = NetworkClient.okHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val activeCall = AtomicReference<Call?>(null)

    @Volatile private var authFailed = apiKey.isBlank()
    @Volatile private var providerStatus =
        if (apiKey.isBlank()) GroqProviderStatus.NO_KEY else GroqProviderStatus.UNKNOWN
    @Volatile private var lastRequestLatencyMs: Long = 0L
    @Volatile private var lastSuccessAtMs: Long = 0L
    @Volatile private var lastErrorDetail: String = ""
    @Volatile private var rateLimitedUntilMs: Long = 0L
    private val externalRateLimitedUntilMs = ConcurrentHashMap<String, Long>()

    fun hasApiKey(): Boolean = apiKey.isNotBlank()
    /**
     * Rate limits are transient, but repeatedly hitting a known 429 only adds latency.
     * Keep the provider eligible again automatically after the server-indicated
     * cooldown; auth failures remain the only persistent disable.
     */
    fun canAttemptRequest(): Boolean =
        apiKey.isNotBlank() && !authFailed && System.currentTimeMillis() >= rateLimitedUntilMs

    /**
     * Live information uses browser-capable models with model-specific quotas. A
     * 429 on one browser model must not poison the normal chat model or the other
     * browser model. External routing remains eligible while at least one supported
     * browser model is outside its provider-indicated cooldown.
     */
    fun canAttemptExternalRequest(): Boolean {
        if (apiKey.isBlank() || authFailed || !webSearchAvailable) return false
        val now = System.currentTimeMillis()
        return listOf(REQUIRED_BROWSER_SEARCH_MODEL, BACKUP_BROWSER_SEARCH_MODEL)
            .any { now >= (externalRateLimitedUntilMs[it] ?: 0L) }
    }
    fun status(): GroqProviderStatus = providerStatus
    fun lastLatencyMs(): Long = lastRequestLatencyMs
    fun lastSuccessMs(): Long = lastSuccessAtMs
    fun lastError(): String = lastErrorDetail

    fun cancelActiveCall() {
        activeCall.getAndSet(null)?.cancel()
    }

    /**
     * Technical fallback for the selected-KB applicability gate. This is NOT an
     * independent medical-answer path: the cloud may select only from the supplied
     * source-derived candidate records and may not add clinical knowledge.
     */
    suspend fun selectGroundedKbRecords(
        notebookContext: String,
        currentUser: String,
        candidates: List<ANMDatabaseHelper.KbRecordCandidate>,
        activeRecordIds: List<Int>
    ): KbRecordSelection? {
        if (!canAttemptRequest() || candidates.isEmpty()) return null

        /*
         * V3.7 evidence-bound global adjudication.
         *
         * Qwen sees only established case text plus already-retrieved source records.
         * Structured output is schema-constrained and every admitted selection must
         * carry verifiable case/source evidence. No domain or record-id rules live here.
         */
        // V5.2 cloud selector uses the same bounded coverage principle as Local.
        // Keep semantic + source-term recall visibility without flooding the adjudicator
        // with every retrieved distractor.
        val uniqueCandidates = candidates.distinctBy { it.recordId }
        val semanticCandidates = uniqueCandidates.sortedWith(
            compareByDescending<ANMDatabaseHelper.KbRecordCandidate> { it.rankingScore }
                .thenByDescending { it.currentSemanticScore }
                .thenByDescending { it.contextSemanticScore }
        )
        val lexicalCandidates = uniqueCandidates
            .filter { it.lexicalSupport > 0f }
            .sortedWith(
                compareByDescending<ANMDatabaseHelper.KbRecordCandidate> { it.lexicalSupport }
                    .thenByDescending { it.rankingScore }
            )
        fun tokenSet(raw: String): Set<String> =
            raw.lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .asSequence()
                .map { it.trim() }
                .filter { it.length >= 4 }
                .toSet()
        val queryTokens = tokenSet(currentUser)
        val candidateTokens = uniqueCandidates.associate { candidate ->
            candidate.recordId to tokenSet(candidate.conditionName + " " + candidate.applicabilityText)
        }
        val tokenDf = queryTokens.associateWith { token ->
            candidateTokens.values.count { token in it }.coerceAtLeast(1)
        }
        fun discriminativeScore(candidate: ANMDatabaseHelper.KbRecordCandidate): Double {
            val sourceTokens = candidateTokens[candidate.recordId].orEmpty()
            return queryTokens.sumOf { token ->
                if (token !in sourceTokens) 0.0
                else kotlin.math.ln((uniqueCandidates.size + 1.0) / (tokenDf[token]!! + 1.0))
            }
        }
        val discriminativeCandidates = uniqueCandidates.sortedByDescending(::discriminativeScore)

        val candidateSet = ArrayList<ANMDatabaseHelper.KbRecordCandidate>(8)
        fun addCandidate(c: ANMDatabaseHelper.KbRecordCandidate) {
            if (candidateSet.size < 8 && candidateSet.none { it.recordId == c.recordId }) candidateSet += c
        }
        uniqueCandidates.filter { it.recordId in activeRecordIds || it.activeAnchor }.forEach(::addCandidate)
        discriminativeCandidates.take(3).forEach(::addCandidate)
        semanticCandidates.take(3).forEach(::addCandidate)
        lexicalCandidates.take(3).forEach(::addCandidate)
        semanticCandidates.forEach(::addCandidate)

        Log.i(
            TAG,
            "KB_CLOUD_SELECTOR_COVERAGE rev=V5_3_ID_ONLY_AUTHORITY ids=" +
                candidateSet.joinToString(",") { it.recordId.toString() }
        )
        val candidateById = candidateSet.associateBy { it.recordId }
        val allowedIds = candidateById.keys
        val activeSet = activeRecordIds.filter { it > 0 }.toSet()

        fun compact(raw: String, maxChars: Int): String =
            FixSightOutputSanitizer.sanitize(raw)
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(maxChars)

        fun evidenceKey(raw: String): String =
            compact(raw, 260)
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        val caseHaystack = evidenceKey(notebookContext + "\n" + currentUser)

        fun validateCaseEvidence(rawValue: String): String? {
            val raw = compact(rawValue, 180)
            val normalized = evidenceKey(raw)
            return raw.takeIf {
                normalized.length >= 4 &&
                    caseHaystack.isNotBlank() &&
                    caseHaystack.contains(normalized)
            }
        }

        fun validateSourceEvidence(recordId: Int, rawValue: String): String? {
            val candidate = candidateById[recordId] ?: return null
            val raw = compact(rawValue, 200)
            val normalized = evidenceKey(raw)
            if (normalized.length < 4) return null
            val sourceHaystack = evidenceKey(
                candidate.conditionName + "\n" + candidate.applicabilityText
            )
            return raw.takeIf { sourceHaystack.contains(normalized) }
        }

        fun needsInfo(
            questionRaw: String,
            anchorRecordId: Int? = null,
            caseEvidence: String? = null,
            sourceEvidence: String? = null
        ): KbRecordSelection {
            val question = compact(questionRaw, 180)
                .let { if (it.isNotBlank() && !it.endsWith("?")) "$it?" else it }
            val anchor = anchorRecordId?.takeIf { it in allowedIds }
            val anchored = anchor != null && !caseEvidence.isNullOrBlank() && !sourceEvidence.isNullOrBlank()
            return KbRecordSelection(
                selectedRecordIds = if (anchored) listOf(anchor!!) else emptyList(),
                // Exact case/source evidence for a specific supplied record means
                // high confidence in the provisional SOP match. The missing branch
                // fact is still unknown, so the provider keeps this PROVISIONAL.
                confidence = if (anchored) "HIGH" else "LOW",
                clarificationQuestion = question,
                decisions = candidateSet.map { candidate ->
                    when {
                        anchored && candidate.recordId == anchor -> KbRecordDecision(
                            recordId = candidate.recordId,
                            verdict = "NEEDS_INFO",
                            rationale = "Presenting case matches this SOP for assessment; one branch-specific fact is still missing. | SOURCE: ${sourceEvidence!!.take(90)}",
                            missingFact = question.removeSuffix("?").take(140)
                        )
                        anchored -> KbRecordDecision(
                            recordId = candidate.recordId,
                            verdict = "CONFLICT",
                            rationale = "Not selected as the provisional assessment SOP.",
                            missingFact = ""
                        )
                        else -> KbRecordDecision(
                            recordId = candidate.recordId,
                            verdict = "NEEDS_INFO",
                            rationale = "One applicability fact is still missing.",
                            missingFact = question.removeSuffix("?").take(140)
                        )
                    }
                },
                caseFactEvidence = if (anchored) listOf(caseEvidence!!) else emptyList()
            )
        }

        fun noMatch(reason: String = ""): KbRecordSelection = KbRecordSelection(
            selectedRecordIds = emptyList(),
            confidence = "LOW",
            clarificationQuestion = "",
            decisions = candidateSet.map {
                KbRecordDecision(
                    recordId = it.recordId,
                    verdict = "CONFLICT",
                    rationale = compact(reason, 160).ifBlank {
                        "Current established facts do not safely establish applicability."
                    },
                    missingFact = ""
                )
            },
            caseFactEvidence = emptyList()
        )

        var lastParseReject = "none"

        fun parseSelection(cleanRaw: String): KbRecordSelection? {
            val clean = cleanRaw
                .replace("```json", "", ignoreCase = true)
                .replace("```", "")
                .trim()
            fun reject(reason: String): KbRecordSelection? {
                lastParseReject = reason
                return null
            }
            if (clean.isBlank()) return reject("blank")

            val jsonText = if (clean.startsWith('{') && clean.endsWith('}')) clean else {
                val first = clean.indexOf('{')
                val last = clean.lastIndexOf('}')
                if (first >= 0 && last > first) clean.substring(first, last + 1) else ""
            }
            if (jsonText.isBlank()) return reject("no_json_object")
            val obj = try { JSONObject(jsonText) } catch (_: Throwable) {
                return reject("malformed_json")
            }

            val action = obj.optString("action", "").trim().uppercase()
            val id = when {
                obj.has("selected_id") -> obj.optInt("selected_id", -1)
                obj.has("record_id") -> obj.optInt("record_id", -1)
                (obj.optJSONArray("selected_ids")?.length() ?: 0) > 0 ->
                    obj.optJSONArray("selected_ids")?.optInt(0, -1) ?: -1
                else -> -1
            }
            val clarification = compact(
                obj.optString("question", obj.optString("clarification_question", "")), 180
            )
            val reason = compact(obj.optString("reason", ""), 180)

            if (action in setOf("ASK", "NEEDS_INFO")) {
                if (clarification.isBlank()) return reject("invalid_ask_shape")
                if (id in allowedIds) {
                    lastParseReject = "none"
                    return needsInfo(
                        questionRaw = clarification,
                        anchorRecordId = id,
                        caseEvidence = compact(currentUser, 160),
                        sourceEvidence = "authoritative_record:$id"
                    )
                }
                lastParseReject = "none"
                return needsInfo(clarification)
            }
            if (action in setOf("NONE", "NO_MATCH")) {
                lastParseReject = "none"
                return noMatch(reason)
            }
            if (id !in allowedIds) return reject("invalid_selected_id")

            val transitionValid = when (action) {
                "SELECT" -> activeSet.isEmpty()
                "KEEP" -> activeSet.isNotEmpty() && id in activeSet
                "SWITCH" -> activeSet.isNotEmpty() && id !in activeSet
                else -> false
            }
            if (!transitionValid) return reject("invalid_transition_$action")

            lastParseReject = "none"
            return KbRecordSelection(
                selectedRecordIds = listOf(id),
                confidence = "HIGH",
                clarificationQuestion = "",
                decisions = candidateSet.map { candidate ->
                    if (candidate.recordId == id) {
                        KbRecordDecision(
                            recordId = candidate.recordId,
                            verdict = "APPLICABLE",
                            rationale = reason.ifBlank {
                                "Selected from the dynamically retrieved authoritative source records."
                            }.take(180),
                            missingFact = ""
                        )
                    } else {
                        KbRecordDecision(
                            recordId = candidate.recordId,
                            verdict = "CONFLICT",
                            rationale = "Not selected by the applicability adjudicator.",
                            missingFact = ""
                        )
                    }
                },
                caseFactEvidence = listOf(compact(currentUser, 180)).filter { it.isNotBlank() }
            )
        }

        fun jsonSchema(): JSONObject {
            fun allowedIdArray(): JSONArray = JSONArray().apply { allowedIds.forEach { put(it) } }
            return JSONObject().apply {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("SELECT"); put("KEEP"); put("SWITCH"); put("ASK"); put("NONE")
                        })
                    })
                    put("selected_id", JSONObject().apply {
                        put("type", "integer")
                        put("enum", JSONArray().apply { put(-1); allowedIds.forEach { put(it) } })
                    })
                    put("question", JSONObject().apply { put("type", "string") })
                    put("reason", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().apply {
                    put("action"); put("selected_id"); put("question"); put("reason")
                })
            }
        }

        fun buildRequest(maxCaseChars: Int, maxRecordChars: Int): JSONObject {
            val candidateText = buildString {
                candidateSet.forEach { c ->
                    append("R|").append(c.recordId)
                        .append("|ACTIVE=").append(
                            if (c.recordId in activeSet || c.activeAnchor) "1" else "0"
                        )
                        .append('|').append(c.conditionName.replace('|', '/').take(110))
                        .append('\n')
                    val canonicalSource = c.applicabilityText
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(maxRecordChars)
                    append("SOURCE=").append(canonicalSource.replace('|', '/')).append('\n')
                    append("END\n")
                }
            }

            val request = """
ACTIVE IDS: ${activeRecordIds.joinToString(",")}
CASE FACTS:
${notebookContext.trim().take(maxCaseChars)}
CURRENT TURN:
${currentUser.trim().take(420)}
CANDIDATES:
$candidateText
""".trimIndent()

            return JSONObject().apply {
                put("model", PRIMARY_TEXT_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "You are ArogyaDoot's source-bounded SOP applicability adjudicator. " +
                                "Do NOT answer the health question and do NOT use outside medical knowledge. " +
                                "Reason from the established case and the supplied source records only. " +
                                "Preserve referents: who or what each fact describes and which attribute a value belongs to. " +
                                "Do not equate facts merely because they share a number, unit, symptom or word. " +
                                "Applicability means the record fits the established PRESENTING CASE well enough to guide the NEXT SOP ASSESSMENT; do not require downstream tests or measurements to already be completed. " +
                                "A fact, test or measurement named in supplied ASSESS guidance is normally something the SOP tells the worker to obtain, not an entry prerequisite merely because its result is unknown. " +
                                "ACTIVE records are continuity evidence, not authority. Use KEEP only if the active record still fits. " +
                                "Use SWITCH only when established case evidence supports another supplied record better. " +
                                "For SELECT, KEEP or SWITCH, return exactly one selected_id from the supplied candidate ids. " +
                                "Kotlin will load the authoritative SOP text from SQLite after selection; do not quote source text. " +
                                "Match SUBJECT/LIFE-STAGE before symptom overlap. If one supplied SOP already matches the established subject, life-stage and presenting problem well enough to guide assessment but one branch-specific fact is still needed, use ASK with that SOP id in selected_id and put the missing fact question in question. " +
                                "Use ASK with selected_id=-1 only when no single supplied SOP can yet guide the next assessment. " +
                                "Do not ask about an unrelated life-stage, procedure or population merely to eliminate a distractor when another supplied record already matches the established subject, life-stage and presenting problem. " +
                                "If no supplied record safely applies, use NONE with selected_id=-1. Never invent case facts or source text."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", request)
                    })
                })
                put(
                    "response_format",
                    JSONObject().apply {
                        put("type", "json_schema")
                        put(
                            "json_schema",
                            JSONObject().apply {
                                put("name", "fixsight_kb_applicability")
                                put("schema", jsonSchema())
                            }
                        )
                    }
                )
                put("max_tokens", 120)
                put("temperature", 0.0)
                put("reasoning_effort", "none")
            }
        }

        return try {
            for (attempt in 1..2) {
                val body = if (attempt == 1) {
                    buildRequest(maxCaseChars = 1_200, maxRecordChars = 240)
                } else {
                    buildRequest(maxCaseChars = 800, maxRecordChars = 180)
                }
                val raw = requestWithRetry(
                    body,
                    maxAttempts = if (attempt == 1) 2 else 1
                ).first
                if (raw == null) {
                    Log.w(
                        TAG,
                        "KB_CLOUD_SELECTOR_REQUEST_FAILED rev=V3_7_EVIDENCE_TRANSITION attempt=$attempt status=$providerStatus"
                    )
                    // DNS/socket/network failure is not a parse-quality problem. Do
                    // not spend another selector request retrying an unavailable cloud;
                    // return immediately so the offline local path remains responsive.
                    if (providerStatus == GroqProviderStatus.NETWORK_ERROR) return null
                    continue
                }
                val clean = parseText(raw).trim()
                val parsed = parseSelection(clean)
                Log.i(
                    TAG,
                    "KB_CLOUD_SELECTOR rev=V3_7_EVIDENCE_TRANSITION " +
                        "attempt=$attempt chars=${clean.length} parsed=${parsed != null} " +
                        "selected=${parsed?.selectedRecordIds?.joinToString(",").orEmpty()} " +
                        "confidence=${parsed?.confidence ?: "NONE"} active=${activeSet.joinToString(",")} " +
                        "caseEvidence=${parsed?.caseFactEvidence?.size ?: 0} " +
                        "reject=${if (parsed == null) lastParseReject else "none"}"
                )
                if (parsed != null) return parsed
            }
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KB_CLOUD_SELECTOR_FAILED rev=V3_7_EVIDENCE_TRANSITION " +
                    "${t.javaClass.simpleName}:${t.message}"
            )
            null
        }
    }

    suspend fun validateModel(): Boolean {
        if (apiKey.isBlank()) {
            providerStatus = GroqProviderStatus.NO_KEY
            return false
        }
        val body = JSONObject().apply {
            put("model", PRIMARY_TEXT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with OK")
                })
            })
            put("max_tokens", 2)
            put("reasoning_effort", "none")
        }
        val result = requestWithRetry(body, maxAttempts = 1)
        return result.first != null
    }

    suspend fun chatText(
        messages: List<Pair<String, String>>,
        currentQuery: String
    ): String {
        if (apiKey.isBlank()) {
            providerStatus = GroqProviderStatus.NO_KEY
            return ""
        }
        if (authFailed) return ""

        val boundedHistory = boundHistory(messages)
        val body = JSONObject().apply {
            put("model", PRIMARY_TEXT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are ArogyaDoot, one live conversational AI assistant. " +
                            "Today's date is ${SimpleDateFormat("d MMMM yyyy", Locale.US).format(Date())}. " +
                            "If the request is about something you do not reliably know, or that may be new or have changed after your training " +
                            "(a product, company, person, event, price, rule or figure), reply with exactly $NEEDS_WEB and nothing else: the app then searches the web. " +
                            "Continue naturally from accepted history and answer the user's current request directly. " +
                            "There are no separate troubleshooting, generic, health, device, or search modes. " +
                            "Use supplied internal context silently when relevant and never describe it as a document unless the user asks for sources. " +
                            "Do not invent facts. This is a live spoken assistant: be concise by default, usually 1 to 3 short sentences and normally under 80 words. If the user explicitly asks for steps, a procedure, more detail, examples, a comparison, or a fuller explanation, that request overrides the default brevity: give enough complete detail to satisfy it and do not omit useful steps merely because the answer will be spoken. Ask one concise follow-up only when a missing detail prevents a useful answer. Plain text only."
                    )
                })
                boundedHistory.forEach { (role, content) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", boundCurrentUserContent(currentQuery, 18_000))
                })
            })
            put("max_tokens", 768)
            put("temperature", 0.7)
            put("top_p", 0.8)
            put("reasoning_effort", "none")
        }

        val raw = requestWithRetry(body, maxAttempts = 3).first ?: return ""
        return parseText(raw)
    }

    suspend fun chatSafetyText(
        messages: List<Pair<String, String>>,
        currentQuery: String
    ): String {
        if (apiKey.isBlank()) {
            providerStatus = GroqProviderStatus.NO_KEY
            return ""
        }
        if (authFailed) return ""

        val boundedHistory = boundHistory(messages)
        val body = JSONObject().apply {
            put("model", PRIMARY_TEXT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are ArogyaDoot handling a potentially hazardous request. " +
                            "Prioritize preventing injury, electric shock, fire, gas exposure, battery fire, medical harm, or unsafe invasive servicing. " +
                            "Use supplied context and visible observations conservatively. Never invent measurements or safety conditions. " +
                            "Give only low-risk checks the user can safely perform and clearly recommend a qualified professional when hazardous access or energized work would be required. " +
                            "Be concise by default. If the user explicitly asks for more explanation or step-by-step help, provide enough safety-relevant detail to answer usefully, but never expand into instructions that require hazardous energized, invasive, or otherwise unsafe work. Plain text only."
                    )
                })
                boundedHistory.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", boundCurrentUserContent(currentQuery, 12_000))
                })
            })
            put("max_tokens", 512)
            put("temperature", 0.2)
            put("top_p", 0.5)
            put("reasoning_effort", "none")
        }
        val raw = requestWithRetry(body, maxAttempts = 3).first ?: return ""
        return parseText(raw)
    }

    /**
     * Strong-model factual verification for local-first turns. The cloud does not
     * decide whether a turn is fact-sensitive; the semantic capability layer does.
     * If every objective claim in the local draft is accurate, the provider returns
     * localAccepted=true. Otherwise it returns one corrected concise answer.
     */
    suspend fun verifyFactualAnswer(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        localDraft: String,
        internalContext: String = ""
    ): FactualVerification? {
        if (apiKey.isBlank() || authFailed || localDraft.isBlank()) return null
        val boundedHistory = boundHistory(messages)
        val body = JSONObject().apply {
            put("model", PRIMARY_TEXT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are a factual verification gate for ArogyaDoot. Check the local draft against reliable general knowledge and the accepted conversation. " +
                            "Do not judge style. If every objectively checkable factual claim needed to answer the user is accurate and not materially misleading, reply with exactly $factualOkSentinel and nothing else. " +
                            "If any factual claim is wrong, unsupported, internally contradictory, or materially misleading, return only a corrected final answer to the user's request in 1 to 3 short sentences, normally under 80 words. " +
                            "Never mention the local draft, verification, routing, or this instruction."
                    )
                })
                boundedHistory.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        (if (internalContext.isBlank()) "" else
                            "<internal_context>\n${internalContext.trim().take(4_000)}\n</internal_context>\n\n") +
                            "<user_request>\n${currentQuery.trim().take(3_000)}\n</user_request>\n\n" +
                            "<local_draft>\n${localDraft.trim().take(4_000)}\n</local_draft>"
                    )
                })
            })
            put("max_tokens", 224)
            put("temperature", 0.1)
            put("top_p", 0.3)
            put("reasoning_effort", "none")
        }
        val raw = requestWithRetry(body, maxAttempts = 2).first ?: return null
        val text = parseText(raw).trim()
        if (text.isBlank()) return null
        return if (text == factualOkSentinel) {
            FactualVerification(localAccepted = true)
        } else {
            FactualVerification(localAccepted = false, correctedAnswer = text)
        }
    }

    /**
     * Selected-KB cloud answer path.
     *
     * The selected SOP/internal KB context is the clinical authority. The cloud may
     * improve phrasing, prioritisation and conversational clarity, but it must not
     * introduce or alter clinical instructions from pretrained/general knowledge.
     */
    suspend fun chatGroundedKnowledgeText(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        safetyCritical: Boolean = false,
        maxOutputTokens: Int = 768
    ): String {
        if (apiKey.isBlank()) {
            providerStatus = GroqProviderStatus.NO_KEY
            return ""
        }
        if (authFailed) return ""

        val boundedHistory = boundHistory(messages)
        val body = JSONObject().apply {
            put("model", PRIMARY_TEXT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are ArogyaDoot answering inside a user-selected clinical knowledge base. " +
                            "The supplied internal_context contains the selected SOP evidence and active case context. " +
                            "For clinical guidance, that selected SOP evidence is the sole authority. Accepted conversation may establish patient facts, but it is not a source of new protocol rules. " +
                            "Do NOT use pretrained or general medical knowledge to introduce, change, extend, or override any diagnosis, medicine, dose, route, threshold, contraindication, investigation, referral criterion, procedure, or treatment. " +
                            "You may improve clarity, order supported actions by urgency, connect established patient facts to explicitly supported SOP instructions, and ask ONE concise clarification if a missing fact prevents a supported answer. " +
                            "If the SOP does not support the requested clinical action, say that it is not established by the selected SOP instead of guessing. " +
                            (if (safetyCritical) "This is safety-sensitive: be conservative, do not infer unstated clinical facts, and preserve exact supported doses/thresholds/referral conditions. " else "") +
                            "Never mention internal context, retrieval, verification, or the source document unless the user asks. For routine turns, answer directly and concisely, usually 1 to 4 short spoken sentences and normally under 110 words. If the user explicitly asks for a detailed or step-by-step answer, include the relevant detail and supported steps needed to answer properly; do not omit supported information merely to satisfy the default length. Remain strictly selected-SOP-bound and safety-focused. Plain text only."
                    )
                })
                boundedHistory.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", currentQuery.trim().take(8_000))
                })
            })
            put("max_tokens", maxOutputTokens.coerceIn(384, 1_280))
            put("temperature", 0.15)
            put("top_p", 0.35)
            put("reasoning_effort", "none")
        }
        val raw = requestWithRetry(body, maxAttempts = 3).first ?: return ""
        return parseText(raw)
    }


    /**
     * Grounded answer for a user-selected generic/document knowledge pack.
     * Unlike the clinical SOP method, this prompt is domain-neutral and allows
     * transparent citation of the real document/page supplied in the evidence.
     */
    suspend fun chatGenericKnowledgeText(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        safetyCritical: Boolean = false,
        maxOutputTokens: Int = 896,
        /** Receives the draft while it arrives (on-screen preview); null = wait for the full reply. */
        onDelta: ((String) -> Unit)? = null
    ): String {
        if (apiKey.isBlank()) {
            providerStatus = GroqProviderStatus.NO_KEY
            return ""
        }
        if (authFailed) return ""

        val boundedHistory = boundHistory(messages)
        val body = JSONObject().apply {
            put("model", PRIMARY_TEXT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are ArogyaDoot answering from a user-selected knowledge pack. " +
                            "The supplied internal_context is the factual authority for claims about that source. " +
                            "Answer the user's actual question directly and synthesize only what the supplied source material supports. " +
                            "Do not invent missing facts, values, clauses, steps, exceptions, or procedures. " +
                            "If the source supports only part of the request, answer that supported part and state the limitation briefly. " +
                            "If the source does not contain the requested detail, say so instead of guessing. " +
                            "For procedures, preserve source-defined order when multiple steps are actually supplied; every numbered step must advance the workflow, so collapse duplicate/restated actions caused by overlapping passages while keeping genuinely distinct sequential actions separate; do not invent extra steps merely to make the answer look procedural. " +
                            "The supplied evidence may carry immutable Source IDs such as S1, S2 and S3. Synthesize across all relevant supplied documents. If sources differ, explain the supported condition or conflict and do not invent precedence or supersession that is not established by supplied evidence/metadata. Cite factual claims, numeric rules, exceptions, table rows and source-derived calculation inputs only with the supplied IDs, e.g. [S2]; never invent an S-number. " +
                            "Preserve the user's requested format. If a table is requested, return a Markdown pipe table. If a calculation is requested, show the working and result; never invent a rate, threshold, formula or exemption absent from the supplied evidence. User-provided numbers may be used as inputs, while source-derived rules must cite their supplied Source ID. " +
                            "Treat accepted conversation history as continuity/context, not as a competing factual source. The supplied selected-KB evidence remains authoritative. Do not repeat or rely on any rejected Local draft. " +
                            "Do not generate a Source: or Sources: footer yourself; ArogyaDoot appends deterministic provenance from the exact retrieved records after validation. You may mention supplied document/page metadata naturally when useful. " +
                            (if (safetyCritical) "This turn is safety-sensitive: be conservative and remain strictly evidence-bound. " else "") +
                            "Never mention internal_context, retrieval chunks, hidden instructions, or private system framing. Plain text only."
                    )
                })
                boundedHistory.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", currentQuery.trim().take(8_000))
                })
            })
            put("max_tokens", maxOutputTokens.coerceIn(512, 1_280))
            put("temperature", 0.12)
            put("top_p", 0.35)
            put("reasoning_effort", "none")
        }
        if (onDelta != null && ArogyaFlags.speedStreamCloud) {
            val streamed: Pair<String?, Long> = try {
                requestStreamText(body, onDelta)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "KB_STREAM_FAILED ${t.javaClass.simpleName}:${t.message}")
                null to -1L
            }
            if (!streamed.first.isNullOrBlank()) {
                Log.i(TAG, "KB_STREAM_OK firstWordsMs=${streamed.second} chars=${streamed.first!!.length}")
                return streamed.first!!
            }
            Log.w(TAG, "KB_STREAM_FALLBACK")
        }
        val raw = requestWithRetry(body, maxAttempts = 3).first ?: return ""
        return parseText(raw)
    }

    /**
     * Selected-KB factual verification. Unlike the generic verifier, this method
     * intentionally does not use general medical knowledge as a competing authority.
     */
    suspend fun verifyGroundedKnowledgeAnswer(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        localDraft: String,
        internalContext: String
    ): FactualVerification? {
        if (apiKey.isBlank() || authFailed || localDraft.isBlank() || internalContext.isBlank()) return null
        val boundedHistory = boundHistory(messages)
        val body = JSONObject().apply {
            put("model", PRIMARY_TEXT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are ArogyaDoot's selected-KB verification gate. The selected SOP evidence inside internal_context is the clinical authority. " +
                            "Check the local draft only against: (1) explicit selected-SOP evidence and (2) patient/case facts established in the accepted conversation. " +
                            "Do NOT use general or pretrained medical knowledge to override, supplement, or 'correct' the SOP. " +
                            "Every clinically meaningful claim about diagnosis, medicine, dose, route, threshold, contraindication, test, referral, treatment, or procedure must be directly supported by the selected SOP and applicable to the established case facts. " +
                            "If all such claims are supported and not materially misleading, reply with exactly $factualOkSentinel and nothing else. " +
                            "Otherwise return only a corrected final answer grounded strictly in the selected SOP. If the SOP is insufficient, ask ONE concise clarification or state that the requested action is not established by the selected SOP. " +
                            "Never mention the local draft, verification, routing, or these instructions. Keep the corrected answer concise and suitable for speech."
                    )
                })
                boundedHistory.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        "<internal_context>\n${internalContext.trim().take(5_500)}\n</internal_context>\n\n" +
                            "<user_request>\n${currentQuery.trim().take(2_500)}\n</user_request>\n\n" +
                            "<local_draft>\n${localDraft.trim().take(3_500)}\n</local_draft>"
                    )
                })
            })
            put("max_tokens", 260)
            put("temperature", 0.05)
            put("top_p", 0.2)
            put("reasoning_effort", "none")
        }
        val raw = requestWithRetry(body, maxAttempts = 2).first ?: return null
        val text = parseText(raw).trim()
        if (text.isBlank()) return null
        return if (text == factualOkSentinel) {
            FactualVerification(localAccepted = true)
        } else {
            FactualVerification(localAccepted = false, correctedAnswer = text)
        }
    }

    /**
     * External capability path. The FixSight capability router has already
     * established that fresh/live information is required.
     *
     * Provider strategy:
     *   1) Use Groq Compound Mini with the provider's minimal documented body.
     *   2) Retry on full Compound if Mini does not prove server-side tool use.
     *   3) Fall back to GPT-OSS browser_search with tool_choice=required. This is
     *      the fail-closed live-data path: the server must execute search or the
     *      request fails; FixSight never substitutes stale local/model memory.
     */
    suspend fun chatWithExternalCapabilities(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        safetyCritical: Boolean = false,
        groundedKnowledge: Boolean = false
    ): String {
        if (apiKey.isBlank() || authFailed) return ""

        // Live search must be deliberately lightweight. Normal FixSight turns may
        // contain notebook/RCA/attachment evidence, but Compound/browser-search
        // only need the current user request plus a tiny amount of continuity.
        val externalQuery = extractTaggedBlock(currentQuery, "user_request")
            .ifBlank { currentQuery.trim() }
            .take(EXTERNAL_QUERY_CHAR_LIMIT)
        val groundedContext = if (groundedKnowledge || safetyCritical) {
            extractTaggedBlock(currentQuery, "internal_context")
                .take(EXTERNAL_GROUNDED_CONTEXT_CHAR_LIMIT)
        } else ""
        val compactHistory = compactExternalHistory(messages)

        val currentDeviceDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val systemInstruction =
            "You are ArogyaDoot, one live conversational AI assistant. " +
                "The device date for this request is $currentDeviceDate. Interpret relative/current requests against this date and verify them from live search results. " +
                "This request requires fresh information outside the local model. " +
                "Search the web before answering and ground current claims in the search result. " +
                "Do not answer a current/external request from model memory alone. " +
                (if (groundedKnowledge)
                    "A small selected knowledge-base context may be included. It remains authoritative for protocol-specific clinical instructions. "
                 else "") +
                (if (safetyCritical)
                    "Prioritize safety and do not invent measurements or safe conditions. "
                 else "") +
                "Be concise by default. Give fuller detail when the user explicitly asks for it. " +
                "Do not discuss tool routing or internal context."

        fun messagesArray(retry: Boolean, minimal: Boolean): JSONArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    systemInstruction +
                        if (retry) " A previous live-search attempt failed. Search now before answering." else ""
                )
            })
            if (!minimal) {
                compactHistory.forEach { (role, content) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            }
            put(JSONObject().apply {
                put("role", "user")
                put(
                    "content",
                    buildString {
                        if (!minimal && groundedContext.isNotBlank()) {
                            append("<internal_context>\n")
                            append(groundedContext)
                            append("\n</internal_context>\n\n")
                        }
                        append("<user_request>\n")
                        append(externalQuery.take(if (minimal) EXTERNAL_MINIMAL_QUERY_CHAR_LIMIT else EXTERNAL_QUERY_CHAR_LIMIT))
                        append("\n</user_request>")
                    }
                )
            })
        }

        fun buildCompoundBody(model: String, retry: Boolean, minimal: Boolean): JSONObject = JSONObject().apply {
            put("model", model)
            // For latency-sensitive fresh turns keep Compound Mini extremely small.
            // The capability router has already decided that external data is mandatory,
            // so a long assistant/system envelope only increases tool latency and can
            // trigger provider payload rejection. The executed_tools check below still
            // guarantees that FixSight never accepts a memory-only answer here.
            if (minimal) {
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        "Use live web search and answer the request with current verified information. " +
                            "Device date: $currentDeviceDate. Request: ${externalQuery.take(EXTERNAL_MINIMAL_QUERY_CHAR_LIMIT)}"
                    )
                }))
            } else {
                put("messages", messagesArray(retry, minimal = false))
            }
            put("max_completion_tokens", 640)
            // Fresh/current turns need retrieval, not an arbitrary Compound tool.
            // Restrict the server-side capability set to web search so the fast
            // first attempt has one job and can be verified through executed_tools.
            put("compound_custom", JSONObject().apply {
                put("tools", JSONObject().apply {
                    put("enabled_tools", JSONArray().put("web_search"))
                })
            })
        }

        fun logExternalRequest(model: String, body: JSONObject, minimal: Boolean) {
            Log.i(
                TAG,
                "EXTERNAL_REQUEST_SIZE model=$model minimal=$minimal chars=${body.toString().length} " +
                    "historyMessages=${if (minimal) 0 else compactHistory.size} queryChars=${externalQuery.length} " +
                    "groundedContextChars=${if (minimal) 0 else groundedContext.length}"
            )
        }

        var compoundRejectedAsTooLarge = false
        // A Compound answer that did not report a search: kept only as the very last resort.
        var unverifiedCompoundText = ""

        suspend fun attemptCompound(
            model: String,
            retry: Boolean,
            attempts: Int,
            minimal: Boolean,
            timeoutMs: Long = EXTERNAL_COMPOUND_READ_TIMEOUT_MS
        ): String {
            val requestBody = buildCompoundBody(model, retry, minimal = minimal)
            logExternalRequest(model, requestBody, minimal = minimal)
            val result = requestWithRetry(
                requestBody,
                maxAttempts = attempts,
                perAttemptReadTimeoutMs = timeoutMs
            )
            val raw = result.first

            // A tiny live-query request receiving HTTP 413 is a provider/tool-path
            // rejection, not useful evidence that another Compound variant will work.
            // Jump straight to the already-existing forced Browser Search fallback so
            // it gets the remaining external-turn timeout budget.
            if (raw == null && isRequestTooLarge(lastErrorDetail)) {
                compoundRejectedAsTooLarge = true
                Log.w(TAG, "EXTERNAL_413_BROWSER_FALLBACK model=$model")
                return ""
            }

            if (raw == null) {
                Log.w(
                    TAG,
                    "EXTERNAL_COMPOUND_HTTP_FAIL model=$model status=$providerStatus error=$lastErrorDetail latencyMs=$lastRequestLatencyMs"
                )
                return ""
            }
            val text = parseText(raw)
            val executed = parseExecutedTools(raw)
            Log.i(
                TAG,
                "EXTERNAL_COMPOUND_RESULT model=$model chars=${text.length} " +
                    "toolCount=${executed.size} tools=${executed.joinToString(",")}"
            )
            if (text.isNotBlank() && executed.isEmpty() && unverifiedCompoundText.isBlank()) unverifiedCompoundText = text
            if (text.isBlank() || executed.isEmpty()) return ""
            return text
        }

        // Fresh/current information is a dedicated search task. Groq supports
        // browser_search on both GPT-OSS 20B and 120B. Use the faster 20B first,
        // then fail over to 120B on timeout/5xx/429/empty content. Each browser
        // model owns its own cooldown so one model quota never disables the other
        // or the normal chat model.
        val minimalFirst = compactHistory.isEmpty() && groundedContext.isBlank()

        fun buildBrowserBody(model: String, minimal: Boolean, retryHint: Boolean): JSONObject = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray(retry = retryHint, minimal = minimal))
            put("tools", JSONArray().apply {
                put(JSONObject().apply { put("type", "browser_search") })
            })
            put("tool_choice", "required")
            put("max_completion_tokens", 900)
        }

        suspend fun attemptBrowserModel(
            model: String,
            minimal: Boolean,
            timeoutMs: Long,
            retryHint: Boolean
        ): String {
            val now = System.currentTimeMillis()
            val cooldownUntil = externalRateLimitedUntilMs[model] ?: 0L
            if (now < cooldownUntil) {
                Log.w(TAG, "FRESH_MODEL_SKIPPED model=$model cooldownMs=${cooldownUntil - now}")
                return ""
            }

            val body = buildBrowserBody(model, minimal, retryHint)
            logExternalRequest(model, body, minimal)
            Log.w(TAG, "EXTERNAL_REQUIRED_BROWSER_BEGIN model=$model")
            val result = requestWithRetry(
                body,
                maxAttempts = 1,
                perAttemptReadTimeoutMs = timeoutMs
            )
            val raw = result.first
            if (raw == null) {
                Log.w(
                    TAG,
                    "FRESH_MODEL_FAILOVER model=$model status=$providerStatus error=$lastErrorDetail latencyMs=$lastRequestLatencyMs"
                )
                return ""
            }

            val text = parseText(raw).trim()
            if (text.isBlank()) {
                Log.w(TAG, "FRESH_MODEL_FAILOVER model=$model reason=empty_content")
                return ""
            }
            Log.i(TAG, "EXTERNAL_REQUIRED_BROWSER_SUCCESS model=$model chars=${text.length}")
            return text
        }

        // Groq explicitly recommends Web Search over Browser Search for
        // latency-sensitive current-information requests. Compound Mini performs
        // a single server-side web_search call and is therefore the primary path.
        // Browser Search remains a fail-closed recovery path when Web Search is
        // unavailable or does not prove tool execution.
        Log.i(
            TAG,
            "FRESH_ROUTE_WEB_SEARCH_PRIMARY model=$COMPOUND_MODEL compoundBackup=$FULL_COMPOUND_MODEL browserPrimary=$REQUIRED_BROWSER_SEARCH_MODEL browserBackup=$BACKUP_BROWSER_SEARCH_MODEL minimal=$minimalFirst"
        )

        // Chain A: Compound web search (Mini, then full Compound). Chain B (started only if A has not
        // answered within EXTERNAL_HEDGE_AFTER_MS): GPT-OSS browser search, 20B then 120B. The first
        // non-empty answer wins and the other chain is cancelled. Earlier conversation is included
        // only when the question depends on it (the caller passes history only then), so
        // follow-ups like "what about its budget?" are searched with their subject.
        suspend fun chainA(): String {
            // Minimal requests: a follow-up's subject is already folded into the query by the caller.
            // Sending the conversation as messages (2 Oct) made follow-up searches fail more often.
            val mini = attemptCompound(model = COMPOUND_MODEL, retry = false, attempts = 1, minimal = true)
            if (mini.isNotBlank()) {
                Log.i(TAG, "FRESH_WEB_SEARCH_SUCCESS model=$COMPOUND_MODEL chars=${mini.length}")
                return mini
            }
            Log.w(TAG, "FRESH_WEB_SEARCH_COMPOUND_FAILOVER from=$COMPOUND_MODEL to=$FULL_COMPOUND_MODEL status=$providerStatus error=$lastErrorDetail")
            val full = attemptCompound(
                model = FULL_COMPOUND_MODEL, retry = true, attempts = 1, minimal = true,
                timeoutMs = EXTERNAL_FULL_COMPOUND_READ_TIMEOUT_MS
            )
            if (full.isNotBlank()) Log.i(TAG, "FRESH_WEB_SEARCH_SUCCESS model=$FULL_COMPOUND_MODEL chars=${full.length}")
            return full
        }

        suspend fun chainB(): String {
            val primaryText = attemptBrowserModel(
                model = REQUIRED_BROWSER_SEARCH_MODEL, minimal = minimalFirst,
                timeoutMs = EXTERNAL_BROWSER_READ_TIMEOUT_MS, retryHint = false
            )
            if (primaryText.isNotBlank()) return primaryText
            Log.w(TAG, "FRESH_PROVIDER_FAILOVER from=$REQUIRED_BROWSER_SEARCH_MODEL to=$BACKUP_BROWSER_SEARCH_MODEL")
            val backupText = attemptBrowserModel(
                model = BACKUP_BROWSER_SEARCH_MODEL, minimal = minimalFirst,
                timeoutMs = EXTERNAL_BROWSER_BACKUP_READ_TIMEOUT_MS, retryHint = true
            )
            if (backupText.isNotBlank()) Log.i(TAG, "FRESH_FAILOVER_SUCCESS model=$BACKUP_BROWSER_SEARCH_MODEL")
            return backupText
        }

        val searched = coroutineScope {
            val winner = CompletableDeferred<String>()
            val a = launch { chainA().takeIf { it.isNotBlank() }?.let { winner.complete(it) } }
            val b = launch {
                delay(EXTERNAL_HEDGE_AFTER_MS)
                if (!winner.isCompleted) {
                    Log.i(TAG, "FRESH_WEB_SEARCH_HEDGE_START after=${EXTERNAL_HEDGE_AFTER_MS}ms model=$REQUIRED_BROWSER_SEARCH_MODEL")
                    chainB().takeIf { it.isNotBlank() }?.let { winner.complete(it) }
                }
            }
            val bothDone = launch { joinAll(a, b); winner.complete("") }
            val text = winner.await()
            a.cancel(); b.cancel(); bothDone.cancel()
            text
        }
        if (searched.isNotBlank()) return stripSearchCitations(searched)

        if (unverifiedCompoundText.isNotBlank()) {
            Log.w(TAG, "FRESH_UNVERIFIED_COMPOUND_USED chars=${unverifiedCompoundText.length}")
            return stripSearchCitations(unverifiedCompoundText)
        }
        Log.w(TAG, "FRESH_UNAVAILABLE_FINAL status=$providerStatus primary=$REQUIRED_BROWSER_SEARCH_MODEL backup=$BACKUP_BROWSER_SEARCH_MODEL")
        return ""
    }

    /** Browser-search citation markers such as 【1†L21-L24】 are not readable to users. */
    private fun stripSearchCitations(text: String): String =
        text.replace(Regex("\\s*【[^】]{0,40}】"), "").replace(Regex("[ \\t]+([.,;:])"), "$1").trim()

    private fun extractTaggedBlock(value: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = value.lastIndexOf(open)
        if (start < 0) return ""
        val contentStart = start + open.length
        val end = value.indexOf(close, contentStart)
        if (end < 0 || end <= contentStart) return ""
        return value.substring(contentStart, end).trim()
    }

    private fun compactExternalHistory(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        val selected = ArrayDeque<Pair<String, String>>()
        var chars = 0
        var count = 0
        for ((roleRaw, contentRaw) in messages.asReversed()) {
            if (count >= 4) break
            val role = roleRaw.trim().lowercase()
            if (role != "user" && role != "assistant") continue
            val content = extractTaggedBlock(contentRaw, "user_request")
                .ifBlank { contentRaw.trim() }
                .take(EXTERNAL_HISTORY_MESSAGE_CHAR_LIMIT)
            if (content.isBlank()) continue
            val cost = content.length + role.length + 4
            if (selected.isNotEmpty() && chars + cost > EXTERNAL_HISTORY_CHAR_BUDGET) break
            selected.addFirst(role to content)
            chars += cost
            count++
        }
        return selected.toList()
    }

    private fun isRequestTooLarge(detail: String?): Boolean {
        val text = detail.orEmpty().lowercase()
        return text.contains("http_413") ||
            text.contains("request entity too large") ||
            text.contains("request_too_large")
    }

    private fun parseExecutedTools(raw: String): List<String> {
        return try {
            val choices = JSONObject(raw).optJSONArray("choices") ?: return emptyList()
            if (choices.length() == 0) return emptyList()
            val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return emptyList()
            val tools = message.optJSONArray("executed_tools") ?: return emptyList()
            buildList {
                for (i in 0 until tools.length()) {
                    val item = tools.optJSONObject(i) ?: continue
                    val type = item.optString("type", "").trim()
                    val name = item.optString("name", "").trim()
                    add(
                        when {
                            name.isNotBlank() && type.isNotBlank() -> "$type:$name"
                            name.isNotBlank() -> name
                            type.isNotBlank() -> type
                            else -> "tool"
                        }
                    )
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Executed-tools parse failure: ${t.message}")
            emptyList()
        }
    }



    /**
     * Vision-tool path for a text-first local assistant.
     *
     * This call does not act as the conversational assistant. It analyzes the
     * current image and returns concise factual visual evidence relevant to the
     * user's current request. The main local Gemma conversation remains
     * responsible for the final answer and for using KB/memory.
     */
    suspend fun analyzeImageObservation(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        bitmap: Bitmap,
        detail: VisionDetail = VisionDetail.STANDARD
    ): String {
        if (apiKey.isBlank()) {
            providerStatus = GroqProviderStatus.NO_KEY
            return ""
        }
        if (authFailed) return ""

        val prepared = prepareVisionImage(bitmap, detail)
        val jpeg = prepared.jpeg
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val boundedHistory = boundHistory(messages)

        val body = JSONObject().apply {
            put("model", PRIMARY_VISION_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are the visual perception tool for ArogyaDoot. Inspect only the supplied image. " +
                            "Do not diagnose, troubleshoot, recommend actions, or agree with the user's assumed failure mode. " +
                            "Return compact structured visual evidence with exactly these headings: " +
                            "VISIBLE_FACTS, SUPPORTED_INFERENCES, VISIBLE_STATUS_TEXT, CONTRADICTIONS, UNKNOWN, AR_LOCALIZATION. " +
                            "For AR_LOCALIZATION, decide semantically whether the user is asking to locate, identify, point to, show, mark, box, circle, or label a visible target. " +
                            "If no localization was requested, return AR_LOCALIZATION: NONE. If localization was requested but the target is not confidently localizable, return AR_LOCALIZATION: NOT_FOUND | target=<short label>. If it is visible, return exactly: " +
                            "AR_LOCALIZATION: FOUND | target=<short label> | confidence=<0.00..1.00> | x=<0..1> | y=<0..1> | w=<0..1> | h=<0..1> | marker=<ARROW or BOX>. " +
                            "Coordinates are a tight bounding box normalized from the image top-left. Never invent coordinates. " +
                            "Return exactly NO_USEFUL_VISUAL_EVIDENCE only if the image is blank, corrupted, completely obscured, or has no discernible subject. Plain text only."
                    )
                })

                // A small amount of conversation helps resolve references such
                // as "that light", "the display", or "it", without changing the
                // role of this call from perception to conversation.
                boundedHistory.takeLast(6).forEach { (role, content) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content.take(1_200))
                    })
                }

                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put(
                                "text",
                                "Current user request: ${currentQuery.trim().take(2_500)}\n" +
                                    "Analyze the current camera frame and return only relevant visible evidence."
                            )
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$b64")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 192)
            put("temperature", 0.1)
            put("top_p", 0.3)
            put("reasoning_effort", "none")
        }

        Log.i(
            TAG,
            "VISION_CLOUD_OBSERVATION_BEGIN model=$PRIMARY_VISION_MODEL detail=${prepared.detail} " +
                "source=${bitmap.width}x${bitmap.height} send=${prepared.width}x${prepared.height} imageBytes=${jpeg.size}"
        )
        val raw = requestWithRetry(body, maxAttempts = 3).first
        var observation = raw?.let(::parseText)?.trim().orEmpty()

        // A valid frame must not be discarded merely because the user's wording is
        // vague. If the first perception prompt over-rejects, retry once with fallback model.
        if (observation.isBlank() || observation == "NO_USEFUL_VISUAL_EVIDENCE") {
            Log.w(TAG, "VISION_PERCEPTION_RETRY reason=fallback_or_sentinel")
            val retryBody = JSONObject().apply {
                put("model", FALLBACK_VISION_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Describe only the visible contents of the supplied image in 1 to 3 factual sentences. Do not diagnose or give instructions. Return NO_USEFUL_VISUAL_EVIDENCE only if the image is blank, corrupted, or completely obscured.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "List the clearly visible objects and scene state.") })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$b64") })
                            })
                        })
                    })
                })
                put("max_tokens", 192)
                put("temperature", 0.0)
                put("top_p", 0.2)
                put("reasoning_effort", "none")
            }
            val retryRaw = requestWithRetry(retryBody, maxAttempts = 2).first
            val retryObservation = retryRaw?.let(::parseText)?.trim().orEmpty()
            if (retryObservation.isNotBlank()) observation = retryObservation
        }

        Log.i(
            TAG,
            "VISION_CLOUD_OBSERVATION_RESULT chars=${observation.length} " +
                "useful=${observation.isNotBlank() && observation != "NO_USEFUL_VISUAL_EVIDENCE"}"
        )
        return observation
    }

    suspend fun chatVision(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        bitmap: Bitmap,
        detail: VisionDetail = VisionDetail.STANDARD,
        safetyCritical: Boolean = false
    ): String = chatVision(messages, currentQuery, listOf(bitmap), detail, safetyCritical)

    /**
     * Multimodal cloud path used by strict Audio+Video and turn-scoped photo/PDF
     * attachments. Multiple images stay inside one user message and therefore one
     * accepted FixSight turn. Allows up to 5 images per request.
     */
    suspend fun chatVision(
        messages: List<Pair<String, String>>,
        currentQuery: String,
        bitmaps: List<Bitmap>,
        detail: VisionDetail = VisionDetail.STANDARD,
        safetyCritical: Boolean = false
    ): String {
        if (apiKey.isBlank()) {
            providerStatus = GroqProviderStatus.NO_KEY
            return ""
        }
        if (authFailed) return ""
        val usable = bitmaps.filter { !it.isRecycled && it.width > 0 && it.height > 0 }.take(if (isGroqKey) GROQ_MAX_IMAGES else 5)
        if (usable.isEmpty()) return ""

        val prepared = usable.map { prepareVisionImage(it, detail) }
        prepared.forEachIndexed { index, image ->
            val src = usable[index]
            Log.i(
                TAG,
                "VISION_REQUEST_IMAGE index=$index detail=${image.detail} source=${src.width}x${src.height} " +
                    "send=${image.width}x${image.height} quality=${image.detail.jpegQuality} imageBytes=${image.jpeg.size}"
            )
        }
        val boundedHistory = boundHistory(messages)

        fun createVisionBody(model: String): JSONObject = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content",
                        "You are ArogyaDoot, a knowledgeable assistant answering about the supplied image(s). " +
                        "Look at every supplied image carefully and use any internal source context silently. " +
                        "Name what is actually visible (objects, text, labels, medicine names and strengths, numbers, readings) and read printed or handwritten text exactly as written. " +
                        "Reply in the language and script the user writes in; for Hindi in English letters (Hinglish), reply in simple English. " +
                        (if (safetyCritical) "This request may be hazardous: prioritize safety, never infer that equipment is de-energized or safe unless the supplied evidence proves it, and do not give instructions requiring dangerous energized or invasive work. " else "") +
                        "Answer the user's actual question directly, usually 2 to 5 short sentences (about 60 to 120 words), with the most useful point first. If the user explicitly asks for steps, more detail, examples, a comparison, or a fuller explanation, give enough complete detail to satisfy that request while staying faithful to what is actually visible. " +
                        "Continue the same accepted conversation; do not create a separate vision or attachment assistant. " +
                        "Do not invent visual details and do not claim to have read content that is not visible in the supplied images.")
                })
                boundedHistory.forEach { (role, content) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", boundCurrentUserContent(currentQuery, 18_000))
                        })
                        prepared.forEach { image ->
                            val b64 = Base64.encodeToString(image.jpeg, Base64.NO_WRAP)
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$b64")
                                })
                            })
                        }
                    })
                })
            })
            put("max_tokens", 768)
            // Reading a label or a reading must be faithful, not creative.
            put("temperature", 0.3)
            put("top_p", 0.8)
            put("reasoning_effort", "none")
        }

        val primaryBody = createVisionBody(PRIMARY_VISION_MODEL)
        val raw = requestWithRetry(primaryBody, maxAttempts = 3).first
        if (raw != null) {
            val text = parseText(raw)
            if (text.isNotBlank()) return text
        }

        Log.w(TAG, "CHAT_VISION_FALLBACK model=$FALLBACK_VISION_MODEL")
        val fallbackBody = createVisionBody(FALLBACK_VISION_MODEL)
        val fallbackRaw = requestWithRetry(fallbackBody, maxAttempts = 2).first ?: return ""
        return parseText(fallbackRaw)
    }

    /** Preserve the trailing <user_request> block when attachment context is large. */
    private fun boundCurrentUserContent(value: String, maxChars: Int): String {
        val clean = value.trim()
        if (clean.length <= maxChars) return clean
        val marker = "<user_request>"
        val requestStart = clean.lastIndexOf(marker)
        if (requestStart < 0) return clean.take(maxChars)
        val requestBlock = clean.substring(requestStart).take(maxChars.coerceAtLeast(1))
        val contextBudget = (maxChars - requestBlock.length - 48).coerceAtLeast(0)
        if (contextBudget == 0) return requestBlock.take(maxChars)
        val prefix = clean.substring(0, requestStart).trim()
        val boundedPrefix = if (prefix.length <= contextBudget) prefix else prefix.take(contextBudget)
        return buildString {
            append(boundedPrefix)
            if (prefix.length > boundedPrefix.length) append("\n[internal context truncated]\n")
            else append('\n')
            append(requestBlock)
        }.take(maxChars)
    }

    private fun boundHistory(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        val selected = ArrayDeque<Pair<String, String>>()
        var chars = 0
        for ((roleRaw, contentRaw) in messages.asReversed()) {
            val role = roleRaw.trim().lowercase()
            val content = contentRaw.trim()
            if ((role != "user" && role != "assistant") || content.isBlank()) continue
            val clipped = content.take(2_500)
            val cost = clipped.length + role.length + 4
            if (selected.isNotEmpty() && chars + cost > HISTORY_CHAR_BUDGET) break
            selected.addFirst(role to clipped)
            chars += cost
        }
        return selected.toList()
    }

    private enum class ErrorType { AUTH, RATE_LIMIT, SERVER_ERROR, NETWORK, UNKNOWN }

    private fun parseRateLimitCooldownMs(raw: String?): Long {
        val text = raw.orEmpty()
        // Groq commonly returns phrases such as "try again in 3m17.5s" or
        // "try again in 9.504s". This parses provider retry metadata only; it is
        // not user/topic routing. Add a small guard band to avoid an immediate re-hit.
        val mixed = Regex("(?i)try\\s+again\\s+in\\s+(?:(\\d+(?:\\.\\d+)?)m)?\\s*(?:(\\d+(?:\\.\\d+)?)s)?").find(text)
        if (mixed != null) {
            val minutes = mixed.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val seconds = mixed.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val total = ((minutes * 60.0 + seconds) * 1000.0).toLong()
            if (total > 0L) return total.coerceAtMost(30L * 60L * 1000L) + 1_500L
        }
        return 30_000L
    }

    /**
     * One OpenAI-compatible SSE stream for a chat body (same endpoint and model mapping as
     * [attemptRequest]). Each content piece goes to [onDelta] as it arrives. Returns the full
     * text plus the first-words latency, or null text when streaming is unavailable (the
     * caller falls back to a normal request). A real cancellation is rethrown, never
     * converted to null, so it never triggers a fallback request.
     */
    private suspend fun requestStreamText(
        body: JSONObject,
        onDelta: (String) -> Unit
    ): Pair<String?, Long> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        fun cancelled(): Boolean = coroutineContext[Job]?.isActive == false
        // The Responses-API web-search shape is not an SSE chat stream; the caller retries normally.
        if (!isGroqKey && body.optString("model", "") in GROQ_ONLY_MODELS) return@withContext null to -1L
        val sendBody = JSONObject(if (isGroqKey) groqChatBody(body).toString() else metaChatBody(body).toString())
        sendBody.put("stream", true)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(sendBody.toString().toRequestBody(jsonMediaType))
            .build()
        val streamClient = client.newBuilder()
            .readTimeout(90L, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val call = streamClient.newCall(request)
        activeCall.set(call)
        val hook = coroutineContext[Job]?.invokeOnCompletion {
            activeCall.compareAndSet(call, null)
            runCatching { call.cancel() }
        }
        try {
            val response = try {
                call.execute()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                if (cancelled()) throw CancellationException("stream cancelled")
                Log.w(TAG, "KB_STREAM_NET ${e.javaClass.simpleName}:${e.message}")
                return@withContext null to -1L
            }
            response.use { r ->
                if (!r.isSuccessful) {
                    val err = runCatching { r.body?.string() }.getOrNull().orEmpty()
                    val detail = "HTTP_${r.code}:" + err.replace(Regex("\\s+"), " ").take(240)
                    if (r.code == 401 || r.code == 403) {
                        authFailed = true
                        providerStatus = GroqProviderStatus.AUTH_FAILED
                        lastErrorDetail = detail
                    } else if (r.code == 429) {
                        providerStatus = GroqProviderStatus.RATE_LIMITED
                        rateLimitedUntilMs = System.currentTimeMillis() + parseRateLimitCooldownMs(err)
                        lastErrorDetail = detail
                    }
                    Log.w(TAG, "KB_STREAM_HTTP code=${r.code}")
                    return@withContext null to -1L
                }
                val src = r.body?.source() ?: return@withContext null to -1L
                val full = StringBuilder()
                var firstMs = -1L
                while (true) {
                    val line = try {
                        src.readUtf8Line()
                    } catch (e: IOException) {
                        if (cancelled()) throw CancellationException("stream cancelled")
                        Log.w(TAG, "KB_STREAM_BREAK chars=${full.length}")
                        return@withContext null to -1L
                    } ?: break
                    val piece = SseStreamParser.contentOf(line) ?: continue
                    if (piece.isEmpty()) continue
                    if (firstMs < 0 && piece.isNotBlank()) firstMs = System.currentTimeMillis() - t0
                    full.append(piece)
                    runCatching { onDelta(piece) }
                    if (cancelled()) throw CancellationException("stream cancelled")
                }
                val text = full.toString()
                if (text.isBlank()) return@withContext null to -1L
                authFailed = false
                providerStatus = GroqProviderStatus.READY
                rateLimitedUntilMs = 0L
                lastSuccessAtMs = System.currentTimeMillis()
                lastErrorDetail = ""
                lastRequestLatencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(0L)
                return@withContext text to firstMs
            }
        } finally {
            runCatching { hook?.dispose() }
            activeCall.compareAndSet(call, null)
        }
    }

    private suspend fun requestWithRetry(
        body: JSONObject,
        maxAttempts: Int,
        perAttemptReadTimeoutMs: Long? = null
    ): Pair<String?, ErrorType?> {
        var last: Pair<String?, ErrorType?> = null to null
        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            last = attemptRequest(body, perAttemptReadTimeoutMs)
            if (last.first != null) return last
            val type = last.second
            // A 429 carries a provider cooldown and is not recoverable by an
            // immediate retry with the same account/model. Open the circuit and let
            // a later turn retry after the cooldown instead of multiplying latency.
            if (type == ErrorType.RATE_LIMIT) return last
            if (type != ErrorType.SERVER_ERROR && type != ErrorType.NETWORK) return last
            if (attempt >= maxAttempts) break

            val backoffMs = when (type) {
                ErrorType.NETWORK -> 750L * (1L shl (attempt - 1).coerceAtMost(4))
                else -> 1_000L * attempt
            }
            delay(backoffMs)
        }
        return last
    }

    private suspend fun attemptRequest(
        body: JSONObject,
        perAttemptReadTimeoutMs: Long? = null
    ): Pair<String?, ErrorType?> =
        suspendCancellableCoroutine { continuation ->
            val requestStartedAt = System.currentTimeMillis()
            val requestedModel = body.optString("model", "")
            // Meta Model API: a live-information request becomes Muse Spark with its web_search
            // tool (Responses API); the answer is re-shaped to the chat-completions JSON the rest
            // of this class parses.
            val metaWebSearch = !isGroqKey && requestedModel in GROQ_ONLY_MODELS
            val (sendUrl, sendBody) = when {
                metaWebSearch -> META_RESPONSES_ENDPOINT to metaWebSearchBody(body)
                isGroqKey -> endpoint to groqChatBody(body)
                else -> endpoint to metaChatBody(body)
            }
            val request = Request.Builder()
                .url(sendUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .apply {
                    // Pin Compound requests to Groq's current tool contract. The
                    // latest version keeps built-in web_search behavior/citations
                    // aligned with the provider's current API while leaving normal
                    // text/vision model requests untouched.
                    if (isGroqKey && body.optString("model", "").startsWith("groq/compound")) {
                        addHeader("Groq-Model-Version", "latest")
                    }
                }
                .post(sendBody.toString().toRequestBody(jsonMediaType))
                .build()
            val requestClient = if (perAttemptReadTimeoutMs != null) {
                client.newBuilder()
                    .readTimeout(perAttemptReadTimeoutMs.coerceAtLeast(1_000L), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            } else client
            val call = requestClient.newCall(request)
            activeCall.set(call)
            continuation.invokeOnCancellation {
                activeCall.compareAndSet(call, null)
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCall.compareAndSet(call, null)
                    if (!continuation.isActive) return
                    val errorType = when (e) {
                        is SocketTimeoutException, is ConnectException, is UnknownHostException -> ErrorType.NETWORK
                        else -> ErrorType.NETWORK
                    }
                    providerStatus = GroqProviderStatus.NETWORK_ERROR
                    lastRequestLatencyMs = (System.currentTimeMillis() - requestStartedAt).coerceAtLeast(0L)
                    lastErrorDetail = e.javaClass.simpleName + ":" + (e.message ?: "network failure")
                    Log.w(TAG, "HTTP failure: ${e.message}")
                    continuation.resume(null to errorType)
                }

                override fun onResponse(call: Call, response: Response) {
                    activeCall.compareAndSet(call, null)
                    response.use { r ->
                        if (!continuation.isActive) return
                        val raw = try { r.body?.string() } catch (_: Throwable) { null }
                        lastRequestLatencyMs = (System.currentTimeMillis() - requestStartedAt).coerceAtLeast(0L)
                        val result: Pair<String?, ErrorType?> = when {
                            r.isSuccessful -> {
                                authFailed = false
                                providerStatus = GroqProviderStatus.READY
                                val responseModel = body.optString("model", "")
                                if (responseModel == REQUIRED_BROWSER_SEARCH_MODEL || responseModel == BACKUP_BROWSER_SEARCH_MODEL) {
                                    externalRateLimitedUntilMs.remove(responseModel)
                                } else {
                                    rateLimitedUntilMs = 0L
                                }
                                lastSuccessAtMs = System.currentTimeMillis()
                                lastErrorDetail = ""
                                (if (metaWebSearch && raw != null) responsesToChatJson(raw) else raw) to null
                            }
                            r.code == 401 || r.code == 403 -> {
                                authFailed = true
                                providerStatus = GroqProviderStatus.AUTH_FAILED
                                lastErrorDetail = "HTTP_${r.code}:" + raw.orEmpty().replace(Regex("\\s+"), " ").take(240)
                                null to ErrorType.AUTH
                            }
                            r.code == 408 -> {
                                providerStatus = GroqProviderStatus.NETWORK_ERROR
                                lastErrorDetail = "HTTP_408:" + raw.orEmpty().replace(Regex("\\s+"), " ").take(240)
                                null to ErrorType.NETWORK
                            }
                            r.code == 429 -> {
                                providerStatus = GroqProviderStatus.RATE_LIMITED
                                val cooldown = parseRateLimitCooldownMs(raw)
                                val responseModel = body.optString("model", "")
                                if (responseModel == REQUIRED_BROWSER_SEARCH_MODEL || responseModel == BACKUP_BROWSER_SEARCH_MODEL) {
                                    externalRateLimitedUntilMs[responseModel] = System.currentTimeMillis() + cooldown
                                    Log.w(TAG, "FRESH_MODEL_RATE_LIMIT model=$responseModel cooldownMs=$cooldown")
                                } else {
                                    rateLimitedUntilMs = System.currentTimeMillis() + cooldown
                                    Log.w(TAG, "CLOUD_RATE_LIMIT_CIRCUIT_OPEN cooldownMs=$cooldown")
                                }
                                lastErrorDetail = "HTTP_${r.code}:" + raw.orEmpty().replace(Regex("\\s+"), " ").take(240)
                                null to ErrorType.RATE_LIMIT
                            }
                            r.code in 500..599 -> {
                                providerStatus = GroqProviderStatus.NETWORK_ERROR
                                lastErrorDetail = "HTTP_${r.code}:" + raw.orEmpty().replace(Regex("\\s+"), " ").take(240)
                                null to ErrorType.SERVER_ERROR
                            }
                            else -> {
                                providerStatus = GroqProviderStatus.NETWORK_ERROR
                                lastErrorDetail = "HTTP_${r.code}:" + raw.orEmpty().replace(Regex("\\s+"), " ").take(240)
                                null to ErrorType.UNKNOWN
                            }
                        }
                        continuation.resume(result)
                    }
                }
            })
        }

    /** Chat body for the Meta Model API: Muse Spark ids, valid reasoning effort, no Groq-only fields. */
    private fun metaChatBody(body: JSONObject): JSONObject {
        val out = JSONObject(body.toString())
        out.put("model", when (out.optString("model")) {
            FALLBACK_HQ_MODEL, FALLBACK_VISION_MODEL -> META_SPARK_FALLBACK
            else -> META_SPARK
        })
        when (out.optString("reasoning_effort", "")) {
            "", "minimal", "low", "medium", "high", "max" -> Unit
            else -> out.put("reasoning_effort", "minimal")  // Groq's "none"
        }
        out.remove("compound_custom")
        if (out.has("max_completion_tokens") && out.has("max_tokens")) out.remove("max_completion_tokens")
        return out
    }

    /** Chat request for Groq: the Qwen model, its reasoning setting, at most GROQ_MAX_IMAGES images. */
    private fun groqChatBody(body: JSONObject): JSONObject {
        val model = body.optString("model")
        if (model in GROQ_ONLY_MODELS) return body
        val out = JSONObject(body.toString())
        out.put("model", GROQ_MODEL_NAMES[model] ?: GROQ_QWEN_MODEL)
        // Qwen: "none" = direct answer (no thinking text), else low/medium/high.
        when (out.optString("reasoning_effort", "")) {
            "none", "low", "medium", "high" -> Unit
            "" -> out.put("reasoning_effort", "none")
            "minimal" -> out.put("reasoning_effort", "none")
            else -> out.put("reasoning_effort", "high")
        }
        out.remove("compound_custom")
        out.optJSONArray("messages")?.let { messages ->
            var images = 0
            for (i in 0 until messages.length()) {
                val content = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
                val kept = org.json.JSONArray()
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "image_url" && ++images > GROQ_MAX_IMAGES) continue
                    kept.put(part)
                }
                messages.getJSONObject(i).put("content", kept)
            }
        }
        return out
    }

    /** Live-information request -> Muse Spark + web_search on the Responses API. */
    private fun metaWebSearchBody(body: JSONObject): JSONObject {
        val messages = body.optJSONArray("messages") ?: JSONArray()
        val input = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = when (m.optString("role")) { "system", "developer" -> "developer"; "assistant" -> "assistant"; else -> "user" }
            val content = m.opt("content")
            val text = if (content is JSONArray) (0 until content.length()).joinToString("\n") { content.getJSONObject(it).optString("text") } else content?.toString().orEmpty()
            if (text.isNotBlank()) input.put(JSONObject().put("role", role).put("content", text))
        }
        return JSONObject()
            .put("model", META_SPARK)
            .put("input", input)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
    }

    /** Responses API output (text + url citations) as a chat-completions message with a Sources list. */
    private fun responsesToChatJson(raw: String): String = runCatching {
        val resp = JSONObject(raw)
        val text = StringBuilder()
        val sources = LinkedHashMap<String, String>()
        val output = resp.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val parts = item.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.getJSONObject(j)
                if (p.optString("type") != "output_text") continue
                text.append(p.optString("text"))
                val anns = p.optJSONArray("annotations") ?: continue
                for (k in 0 until anns.length()) {
                    val a = anns.getJSONObject(k)
                    if (a.optString("type") == "url_citation") sources[a.optString("url")] = a.optString("title")
                }
            }
        }
        if (sources.isNotEmpty()) {
            text.append("\n\nSources:\n")
            sources.entries.take(6).forEach { (url, title) -> text.append("- ").append(title.ifBlank { url }).append(" ").append(url).append('\n') }
        }
        JSONObject().put("model", META_SPARK).put("choices", JSONArray().put(
            JSONObject().put("index", 0).put("message", JSONObject().put("role", "assistant").put("content", text.toString().trim()))
        )).toString()
    }.getOrDefault(raw)

    private fun parseText(raw: String): String {
        return try {
            val choices = JSONObject(raw).optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            var text = choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
                .trim()
            text = text
                .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<think>[\\s\\S]*\\z", RegexOption.IGNORE_CASE), "")
                .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
                .trim()
            text
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Parse failure: ${t.message}")
            ""
        }
    }
}
