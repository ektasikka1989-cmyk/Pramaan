package com.example.arfas

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Domain-neutral semantic intent used by KB answer shaping and local utilities. */
enum class SemanticQueryIntent {
    VALUE_LOOKUP,
    ENUMERATION,
    COMPARISON,
    PROCEDURE,
    EXPLANATION,
    MATH_CALCULATION,
    GENERAL
}

data class SemanticQueryIntentResult(
    val intent: SemanticQueryIntent,
    val score: Float,
    val runnerUpScore: Float,
    val confident: Boolean
)

/** Semantic answer-size intent. This controls latency/output budget, not topic routing. */
enum class SemanticResponseScope { SHORT, NORMAL, DETAILED }

data class SemanticResponseScopeResult(
    val scope: SemanticResponseScope,
    val score: Float,
    val runnerUpScore: Float,
    val confident: Boolean
)

data class SemanticLocalAnswerSufficiency(
    val sufficient: Boolean,
    val sufficientScore: Float,
    val insufficientScore: Float
)

data class SemanticTroubleshootingResolutionAssessment(
    val resolved: Boolean,
    val resolvedScore: Float,
    val continueScore: Float
)

data class SemanticExternalCapabilityAssessment(
    val required: Boolean,
    val ambiguousExternalLean: Boolean,
    val currentExternalScore: Float,
    val currentLocalScore: Float,
    val contextualExternalScore: Float = 0f,
    val contextualLocalScore: Float = 0f,
    val presentStateScore: Float = 0f,
    val stableStateScore: Float = 0f,
    val contextualPresentStateScore: Float = 0f,
    val contextualStableStateScore: Float = 0f,
    val volatileStateScore: Float = 0f,
    val durableStateScore: Float = 0f,
    val contextualVolatileStateScore: Float = 0f,
    val contextualDurableStateScore: Float = 0f,
    val semanticFreshLean: Boolean = false
)

/**
 * Lightweight semantic routing provider for the no-KB baseline.
 *
 * IMPORTANT:
 * - It NEVER retrieves ANM/SOP knowledge.
 * - It NEVER attaches evidence to Gemma.
 * - It only decides whether a turn requires live/external information or
 *   should be escalated because it is safety-critical.
 *
 * This keeps Audio-only close to the direct AndroLLM path while preserving
 * cloud escalation for capabilities the static local model should not answer.
 */
class SemanticCapabilityContextProvider(
    private val semanticEngine: SemanticSearchEngine
) : BaselineContextProvider, BaselineCapabilityDecider, BaselineResponseValidator {

    private val continuationPrewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val continuationPrewarmInFlight = AtomicBoolean(false)

    companion object {
        private const val TAG = "SemanticCapability"

        // Dynamic freshness boundary. No topic/domain vocabulary is encoded here.
        // The only distinction is whether the requested answer can remain reliable when
        // the model's world knowledge is bounded at approximately the end of 2024.
        private const val EXTERNAL_DESCRIPTOR =
            "the user's actual requested answer materially depends on information that may have changed after December 2024 or must be observed from the current world, including present or future state, recent events, changing values, availability, schedules, rankings, office-holders, rules, releases, service status, forecasts, or other time-sensitive facts; current external retrieval is needed to answer reliably"

        private const val LOCAL_DESCRIPTOR =
            "the user's actual requested answer can be given reliably from knowledge available through December 2024, mathematical or logical reasoning, writing or transformation, a stable method or concept, an explicitly completed historical period, or accepted conversation context without needing post-2024 real-world information"

        // Second, independent semantic axis for the AndroLLM-style coarse router.
        // This judges the TIME REFERENCE of the requested answer rather than its topic.
        // It is intentionally domain-neutral: weather, office-holders, prices, releases,
        // service status and future unknown categories all share the same present-state
        // property without being named here.
        private const val PRESENT_STATE_DESCRIPTOR =
            "the user is asking for a real-world state, value, status, event situation, availability, condition, holder, forecast, schedule, release, rule, ranking, or other fact that applies after December 2024, now, or in the future; if the requested answer is not fixed to a completed pre-2025 period, correctness may depend on up-to-date external information"

        private const val STABLE_STATE_DESCRIPTOR =
            "the user is asking for a timeless explanation, method, procedure, recipe, reasoning or calculation task, creative or transformation task, or a fact explicitly tied to a completed historical period no later than December 2024, so current external information is not required"

        // Third independent freshness axis. It asks whether the requested answer is a
        // volatile world snapshot or durable knowledge. Multiple semantic axes are
        // intentionally combined so no static topic/keyword list decides routing.
        private const val VOLATILE_WORLD_STATE_DESCRIPTOR =
            "the requested answer is a snapshot of the changing real world whose truth can become different with time; answering reliably requires observing or retrieving the relevant present or post-December-2024 state rather than relying only on older parametric knowledge"

        private const val DURABLE_KNOWLEDGE_DESCRIPTOR =
            "the requested answer is durable knowledge, reasoning, a stable method, an explicitly completed historical fact, or a transformation task whose correctness does not depend on observing a changing present or post-December-2024 world state"

        private const val RESPONSE_SHORT_DESCRIPTOR =
            "a narrow conversational follow-up or simple direct request that is fully satisfied by a concise answer with only the most useful points; extra detail would mostly add latency or repetition"
        private const val RESPONSE_NORMAL_DESCRIPTOR =
            "an ordinary request that benefits from a moderate complete answer with enough explanation to be useful but does not require exhaustive treatment"
        private const val RESPONSE_DETAILED_DESCRIPTOR =
            "a request whose goal genuinely requires comprehensive, detailed, step-by-step, exhaustive, or deeply explained coverage rather than a concise answer"

        private const val HIGH_RISK_DESCRIPTOR =
            "a request for potentially hazardous or safety-critical instructions where a wrong answer could cause serious injury, electric shock, fire, gas exposure, " +
            "vehicle safety failure, battery fire, high-voltage exposure, medication dosing, emergency medical harm, or invasive professional servicing"

        private const val ROUTINE_SAFE_DESCRIPTOR =
            "ordinary low-risk information or troubleshooting that a consumer can safely perform without opening hazardous equipment, handling dangerous energy, " +
            "administering prescription treatment, or performing professional safety-critical work"

        private const val VISUAL_REQUIRED_DESCRIPTOR =
            "a request whose answer depends on seeing or inspecting the current camera image, identifying visible objects, reading visible text, " +
            "describing what is on screen, comparing visible items, or reasoning from the current visual scene"

        private const val NON_VISUAL_DESCRIPTOR =
            "a request that can be answered from the user's words and conversation alone without needing to inspect the current camera image"

        private const val CAMERA_EVIDENCE_RELEVANT_DESCRIPTOR =
            "the user is reporting, diagnosing, identifying, checking, locating, comparing, or asking what to do about a concrete physical real-world subject that may be in the already-enabled camera view; inspecting the current frame could materially improve the answer by confirming visible state, condition, setup, screen state, connections, components, damage, or what object is involved, even when the user does not explicitly say look, see, camera, image, or photo"

        private const val CAMERA_EVIDENCE_IRRELEVANT_DESCRIPTOR =
            "the user's request is self-contained in language and can be answered well without observing the current physical scene; the camera happens to be enabled but its contents are incidental, unrelated, or unnecessary to the requested explanation, writing, calculation, general knowledge, creative task, or procedure"

        private const val HIGH_DETAIL_VISUAL_DESCRIPTOR =
            "a visual request where fine image detail is important: reading small text or an error message, identifying a tiny label or serial marking, " +
            "inspecting a small connector, component marking, subtle indicator light, screen symbol, or other detail that may be lost after strong downscaling"

        private const val GENERAL_VISUAL_DESCRIPTOR =
            "a visual request where ordinary scene and object understanding is sufficient without needing to resolve tiny text, labels, markings, or subtle fine detail"

        private const val BROAD_VISUAL_INVENTORY_DESCRIPTOR =
            "a visual request asking for broad scene coverage, a comprehensive description of what is visible, or identification of multiple objects/items across the current frame rather than one focused target or one diagnostic clue"

        private const val FOCUSED_VISUAL_DESCRIPTOR =
            "a visual request focused on one primary object, one specific component, one question about a visible item, or one diagnostic observation rather than an inventory of the whole scene"

        private const val TROUBLESHOOTING_RESOLVED_DESCRIPTOR =
            "in an active troubleshooting conversation, the user is clearly confirming that the original problem is now fixed, working correctly, solved, restored, or no longer occurring after the troubleshooting steps; this is an outcome confirmation, not merely agreement to try a step"

        private const val TROUBLESHOOTING_CONTINUE_DESCRIPTOR =
            "in an active troubleshooting conversation, the user is saying the problem remains, is only partly improved, they are unsure whether it is fixed, they want to keep diagnosing, they are answering a diagnostic question, or they are merely acknowledging the assistant without confirming successful resolution"

        private const val SPATIAL_LOCALIZATION_DESCRIPTOR =
            "a visual request whose answer genuinely depends on identifying where a visible target is located in the current image or scene, including a verbal location answer, without assuming that any marker or overlay should be drawn"

        private const val GENERAL_VISUAL_ANSWER_DESCRIPTOR =
            "a visual request that needs identifying, understanding, describing, inspecting, or diagnosing the current image but does not require the precise spatial position of a target"

        // AR is a side effect and therefore uses an independent, deliberately strict
        // semantic permission boundary. Spatial understanding alone never authorizes it.
        private const val AR_OVERLAY_REQUEST_DESCRIPTOR =
            "the user's requested output explicitly requires ArogyaDoot to visually indicate a target in the camera view using an AR or onscreen marker, arrow, box, highlight, tag, anchor, or equivalent pointing overlay; merely identifying, describing, diagnosing, or verbally locating an object is not enough"

        private const val NO_AR_OVERLAY_DESCRIPTOR =
            "the user wants a normal visual answer such as identifying what something is, describing what is visible, diagnosing a visible problem, reading the scene, or verbally saying where something is, without asking ArogyaDoot to draw or place a marker, highlight, arrow, box, tag, anchor, or overlay in the camera view"

        // Context dependence alone must never authorize AR. A short follow-up can be
        // context-dependent because it refers to a visible object (for example, asking
        // what it is), while still requesting only a normal visual answer. These two
        // descriptors distinguish an omitted AR ACTION from an omitted VISUAL REFERENT
        // semantically, without routing on fixed words or domain vocabulary.
        private const val AR_ACTION_FOLLOWUP_DESCRIPTOR =
            "a short context-dependent follow-up command that continues a previously established visual pointing action and asks the assistant to mark, highlight, point at, tag, box, anchor, or otherwise indicate the referenced target in the camera view"

        private const val VISUAL_REFERENCE_FOLLOWUP_DESCRIPTOR =
            "a short context-dependent visual follow-up that refers to a previously discussed or currently visible object in order to identify, describe, inspect, explain, or diagnose it, but does not request any marker, highlight, arrow, box, tag, anchor, or overlay"

        private const val CONTEXT_FOLLOWUP_DESCRIPTOR =
            "a conversational follow-up whose meaning depends on the immediately preceding discussion, such as an elliptical continuation, pronoun reference, " +
            "request to continue, comparison with the previous answer, or question that is incomplete without prior context"

        private const val STANDALONE_REQUEST_DESCRIPTOR =
            "a complete independently understandable new request or topic that should be answered primarily from the current user message even if previous conversation exists"

        // Selected-KB continuation descriptors. These are intentionally domain-neutral:
        // they distinguish an elliptical follow-up that needs the previous user goal
        // from a self-contained request that already states its own subject and action.
        private const val KB_ELLIPTICAL_FOLLOWUP_DESCRIPTOR =
            "a short follow-up request whose subject, object, or target operation is omitted because it was established by the preceding user request, such as asking to explain it step by step, continue the procedure, give more detail, or describe the next part"

        private const val KB_SELF_CONTAINED_SPECIFIC_DESCRIPTOR =
            "a self-contained request that explicitly states the subject, object, target operation, or information needed and can be understood correctly without inheriting the preceding user's goal"

        // Generic answer-quality descriptors. These are deliberately domain-neutral:
        // they judge whether a short reply advances the user's request, not what
        // subject the user is discussing. No ANM/KB evidence is retrieved here.
        private const val USEFUL_RESPONSE_DESCRIPTOR =
            "a useful assistant response that advances the user's request with a concrete action, specific question, direct factual answer, concise explanation, or useful instruction"
        private const val DIRECT_RESPONSE_DESCRIPTOR =
            "a concise correct direct answer such as an entity, number, calculation result, confirmation, correction, or socially appropriate conversational reply"
        private const val FILLER_RESPONSE_DESCRIPTOR =
            "a generic acknowledgement, filler, malformed fragment, or non-answer that does not answer the user's request, provide useful information, or ask a useful question"
        private const val RESTATEMENT_RESPONSE_DESCRIPTOR =
            "an assistant response that merely repeats, rephrases, mirrors, or turns the user's request back into a question without answering it, adding a useful instruction, or requesting a genuinely missing detail"

        private const val LOCAL_ANSWER_SUFFICIENT_DESCRIPTOR =
            "the assistant response actually fulfills the user's requested task using the capability it was given: it directly answers, explains, reasons, or gives the requested useful action without deferring the task elsewhere, claiming unavailable access, or substituting a limitation statement for the requested answer"
        private const val LOCAL_ANSWER_INSUFFICIENT_DESCRIPTOR =
            "the assistant response does not actually satisfy the user's requested task because it mainly refuses, says it cannot know or access the needed information, admits its knowledge is too old for the requested answer, tells the user to check elsewhere, apologizes instead of answering, or otherwise exposes that the current local capability is insufficient"

        // Domain-neutral user-intent descriptors. These replace finite keyword lists
        // for answer-shape decisions in the generic Knowledge Pack path.
        private const val VALUE_LOOKUP_DESCRIPTOR =
            "a narrow request whose primary goal is to obtain one or more concrete factual values from knowledge, with the value itself being the main answer rather than a broad explanation, classification, or mathematical derivation"
        private const val ENUMERATION_DESCRIPTOR =
            "a broad coverage request whose primary goal is to identify and explain multiple distinct relevant items, categories, cases, requirements, provisions, mechanisms, or alternatives rather than return one isolated fact"
        private const val COMPARISON_DESCRIPTOR =
            "a request asking to compare two or more things, explain their differences, similarities, advantages, disadvantages, or choose between alternatives"
        private const val PROCEDURE_DESCRIPTOR =
            "a request asking for a process, procedure, sequence of steps, workflow, method, instructions, or what to do next"
        private const val EXPLANATION_DESCRIPTOR =
            "a request whose primary goal is to understand a concept, rule, meaning, definition, rationale, method, basis, or how something is determined; it may discuss a formula or calculation method but does not itself ask for a numeric result from supplied operands"
        private const val MATH_CALCULATION_DESCRIPTOR =
            "a self-contained numerical computation request where the necessary operands are supplied by the user and the desired answer is a calculated numeric result; no policy rule, document fact, external knowledge, definition, or source lookup is required to perform the computation"
        private const val GENERAL_INTENT_DESCRIPTOR =
            "a general conversational or factual request that does not primarily ask for a calculation, a specific measured value, a list, a comparison, a procedure, or an explanation"

        private const val KB_INTEGRATED_ANSWER_DESCRIPTOR =
            "an integrated source-grounded answer that directly resolves the user's request by synthesizing all materially relevant supplied sources, combining corroborating information, distinguishing conflicts or conditions, and presenting the result as one coherent expert response rather than a source dump"
        private const val KB_PARTIAL_ANSWER_DESCRIPTOR =
            "an incomplete source-grounded response that gives only a fragment, isolated example, form/template language, vague pointer to another section, or one source's wording while failing to resolve the full user request even though relevant source material is available"
        private const val KB_SOURCE_DUMP_DESCRIPTOR =
            "a response that mainly walks through sources one by one, repeats document fragments, or exposes retrieval structure instead of integrating the material into a direct answer to the user"

        /*
         * LEGACY STATIC FRESHNESS ROUTER (disabled 2026-09-15).
         * Retained verbatim for rollback/reference only. It MUST NOT participate in
         * active routing because product routing is now semantic and capability-based.
         *         // AndroLLM-style deterministic capability safety net.
         *         //
         *         // The exact AndroLLM ToolRouter uses deterministic WEB terms before exposing the
         *         // planner. FixSight keeps that proven principle, but narrows it to capabilities
         *         // that FixSight actually owns. The semantic E5 boundary remains primary for
         *         // paraphrases and ambiguous requests; these rules only fast-path cases whose
         *         // capability requirement is explicit enough that sending them to a Dec-2024
         *         // parametric model would be predictably unsafe.
         *         //
         *         // IMPORTANT: this is not a generic topic router. Stable explanatory questions
         *         // such as "explain weather systems" or "what happened in 2011" remain eligible
         *         // for Local. The patterns below describe explicit retrieval intent, present/future
         *         // time, post-cutoff facts, or intrinsically live values.
         *         private val EXPLICIT_FRESHNESS_REQUEST = Regex(
         *             """(?i)\b(latest|newest|currently|today|tonight|yesterday|right\s+now|up[- ]?to[- ]?date|real[- ]?time|breaking|live\s+(?:update|updates|status|coverage|result|results)|most\s+recent|recent\s+(?:developments?|updates?|information|status|events?|changes?)|as\s+of\s+(?:today|now)|tomorrow|earlier\s+today|this\s+(?:morning|afternoon|evening|week|month|year)|last\s+(?:night|hour|day|week|month|year)|next\s+(?:hour|day|week|month|year)|at\s+the\s+moment)\b"""
         *         )
         * 
         *         // Explicit user request to use external information. This mirrors AndroLLM's
         *         // deterministic `search`, `look up`, `research`, `investigate` WEB routing.
         *         private val EXPLICIT_WEB_ACTION_REQUEST = Regex(
         *             """(?i)(?:^|\b)(?:search(?:\s+the)?\s+web|search\s+online|search\s+for|look\s+it\s+up|look\s+up(?:\s+online|\s+on\s+the\s+web)?|browse\s+(?:the\s+)?web|check\s+online|verify\s+online|google(?:\s+it|\s+this|\s+that|\s+for)?|research\s+(?:online|the\s+web)|investigate\s+(?:online|on\s+the\s+web))(?:\b|$)"""
         *         )
         * 
         *         // Intrinsically live information families copied from AndroLLM's WEB routing
         *         // philosophy, but expressed narrowly enough to avoid forcing timeless conceptual
         *         // questions to Cloud.
         *         private val LIVE_WEATHER_REQUEST = Regex(
         *             """(?i)(?:\b(?:weather|temperature)\s+(?:in|at|for|near)\b|\b(?:what(?:'s|\s+is)|how(?:'s|\s+is))\s+(?:the\s+)?weather\b|\bforecast(?:\s+for|\s+in|\s+at|\s+today|\s+tomorrow)?\b|\btemperature\s+outside\b)"""
         *         )
         *         private val LIVE_NEWS_REQUEST = Regex(
         *             """(?i)(?:\b(?:latest\s+)?news\s+(?:about|on|from|in|regarding)\b|\b(?:news|headlines)\s+(?:today|now|this\s+(?:morning|evening|week))\b|^(?:give|show|tell)\s+me\s+(?:the\s+)?(?:latest\s+)?(?:news|headlines)\b)"""
         *         )
         *         private val LIVE_PRICE_REQUEST = Regex(
         *             """(?i)\b(?:current\s+|latest\s+|today(?:'s)?\s+)?(?:price|prices|market\s+price|share\s+price|stock\s+price|exchange\s+rate|currency\s+rate|fuel\s+price|fare)\s+(?:of|for|between)\b"""
         *         )
         *         private val LIVE_STATUS_REQUEST = Regex(
         *             """(?i)(?:\b(?:flight|train|bus|service|server|website|network|order|delivery|application)\s+(?:live\s+)?status\b|\b(?:flight|train|bus)\s+(?:schedule|timetable|arrival|departure)\b|\b(?:schedule|timetable|arrival|departure)\s+(?:of|for)\s+(?:a\s+)?(?:flight|train|bus)\b|\b(?:outage|downtime|traffic\s+condition|opening\s+hours|availability)\s+(?:now|today|currently|near\s+me)\b|\b(?:open\s+now|near\s+me|nearest\s+(?:restaurant|hospital|hotel|pharmacy|store|shop|station|airport))\b)"""
         *         )
         *         private val LIVE_SPORT_REQUEST = Regex(
         *             """(?i)\b(?:live\s+score|current\s+score|score\s+today|latest\s+score|match\s+score|score\s+of\s+(?:the\s+)?match|standings|rankings|points\s+table|match\s+result\s+today)\b"""
         *         )
         *         private val LIVE_DISCOVERY_REQUEST = Regex(
         *             """(?i)\b(?:find\s+(?:the\s+)?cheapest|cheapest\s+(?:flight|hotel|fare|price|deal)|best\s+(?:price|deal)\s+(?:for|on)|available\s+(?:flights?|hotels?|tickets?)|restaurants?\s+near\s+me|hotels?\s+near\s+me|pharmacies\s+near\s+me)\b"""
         *         )
         * 
         *         // Gemma's reliable world-knowledge horizon is December 2024. A factual request
         *         // explicitly anchored to 2024 or later cannot be treated as safely contained in
         *         // the model's parametric memory. Creative/writing prompts are excluded so
         *         // "write a story set in 2026" remains Local.
         *         private val POST_CUTOFF_YEAR = Regex("""\b(202[4-9]|20[3-9]\d|2[1-9]\d{2})\b""")
         *         private val FACTUAL_REQUEST_SHAPE = Regex(
         *             """(?i)^(?:what|what's|who|who's|when|where|which|how\s+many|how\s+much|tell\s+me|give\s+me|show\s+me|is|are|did|does|has|have|was|were|list|report|summari[sz]e)\b"""
         *         )
         *         private val CREATIVE_OR_TRANSFORM_REQUEST = Regex(
         *             """(?i)^(?:write|draft|compose|create|invent|imagine|roleplay|rewrite|rephrase|translate|proofread|polish|make\s+up|generate\s+a\s+(?:story|poem|email|letter|caption|script))\b"""
         *         )
         * 
         *         // Office-holder / leadership questions are frequently phrased without words like
         *         // "current". "Who is the CEO/president...?" therefore needs fresh information,
         *         // while an explicitly historical "who was ... in 2010" remains semantic/local.
         *         private val PRESENT_ROLE_REQUEST = Regex(
         *             """(?i)^(?:who\s+is|who's|name)\b.{0,80}\b(?:president|prime\s+minister|chief\s+minister|governor|mayor|minister|ceo|chief\s+executive|chair(?:man|woman|person)?|head\s+of|captain|coach)\b"""
         *         )
         *         private val EXPLICIT_PRE_2024_HISTORY = Regex(
         *             """(?i)\b(?:in|during|as\s+of|on)\s+(?:19\d{2}|200\d|201\d|202[0-3])\b"""
         *         )
         * 
         *         private fun deterministicExternalReason(query: String): String? {
         *             val q = query.trim()
         *             if (q.isBlank()) return null
         *             if (EXPLICIT_WEB_ACTION_REQUEST.containsMatchIn(q)) return "explicit_web_action"
         *             if (EXPLICIT_FRESHNESS_REQUEST.containsMatchIn(q)) return "explicit_freshness"
         *             if (LIVE_WEATHER_REQUEST.containsMatchIn(q)) return "live_weather"
         *             if (LIVE_NEWS_REQUEST.containsMatchIn(q)) return "live_news"
         *             if (LIVE_PRICE_REQUEST.containsMatchIn(q)) return "live_price_or_rate"
         *             if (LIVE_STATUS_REQUEST.containsMatchIn(q)) return "live_status_or_availability"
         *             if (LIVE_SPORT_REQUEST.containsMatchIn(q)) return "live_score_or_ranking"
         *             if (LIVE_DISCOVERY_REQUEST.containsMatchIn(q)) return "live_discovery_or_availability"
         *             if (PRESENT_ROLE_REQUEST.containsMatchIn(q) && !EXPLICIT_PRE_2024_HISTORY.containsMatchIn(q)) {
         *                 return "present_role_holder"
         *             }
         *             if (!CREATIVE_OR_TRANSFORM_REQUEST.containsMatchIn(q) &&
         *                 FACTUAL_REQUEST_SHAPE.containsMatchIn(q) &&
         *                 POST_CUTOFF_YEAR.containsMatchIn(q)
         *             ) {
         *                 return "post_2024_fact"
         *             }
         *             return null
         *         }
         * 
         *         private fun explicitFreshnessRequested(query: String): Boolean =
         *             deterministicExternalReason(query) != null
         * 
         */

        private const val MIN_INTENT_SCORE = 0.74f
        private const val MIN_INTENT_MARGIN = 0.020f

        private const val MIN_SHORT_RESPONSE_RELEVANCE = 0.50f
        private const val MIN_RESPONSE_PROGRESS_MARGIN = 0.012f
        private const val RESTATEMENT_MARGIN = 0.020f
        private const val RESTATEMENT_MIN_QUERY_SIMILARITY = 0.70f
        private const val RESTATEMENT_MAX_CHARS = 180
        private const val MIN_EXTERNAL_SCORE = 0.78f
        private const val MIN_EXTERNAL_MARGIN = 0.040f
        // Symmetric confidence boundary. E5 may confidently choose stable/local or
        // fresh/external. Anything between those two high-confidence regions is
        // deliberately handed to the detached Gemma planner. No topic vocabulary or
        // direction-of-lean heuristic participates.
        private const val MIN_LOCAL_SCORE = 0.78f
        private const val MIN_LOCAL_MARGIN = 0.040f
        // The temporal axis is deliberately a little more permissive than the broad
        // external/local axis because it answers a narrower question: present-state vs
        // stable/fixed-time. This is semantic confidence, not keyword routing.
        private const val MIN_PRESENT_STATE_SCORE = 0.74f
        private const val MIN_PRESENT_STATE_MARGIN = 0.018f
        private const val MIN_STABLE_STATE_SCORE = 0.74f
        private const val MIN_STABLE_STATE_MARGIN = 0.018f
        // Semantic ensemble consensus. Freshness and stability are intentionally
        // asymmetric. A false "stable/local" decision is more damaging than one
        // small detached planner call because a pre-2025 model can otherwise present
        // stale world-state as current. These are score margins, not topic rules.
        // V26: freshness must be materially supported. Near-ties are ambiguity, not
        // permission to leave the local stack. Keep fresh/stable confidence symmetric.
        private const val MIN_FRESH_ENSEMBLE_AXIS_MARGIN = 0.008f
        private const val MIN_FRESH_ENSEMBLE_SUM_MARGIN = 0.030f
        private const val MIN_STABLE_ENSEMBLE_AXIS_MARGIN = 0.008f
        private const val MIN_STABLE_ENSEMBLE_SUM_MARGIN = 0.030f
        private const val MIN_RESPONSE_SCOPE_SCORE = 0.70f
        private const val MIN_RESPONSE_SCOPE_MARGIN = 0.012f
        private const val MIN_RISK_SCORE = 0.71f
        private const val MIN_RISK_MARGIN = 0.035f
        private const val MIN_VISUAL_SCORE = 0.70f
        private const val MIN_VISUAL_MARGIN = 0.018f
        // Camera relevance is contextual evidence admission, not an external action.
        // Keep the threshold permissive enough to rescue implicit physical troubleshooting,
        // while still requiring a material semantic advantage over camera-irrelevant requests.
        private const val MIN_CAMERA_EVIDENCE_SCORE = 0.70f
        private const val MIN_CAMERA_EVIDENCE_MARGIN = -0.018f
        private const val CAMERA_EVIDENCE_CONTEXT_MARGIN = 0.004f
        private const val CAMERA_EVIDENCE_CONTEXT_NEAR_MARGIN = -0.040f
        private const val MIN_HIGH_DETAIL_SCORE = 0.70f
        private const val MIN_HIGH_DETAIL_MARGIN = 0.020f
        private const val MIN_BROAD_VISUAL_SCORE = 0.70f
        private const val MIN_BROAD_VISUAL_MARGIN = 0.018f
        private const val MIN_SPATIAL_LOCALIZATION_SCORE = 0.72f
        // Spatial and general-visual requests are often semantic near-ties (for example,
        // "identify this component" needs both understanding and localization). Requiring
        // spatial to win by a positive margin suppresses valid AR requests. Instead,
        // admit a strong spatial score unless the general-visual interpretation wins
        // clearly by more than this small ambiguity band. Domain/topic keywords are not used.
        private const val MAX_GENERAL_VISUAL_ADVANTAGE_FOR_SPATIAL = 0.008f
        // AR permission is intentionally stricter than ordinary semantic routing because
        // it authorizes a visible side effect. Near-ties default to no AR.
        private const val MIN_AR_OVERLAY_SCORE = 0.78f
        private const val MIN_AR_OVERLAY_MARGIN = 0.040f
        private const val MAX_CONTEXTUAL_NO_AR_ADVANTAGE = 0.020f
        private const val CONTEXT_FOLLOWUP_MARGIN = 0.015f
        private const val STALE_CONTEXT_MARGIN = 0.035f
    }

    private val mutex = Mutex()
    @Volatile private var externalEmbedding: FloatArray? = null
    @Volatile private var localEmbedding: FloatArray? = null
    @Volatile private var presentStateEmbedding: FloatArray? = null
    @Volatile private var stableStateEmbedding: FloatArray? = null
    @Volatile private var volatileStateEmbedding: FloatArray? = null
    @Volatile private var durableStateEmbedding: FloatArray? = null
    @Volatile private var responseShortEmbedding: FloatArray? = null
    @Volatile private var responseNormalEmbedding: FloatArray? = null
    @Volatile private var responseDetailedEmbedding: FloatArray? = null
    @Volatile private var riskEmbedding: FloatArray? = null
    @Volatile private var safeEmbedding: FloatArray? = null
    @Volatile private var visualRequiredEmbedding: FloatArray? = null
    @Volatile private var nonVisualEmbedding: FloatArray? = null
    @Volatile private var cameraEvidenceRelevantEmbedding: FloatArray? = null
    @Volatile private var cameraEvidenceIrrelevantEmbedding: FloatArray? = null
    @Volatile private var highDetailVisualEmbedding: FloatArray? = null
    @Volatile private var generalVisualEmbedding: FloatArray? = null
    @Volatile private var broadVisualInventoryEmbedding: FloatArray? = null
    @Volatile private var focusedVisualEmbedding: FloatArray? = null
    @Volatile private var spatialLocalizationEmbedding: FloatArray? = null
    @Volatile private var generalVisualAnswerEmbedding: FloatArray? = null
    @Volatile private var arOverlayRequestEmbedding: FloatArray? = null
    @Volatile private var noArOverlayEmbedding: FloatArray? = null
    @Volatile private var arActionFollowupEmbedding: FloatArray? = null
    @Volatile private var visualReferenceFollowupEmbedding: FloatArray? = null
    @Volatile private var contextFollowupEmbedding: FloatArray? = null
    @Volatile private var standaloneRequestEmbedding: FloatArray? = null
    @Volatile private var kbEllipticalFollowupEmbedding: FloatArray? = null
    @Volatile private var kbSelfContainedSpecificEmbedding: FloatArray? = null
    @Volatile private var usefulResponseEmbedding: FloatArray? = null
    @Volatile private var directResponseEmbedding: FloatArray? = null
    @Volatile private var fillerResponseEmbedding: FloatArray? = null
    @Volatile private var restatementResponseEmbedding: FloatArray? = null
    @Volatile private var localAnswerSufficientEmbedding: FloatArray? = null
    @Volatile private var localAnswerInsufficientEmbedding: FloatArray? = null
    @Volatile private var valueLookupEmbedding: FloatArray? = null
    @Volatile private var enumerationEmbedding: FloatArray? = null
    @Volatile private var comparisonEmbedding: FloatArray? = null
    @Volatile private var procedureEmbedding: FloatArray? = null
    @Volatile private var explanationEmbedding: FloatArray? = null
    @Volatile private var mathCalculationEmbedding: FloatArray? = null
    @Volatile private var generalIntentEmbedding: FloatArray? = null
    @Volatile private var troubleshootingResolvedEmbedding: FloatArray? = null
    @Volatile private var troubleshootingContinueEmbedding: FloatArray? = null
    @Volatile private var kbIntegratedAnswerEmbedding: FloatArray? = null
    @Volatile private var kbPartialAnswerEmbedding: FloatArray? = null
    @Volatile private var kbSourceDumpEmbedding: FloatArray? = null
    @Volatile private var cachedQueryText: String = ""
    @Volatile private var cachedQueryEmbedding: FloatArray? = null

    suspend fun warmup() {
        if (!semanticEngine.isReady) return
        mutex.withLock {
            if (externalEmbedding == null) externalEmbedding = semanticEngine.embed("passage: $EXTERNAL_DESCRIPTOR")
            if (localEmbedding == null) localEmbedding = semanticEngine.embed("passage: $LOCAL_DESCRIPTOR")
            if (presentStateEmbedding == null) presentStateEmbedding = semanticEngine.embed("passage: $PRESENT_STATE_DESCRIPTOR")
            if (stableStateEmbedding == null) stableStateEmbedding = semanticEngine.embed("passage: $STABLE_STATE_DESCRIPTOR")
            if (volatileStateEmbedding == null) volatileStateEmbedding = semanticEngine.embed("passage: $VOLATILE_WORLD_STATE_DESCRIPTOR")
            if (durableStateEmbedding == null) durableStateEmbedding = semanticEngine.embed("passage: $DURABLE_KNOWLEDGE_DESCRIPTOR")
            if (riskEmbedding == null) riskEmbedding = semanticEngine.embed("passage: $HIGH_RISK_DESCRIPTOR")
            if (safeEmbedding == null) safeEmbedding = semanticEngine.embed("passage: $ROUTINE_SAFE_DESCRIPTOR")
            if (visualRequiredEmbedding == null) visualRequiredEmbedding = semanticEngine.embed("passage: $VISUAL_REQUIRED_DESCRIPTOR")
            if (nonVisualEmbedding == null) nonVisualEmbedding = semanticEngine.embed("passage: $NON_VISUAL_DESCRIPTOR")
            if (cameraEvidenceRelevantEmbedding == null) cameraEvidenceRelevantEmbedding = semanticEngine.embed("passage: $CAMERA_EVIDENCE_RELEVANT_DESCRIPTOR")
            if (cameraEvidenceIrrelevantEmbedding == null) cameraEvidenceIrrelevantEmbedding = semanticEngine.embed("passage: $CAMERA_EVIDENCE_IRRELEVANT_DESCRIPTOR")
            if (highDetailVisualEmbedding == null) highDetailVisualEmbedding = semanticEngine.embed("passage: $HIGH_DETAIL_VISUAL_DESCRIPTOR")
            if (generalVisualEmbedding == null) generalVisualEmbedding = semanticEngine.embed("passage: $GENERAL_VISUAL_DESCRIPTOR")
            if (broadVisualInventoryEmbedding == null) broadVisualInventoryEmbedding = semanticEngine.embed("passage: $BROAD_VISUAL_INVENTORY_DESCRIPTOR")
            if (focusedVisualEmbedding == null) focusedVisualEmbedding = semanticEngine.embed("passage: $FOCUSED_VISUAL_DESCRIPTOR")
            if (spatialLocalizationEmbedding == null) spatialLocalizationEmbedding = semanticEngine.embed("passage: $SPATIAL_LOCALIZATION_DESCRIPTOR")
            if (generalVisualAnswerEmbedding == null) generalVisualAnswerEmbedding = semanticEngine.embed("passage: $GENERAL_VISUAL_ANSWER_DESCRIPTOR")
            if (arOverlayRequestEmbedding == null) arOverlayRequestEmbedding = semanticEngine.embed("passage: $AR_OVERLAY_REQUEST_DESCRIPTOR")
            if (noArOverlayEmbedding == null) noArOverlayEmbedding = semanticEngine.embed("passage: $NO_AR_OVERLAY_DESCRIPTOR")
            if (arActionFollowupEmbedding == null) arActionFollowupEmbedding = semanticEngine.embed("passage: $AR_ACTION_FOLLOWUP_DESCRIPTOR")
            if (visualReferenceFollowupEmbedding == null) visualReferenceFollowupEmbedding = semanticEngine.embed("passage: $VISUAL_REFERENCE_FOLLOWUP_DESCRIPTOR")
            if (contextFollowupEmbedding == null) contextFollowupEmbedding = semanticEngine.embed("passage: $CONTEXT_FOLLOWUP_DESCRIPTOR")
            if (standaloneRequestEmbedding == null) standaloneRequestEmbedding = semanticEngine.embed("passage: $STANDALONE_REQUEST_DESCRIPTOR")
            if (usefulResponseEmbedding == null) usefulResponseEmbedding = semanticEngine.embed("passage: $USEFUL_RESPONSE_DESCRIPTOR")
            if (directResponseEmbedding == null) directResponseEmbedding = semanticEngine.embed("passage: $DIRECT_RESPONSE_DESCRIPTOR")
            if (fillerResponseEmbedding == null) fillerResponseEmbedding = semanticEngine.embed("passage: $FILLER_RESPONSE_DESCRIPTOR")
            if (restatementResponseEmbedding == null) restatementResponseEmbedding = semanticEngine.embed("passage: $RESTATEMENT_RESPONSE_DESCRIPTOR")
        }
        Log.i(TAG, "CAPABILITY_WARMUP complete=true")
    }

    private suspend fun embedCurrentQuery(text: String): FloatArray {
        val normalized = text.trim()
        val cached = cachedQueryEmbedding
        if (normalized == cachedQueryText && cached != null) return cached
        val emb = semanticEngine.embed("query: $normalized")
            ?: throw IllegalStateException("semantic_capability_embedding_failed")
        cachedQueryText = normalized
        cachedQueryEmbedding = emb
        return emb
    }

    /**
     * Classify answer intent semantically. No query-domain keyword list participates.
     * The same E5 query embedding is cached/reused by the rest of capability routing.
     */
    suspend fun classifyQueryIntent(query: String): SemanticQueryIntentResult? {
        val clean = query.trim()
        if (clean.isBlank()) return null
        if (!semanticEngine.isReady && !semanticEngine.initialise()) return null
        val q = embedCurrentQuery(clean)
        val refs = mutex.withLock {
            if (valueLookupEmbedding == null) valueLookupEmbedding = semanticEngine.embed("passage: $VALUE_LOOKUP_DESCRIPTOR")
            if (enumerationEmbedding == null) enumerationEmbedding = semanticEngine.embed("passage: $ENUMERATION_DESCRIPTOR")
            if (comparisonEmbedding == null) comparisonEmbedding = semanticEngine.embed("passage: $COMPARISON_DESCRIPTOR")
            if (procedureEmbedding == null) procedureEmbedding = semanticEngine.embed("passage: $PROCEDURE_DESCRIPTOR")
            if (explanationEmbedding == null) explanationEmbedding = semanticEngine.embed("passage: $EXPLANATION_DESCRIPTOR")
            if (mathCalculationEmbedding == null) mathCalculationEmbedding = semanticEngine.embed("passage: $MATH_CALCULATION_DESCRIPTOR")
            if (generalIntentEmbedding == null) generalIntentEmbedding = semanticEngine.embed("passage: $GENERAL_INTENT_DESCRIPTOR")
            listOf(
                SemanticQueryIntent.VALUE_LOOKUP to valueLookupEmbedding,
                SemanticQueryIntent.ENUMERATION to enumerationEmbedding,
                SemanticQueryIntent.COMPARISON to comparisonEmbedding,
                SemanticQueryIntent.PROCEDURE to procedureEmbedding,
                SemanticQueryIntent.EXPLANATION to explanationEmbedding,
                SemanticQueryIntent.MATH_CALCULATION to mathCalculationEmbedding,
                SemanticQueryIntent.GENERAL to generalIntentEmbedding
            )
        }
        val scored = refs.mapNotNull { (intent, emb) ->
            emb?.let { intent to semanticEngine.cosineSimilarity(q, it) }
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)?.second ?: 0f
        val confident = best.second >= MIN_INTENT_SCORE && (best.second - runnerUp) >= MIN_INTENT_MARGIN
        Log.i(TAG, "QUERY_INTENT intent=${best.first} score=${"%.4f".format(best.second)} runner=${"%.4f".format(runnerUp)} confident=$confident")
        return SemanticQueryIntentResult(best.first, best.second, runnerUp, confident)
    }

    /**
     * Semantically size an ordinary answer. This never chooses Local vs Cloud and
     * never uses topic keywords. Uncertain requests default to NORMAL.
     */
    suspend fun classifyResponseScope(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): SemanticResponseScopeResult? {
        val clean = query.trim()
        if (clean.isBlank()) return null
        if (!semanticEngine.isReady && !semanticEngine.initialise()) return null
        val q = embedCurrentQuery(clean)
        val refs: List<Pair<SemanticResponseScope, FloatArray?>> = mutex.withLock {
            if (responseShortEmbedding == null) responseShortEmbedding = semanticEngine.embed("passage: $RESPONSE_SHORT_DESCRIPTOR")
            if (responseNormalEmbedding == null) responseNormalEmbedding = semanticEngine.embed("passage: $RESPONSE_NORMAL_DESCRIPTOR")
            if (responseDetailedEmbedding == null) responseDetailedEmbedding = semanticEngine.embed("passage: $RESPONSE_DETAILED_DESCRIPTOR")
            listOf(
                SemanticResponseScope.SHORT to responseShortEmbedding,
                SemanticResponseScope.NORMAL to responseNormalEmbedding,
                SemanticResponseScope.DETAILED to responseDetailedEmbedding
            )
        }
        val scored: List<Pair<SemanticResponseScope, Float>> = refs.mapNotNull { pair ->
            val emb = pair.second ?: return@mapNotNull null
            pair.first to semanticEngine.cosineSimilarity(q, emb)
        }.sortedByDescending { pair -> pair.second }
        val best = scored.firstOrNull() ?: return null
        val runner = scored.getOrNull(1)?.second ?: 0f
        val confident = best.second >= MIN_RESPONSE_SCOPE_SCORE && (best.second - runner) >= MIN_RESPONSE_SCOPE_MARGIN
        val chosen = if (confident) best.first else SemanticResponseScope.NORMAL
        Log.i(TAG, "RESPONSE_SCOPE scope=$chosen raw=${best.first} score=${"%.4f".format(best.second)} runner=${"%.4f".format(runner)} confident=$confident")
        return SemanticResponseScopeResult(chosen, best.second, runner, confident)
    }

    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? = null

    override suspend fun isOperational(): Boolean {
        if (semanticEngine.isReady) return true
        val ready = semanticEngine.initialise()
        if (ready) warmup()
        return ready
    }

    override suspend fun requiresVerifiedGrounding(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    private fun recentContext(
        acceptedHistory: List<AndroChatMessage>,
        query: String,
        maxMessages: Int = 4
    ): String = buildString {
        acceptedHistory.takeLast(maxMessages).forEach { message ->
            append(if (message.role == "assistant") "Assistant: " else "User: ")
            append(message.content.trim()).append('\n')
        }
        append("User: ").append(query.trim())
    }.trim()

    private suspend fun isContextDependentTurn(
        queryEmbedding: FloatArray,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (acceptedHistory.isEmpty()) return false
        val refs = mutex.withLock {
            if (contextFollowupEmbedding == null) contextFollowupEmbedding = semanticEngine.embed("passage: $CONTEXT_FOLLOWUP_DESCRIPTOR")
            if (standaloneRequestEmbedding == null) standaloneRequestEmbedding = semanticEngine.embed("passage: $STANDALONE_REQUEST_DESCRIPTOR")
            contextFollowupEmbedding to standaloneRequestEmbedding
        }
        val followup = refs.first ?: return false
        val standalone = refs.second ?: return false
        val followupScore = semanticEngine.cosineSimilarity(queryEmbedding, followup)
        val standaloneScore = semanticEngine.cosineSimilarity(queryEmbedding, standalone)
        val dependent = followupScore >= standaloneScore + CONTEXT_FOLLOWUP_MARGIN
        Log.i(
            TAG,
            "CONTEXT_ROUTE followup=${"%.4f".format(followupScore)}/${"%.4f".format(standaloneScore)} dependent=$dependent"
        )
        return dependent
    }

    /**
     * Reuse the same semantic query embedding used by capability routing to decide
     * whether a turn depends on recent conversation context. This adds no second query
     * embedding when normal capability routing runs later in the same turn.
     */
    suspend fun isContextDependentForRecall(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank() || acceptedHistory.isEmpty() || !semanticEngine.isReady) return false
        return isContextDependentTurn(embedCurrentQuery(query), acceptedHistory)
    }

    /**
     * Selected-KB continuation classifier. It preserves the existing generic semantic
     * follow-up decision, then adds a KB-only semantic check for elliptical requests
     * whose subject/object is omitted because it was established by the preceding user
     * goal. No domain/topic keywords are used.
     */
    suspend fun isContextDependentForKnowledgeBase(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank() || acceptedHistory.none { it.role.equals("user", true) }) return false
        if (!semanticEngine.isReady && !semanticEngine.initialise()) return false
        val clean = query.trim()
        // A complete interrogative that explicitly names its subject should not inherit
        // the previous KB goal merely because the embedding sits near the elliptical
        // boundary. This is linguistic structure only; no domain vocabulary is used.
        // Pronoun/deictic subjects (it/this/that/one/they...) remain eligible follow-ups.
        val explicitSubjectQuestion = run {
            val m = Regex(
                "(?i)^(?:what|who|where|when|why|how)\\s+(?:is|are|was|were|do|does|did|can|could|should|would|will|means?)\\s+(.+?)[?.!]*$"
            ).find(clean)
            val subject = m?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val first = Regex("[\\p{L}\\p{N}._/-]+").find(subject)?.value?.lowercase().orEmpty()
            first.isNotBlank() && first !in setOf(
                "it", "this", "that", "these", "those", "they", "them", "one", "ones",
                "he", "she", "there", "here", "same", "former", "latter"
            )
        }
        if (explicitSubjectQuestion) {
            Log.i(TAG, "KB_CONTEXT_ROUTE structuralSelfContained=true dependent=false")
            return false
        }

        val q = embedCurrentQuery(clean)
        if (isContextDependentTurn(q, acceptedHistory)) return true
        val refs = mutex.withLock {
            if (kbEllipticalFollowupEmbedding == null) {
                kbEllipticalFollowupEmbedding = semanticEngine.embed("passage: $KB_ELLIPTICAL_FOLLOWUP_DESCRIPTOR")
            }
            if (kbSelfContainedSpecificEmbedding == null) {
                kbSelfContainedSpecificEmbedding = semanticEngine.embed("passage: $KB_SELF_CONTAINED_SPECIFIC_DESCRIPTOR")
            }
            kbEllipticalFollowupEmbedding to kbSelfContainedSpecificEmbedding
        }
        val elliptical = refs.first ?: return false
        val selfContained = refs.second ?: return false
        val ellipticalScore = semanticEngine.cosineSimilarity(q, elliptical)
        val selfContainedScore = semanticEngine.cosineSimilarity(q, selfContained)
        val dependent = ellipticalScore >= selfContainedScore + 0.010f
        Log.i(
            TAG,
            "KB_CONTEXT_ROUTE elliptical=${"%.4f".format(ellipticalScore)}/${"%.4f".format(selfContainedScore)} dependent=$dependent"
        )
        return dependent
    }

    /**
     * Episode-aware continuation check.  Unlike the generic follow-up-shape
     * classifier, this compares the new turn with the actual unresolved Cloud
     * sub-problem and the last Cloud question.  Null means semantic state is not
     * available and the caller must preserve the episode conservatively rather
     * than interpreting infrastructure failure as a topic change.
     */
    suspend fun isRelatedToEscalationEpisode(
        query: String,
        episodeGoal: String,
        lastCloudQuestion: String
    ): Boolean? {
        val clean = query.trim()
        val context = listOf(episodeGoal.trim(), lastCloudQuestion.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(1_600)
        if (clean.isBlank() || context.isBlank()) return false
        if (!semanticEngine.isReady && !semanticEngine.initialise()) return null
        return try {
            val q = embedCurrentQuery(clean)
            val c = semanticEngine.embed("passage: $context") ?: return null
            val relatedScore = semanticEngine.cosineSimilarity(q, c)
            val genericDependent = isContextDependentTurn(
                q,
                listOf(
                    AndroChatMessage(
                        id = Long.MAX_VALUE,
                        role = "assistant",
                        content = context,
                        timestampMs = System.currentTimeMillis()
                    )
                )
            )
            val related = relatedScore >= 0.69f || (genericDependent && relatedScore >= 0.61f)
            Log.i(TAG, "ESCALATION_CONTEXT_ROUTE relatedScore=${"%.4f".format(relatedScore)} genericDependent=$genericDependent related=$related")
            related
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "ESCALATION_CONTEXT_ROUTE_FAILED ${t.javaClass.simpleName}:${t.message}")
            null
        }
    }


    /**
     * Warm only the semantic state required for a future conversational attachment
     * continuation. This is deliberately background-only and is scheduled after an
     * attachment turn has already been accepted, so it never delays the current
     * answer. No query-specific rule or keyword is involved.
     */
    suspend fun assessTroubleshootingResolution(
        query: String,
        recentAssistant: String = ""
    ): SemanticTroubleshootingResolutionAssessment {
        val clean = query.trim()
        if (clean.isBlank()) return SemanticTroubleshootingResolutionAssessment(false, 0f, 1f)
        if (!semanticEngine.isReady && !semanticEngine.initialise()) {
            return SemanticTroubleshootingResolutionAssessment(false, 0f, 1f)
        }
        return try {
            val contextual = buildString {
                append(clean)
                if (recentAssistant.isNotBlank()) {
                    append("\nRecent troubleshooting assistant turn: ")
                    append(recentAssistant.trim().take(700))
                }
            }
            val q = semanticEngine.embed("query: $contextual")
                ?: return SemanticTroubleshootingResolutionAssessment(false, 0f, 1f)
            val refs = mutex.withLock {
                if (troubleshootingResolvedEmbedding == null) {
                    troubleshootingResolvedEmbedding = semanticEngine.embed("passage: $TROUBLESHOOTING_RESOLVED_DESCRIPTOR")
                }
                if (troubleshootingContinueEmbedding == null) {
                    troubleshootingContinueEmbedding = semanticEngine.embed("passage: $TROUBLESHOOTING_CONTINUE_DESCRIPTOR")
                }
                troubleshootingResolvedEmbedding to troubleshootingContinueEmbedding
            }
            val resolvedRef = refs.first
                ?: return SemanticTroubleshootingResolutionAssessment(false, 0f, 1f)
            val continueRef = refs.second
                ?: return SemanticTroubleshootingResolutionAssessment(false, 0f, 1f)
            val resolvedScore = semanticEngine.cosineSimilarity(q, resolvedRef)
            val continueScore = semanticEngine.cosineSimilarity(q, continueRef)
            // Conservative semantic boundary: a real resolution confirmation must beat
            // the continue/uncertain interpretation by a meaningful margin. No keyword list.
            val resolved = resolvedScore >= 0.74f && resolvedScore >= continueScore + 0.018f
            Log.i(TAG, "TROUBLESHOOTING_RESOLUTION_SEMANTIC resolved=${"%.4f".format(resolvedScore)}/${"%.4f".format(continueScore)} decision=$resolved")
            SemanticTroubleshootingResolutionAssessment(resolved, resolvedScore, continueScore)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "TROUBLESHOOTING_RESOLUTION_SEMANTIC_FAILED ${t.javaClass.simpleName}:${t.message}")
            SemanticTroubleshootingResolutionAssessment(false, 0f, 1f)
        }
    }

    fun scheduleAttachmentContinuationPrewarm() {
        if (!continuationPrewarmInFlight.compareAndSet(false, true)) return
        continuationPrewarmScope.launch {
            try {
                if (!semanticEngine.isReady) semanticEngine.initialise()
                if (!semanticEngine.isReady) return@launch
                mutex.withLock {
                    if (contextFollowupEmbedding == null) {
                        contextFollowupEmbedding = semanticEngine.embed("passage: $CONTEXT_FOLLOWUP_DESCRIPTOR")
                    }
                    if (standaloneRequestEmbedding == null) {
                        standaloneRequestEmbedding = semanticEngine.embed("passage: $STANDALONE_REQUEST_DESCRIPTOR")
                    }
                }
                Log.i(TAG, "ATTACHMENT_CONTINUATION_PREWARM ready=true")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "ATTACHMENT_CONTINUATION_PREWARM_FAILED ${t.message}")
            } finally {
                continuationPrewarmInFlight.set(false)
            }
        }
    }

    suspend fun assessExternalCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): SemanticExternalCapabilityAssessment {
        if (query.isBlank()) {
            return SemanticExternalCapabilityAssessment(
                required = false,
                ambiguousExternalLean = false,
                currentExternalScore = 0f,
                currentLocalScore = 0f
            )
        }
        if (!semanticEngine.isReady && !semanticEngine.initialise()) {
            throw IllegalStateException("semantic_capability_unavailable")
        }

        data class RouteScores(
            val external: Float,
            val local: Float,
            val present: Float,
            val stable: Float,
            val volatile: Float,
            val durable: Float
        )

        suspend fun score(text: String): RouteScores {
            val q = if (text.trim() == query.trim()) embedCurrentQuery(query)
            else semanticEngine.embed("query: ${text.trim()}")
                ?: throw IllegalStateException("semantic_capability_embedding_failed")
            val refs = mutex.withLock {
                if (externalEmbedding == null) externalEmbedding = semanticEngine.embed("passage: $EXTERNAL_DESCRIPTOR")
                if (localEmbedding == null) localEmbedding = semanticEngine.embed("passage: $LOCAL_DESCRIPTOR")
                if (presentStateEmbedding == null) presentStateEmbedding = semanticEngine.embed("passage: $PRESENT_STATE_DESCRIPTOR")
                if (stableStateEmbedding == null) stableStateEmbedding = semanticEngine.embed("passage: $STABLE_STATE_DESCRIPTOR")
                if (volatileStateEmbedding == null) volatileStateEmbedding = semanticEngine.embed("passage: $VOLATILE_WORLD_STATE_DESCRIPTOR")
                if (durableStateEmbedding == null) durableStateEmbedding = semanticEngine.embed("passage: $DURABLE_KNOWLEDGE_DESCRIPTOR")
                listOf(externalEmbedding, localEmbedding, presentStateEmbedding, stableStateEmbedding, volatileStateEmbedding, durableStateEmbedding)
            }
            return RouteScores(
                external = semanticEngine.cosineSimilarity(q, refs[0] ?: error("semantic_external_descriptor_failed")),
                local = semanticEngine.cosineSimilarity(q, refs[1] ?: error("semantic_local_descriptor_failed")),
                present = semanticEngine.cosineSimilarity(q, refs[2] ?: error("semantic_present_descriptor_failed")),
                stable = semanticEngine.cosineSimilarity(q, refs[3] ?: error("semantic_stable_descriptor_failed")),
                volatile = semanticEngine.cosineSimilarity(q, refs[4] ?: error("semantic_volatile_descriptor_failed")),
                durable = semanticEngine.cosineSimilarity(q, refs[5] ?: error("semantic_durable_descriptor_failed"))
            )
        }

        val currentEmbedding = embedCurrentQuery(query)
        val current = score(query)
        val contextDependent = try {
            isContextDependentTurn(currentEmbedding, acceptedHistory)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
        val contextual = if (contextDependent) score(recentContext(acceptedHistory, query)) else null

        fun axisDeltas(x: RouteScores): List<Float> = listOf(
            x.external - x.local,
            x.present - x.stable,
            x.volatile - x.durable
        )

        fun semanticDecision(x: RouteScores): Int {
            val deltas = axisDeltas(x)
            val freshVotes = deltas.count { it >= MIN_FRESH_ENSEMBLE_AXIS_MARGIN }
            val stableVotes = deltas.count { it <= -MIN_STABLE_ENSEMBLE_AXIS_MARGIN }
            val sum = deltas.sum()
            return when {
                freshVotes >= 2 && sum >= MIN_FRESH_ENSEMBLE_SUM_MARGIN -> 1
                // Stable ownership requires materially stronger agreement. Borderline
                // negative deltas are treated as ambiguous and resolved by the existing
                // detached epistemic planner rather than silently authorizing Local.
                stableVotes >= 2 && sum <= -MIN_STABLE_ENSEMBLE_SUM_MARGIN -> -1
                else -> 0
            }
        }

        val currentDecision = semanticDecision(current)
        val contextDecision = contextual?.let(::semanticDecision) ?: 0
        val required = currentDecision == 1 || contextDecision == 1
        val confidentlyStable = !required && (currentDecision == -1 || contextDecision == -1)
        val ambiguous = !required && !confidentlyStable

        val currentFreshSignal = axisDeltas(current).sum()
        val contextFreshSignal = contextual?.let { axisDeltas(it).sum() } ?: Float.NEGATIVE_INFINITY
        val semanticFreshLean = maxOf(currentFreshSignal, contextFreshSignal) > 0f

        Log.i(
            TAG,
            "EXTERNAL current=${"%.4f".format(current.external)}/${"%.4f".format(current.local)} " +
                "temporal=${"%.4f".format(current.present)}/${"%.4f".format(current.stable)} " +
                "volatility=${"%.4f".format(current.volatile)}/${"%.4f".format(current.durable)} " +
                "context=${"%.4f".format(contextual?.external ?: 0f)}/${"%.4f".format(contextual?.local ?: 0f)} " +
                "contextTemporal=${"%.4f".format(contextual?.present ?: 0f)}/${"%.4f".format(contextual?.stable ?: 0f)} " +
                "contextVolatility=${"%.4f".format(contextual?.volatile ?: 0f)}/${"%.4f".format(contextual?.durable ?: 0f)} " +
                "decision=$currentDecision/$contextDecision required=$required ambiguous=$ambiguous freshLean=$semanticFreshLean router=semantic_ensemble"
        )
        return SemanticExternalCapabilityAssessment(
            required = required,
            ambiguousExternalLean = ambiguous,
            currentExternalScore = current.external,
            currentLocalScore = current.local,
            contextualExternalScore = contextual?.external ?: 0f,
            contextualLocalScore = contextual?.local ?: 0f,
            presentStateScore = current.present,
            stableStateScore = current.stable,
            contextualPresentStateScore = contextual?.present ?: 0f,
            contextualStableStateScore = contextual?.stable ?: 0f,
            volatileStateScore = current.volatile,
            durableStateScore = current.durable,
            contextualVolatileStateScore = contextual?.volatile ?: 0f,
            contextualDurableStateScore = contextual?.durable ?: 0f,
            semanticFreshLean = semanticFreshLean
        )
    }

    override suspend fun requiresExternalCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = assessExternalCapability(query, acceptedHistory).required

    override suspend fun requiresSafetyEscalation(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        if (!semanticEngine.isReady) {
            throw IllegalStateException("semantic_safety_unavailable")
        }

        // Reuse the exact current-turn embedding already computed by external routing.
        // This removes a second ONNX query pass from every ordinary turn.
        val q = embedCurrentQuery(query)

        val (risk, safe) = mutex.withLock {
            if (riskEmbedding == null) {
                riskEmbedding = semanticEngine.embed("passage: $HIGH_RISK_DESCRIPTOR")
            }
            if (safeEmbedding == null) {
                safeEmbedding = semanticEngine.embed("passage: $ROUTINE_SAFE_DESCRIPTOR")
            }
            riskEmbedding to safeEmbedding
        }

        val r = risk ?: throw IllegalStateException("semantic_risk_descriptor_failed")
        val s = safe ?: throw IllegalStateException("semantic_safe_descriptor_failed")

        val riskScore = semanticEngine.cosineSimilarity(q, r)
        val safeScore = semanticEngine.cosineSimilarity(q, s)
        val required = riskScore >= MIN_RISK_SCORE &&
            (riskScore - safeScore) >= MIN_RISK_MARGIN

        Log.i(
            TAG,
            "SAFETY risk=${"%.4f".format(riskScore)} safe=${"%.4f".format(safeScore)} required=$required"
        )
        return required
    }

    override suspend fun requiresFactualVerification(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    override suspend fun requiresVisualCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        if (!semanticEngine.isReady) throw IllegalStateException("semantic_visual_unavailable")

        val q = embedCurrentQuery(query)
        val (visual, nonVisual) = mutex.withLock {
            if (visualRequiredEmbedding == null) visualRequiredEmbedding = semanticEngine.embed("passage: $VISUAL_REQUIRED_DESCRIPTOR")
            if (nonVisualEmbedding == null) nonVisualEmbedding = semanticEngine.embed("passage: $NON_VISUAL_DESCRIPTOR")
            visualRequiredEmbedding to nonVisualEmbedding
        }
        val v = visual ?: throw IllegalStateException("semantic_visual_descriptor_failed")
        val n = nonVisual ?: throw IllegalStateException("semantic_nonvisual_descriptor_failed")
        val visualScore = semanticEngine.cosineSimilarity(q, v)
        val nonVisualScore = semanticEngine.cosineSimilarity(q, n)
        val required = visualScore >= MIN_VISUAL_SCORE && (visualScore - nonVisualScore) >= MIN_VISUAL_MARGIN
        Log.i(
            TAG,
            "VISUAL_REQUIRED visual=${"%.4f".format(visualScore)} nonVisual=${"%.4f".format(nonVisualScore)} required=$required"
        )
        return required
    }

    override suspend fun isCurrentCameraEvidenceRelevant(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        if (!semanticEngine.isReady) throw IllegalStateException("semantic_camera_evidence_unavailable")

        val q = embedCurrentQuery(query)
        val (relevant, irrelevant) = mutex.withLock {
            if (cameraEvidenceRelevantEmbedding == null) {
                cameraEvidenceRelevantEmbedding = semanticEngine.embed("passage: $CAMERA_EVIDENCE_RELEVANT_DESCRIPTOR")
            }
            if (cameraEvidenceIrrelevantEmbedding == null) {
                cameraEvidenceIrrelevantEmbedding = semanticEngine.embed("passage: $CAMERA_EVIDENCE_IRRELEVANT_DESCRIPTOR")
            }
            cameraEvidenceRelevantEmbedding to cameraEvidenceIrrelevantEmbedding
        }
        val r = relevant ?: throw IllegalStateException("semantic_camera_relevant_descriptor_failed")
        val i = irrelevant ?: throw IllegalStateException("semantic_camera_irrelevant_descriptor_failed")
        val relevantScore = semanticEngine.cosineSimilarity(q, r)
        val irrelevantScore = semanticEngine.cosineSimilarity(q, i)
        val directMargin = relevantScore - irrelevantScore
        val directRequired = relevantScore >= MIN_CAMERA_EVIDENCE_SCORE &&
            directMargin >= MIN_CAMERA_EVIDENCE_MARGIN

        // Short follow-ups can omit the physical subject established by the previous
        // turn. Use recent accepted context only as a recovery signal when the current
        // query is already semantically near the camera-evidence boundary. This avoids
        // making stale conversation context turn unrelated questions into camera turns.
        var contextualRequired = false
        var contextRelevantScore = 0f
        var contextIrrelevantScore = 0f
        if (!directRequired && acceptedHistory.isNotEmpty() &&
            relevantScore >= MIN_CAMERA_EVIDENCE_SCORE &&
            directMargin >= CAMERA_EVIDENCE_CONTEXT_NEAR_MARGIN
        ) {
            val contextText = recentContext(acceptedHistory, query, maxMessages = 3)
            val contextEmbedding = semanticEngine.embed("query: $contextText")
            if (contextEmbedding != null) {
                contextRelevantScore = semanticEngine.cosineSimilarity(contextEmbedding, r)
                contextIrrelevantScore = semanticEngine.cosineSimilarity(contextEmbedding, i)
                contextualRequired = contextRelevantScore >= MIN_CAMERA_EVIDENCE_SCORE &&
                    (contextRelevantScore - contextIrrelevantScore) >= CAMERA_EVIDENCE_CONTEXT_MARGIN
            }
        }
        val required = directRequired || contextualRequired
        Log.i(
            TAG,
            "CAMERA_EVIDENCE relevant=${"%.4f".format(relevantScore)} irrelevant=${"%.4f".format(irrelevantScore)} " +
                "margin=${"%.4f".format(directMargin)} context=${"%.4f".format(contextRelevantScore)}/${"%.4f".format(contextIrrelevantScore)} required=$required"
        )
        return required
    }

    override suspend fun requiresHighDetailVisualCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        if (!semanticEngine.isReady) throw IllegalStateException("semantic_visual_detail_unavailable")

        val q = embedCurrentQuery(query)
        val (highDetail, general) = mutex.withLock {
            if (highDetailVisualEmbedding == null) {
                highDetailVisualEmbedding = semanticEngine.embed("passage: $HIGH_DETAIL_VISUAL_DESCRIPTOR")
            }
            if (generalVisualEmbedding == null) {
                generalVisualEmbedding = semanticEngine.embed("passage: $GENERAL_VISUAL_DESCRIPTOR")
            }
            highDetailVisualEmbedding to generalVisualEmbedding
        }
        val h = highDetail ?: throw IllegalStateException("semantic_high_detail_descriptor_failed")
        val g = general ?: throw IllegalStateException("semantic_general_visual_descriptor_failed")
        val highDetailScore = semanticEngine.cosineSimilarity(q, h)
        val generalScore = semanticEngine.cosineSimilarity(q, g)
        val required = highDetailScore >= MIN_HIGH_DETAIL_SCORE &&
            (highDetailScore - generalScore) >= MIN_HIGH_DETAIL_MARGIN
        Log.i(
            TAG,
            "VISUAL_DETAIL high=${"%.4f".format(highDetailScore)} general=${"%.4f".format(generalScore)} required=$required"
        )
        return required
    }


    override suspend fun requiresBroadVisualInventoryCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        if (!semanticEngine.isReady) throw IllegalStateException("semantic_visual_inventory_unavailable")
        val q = embedCurrentQuery(query)
        val (broad, focused) = mutex.withLock {
            if (broadVisualInventoryEmbedding == null) {
                broadVisualInventoryEmbedding = semanticEngine.embed("passage: $BROAD_VISUAL_INVENTORY_DESCRIPTOR")
            }
            if (focusedVisualEmbedding == null) {
                focusedVisualEmbedding = semanticEngine.embed("passage: $FOCUSED_VISUAL_DESCRIPTOR")
            }
            broadVisualInventoryEmbedding to focusedVisualEmbedding
        }
        val b = broad ?: throw IllegalStateException("semantic_broad_visual_descriptor_failed")
        val f = focused ?: throw IllegalStateException("semantic_focused_visual_descriptor_failed")
        val broadScore = semanticEngine.cosineSimilarity(q, b)
        val focusedScore = semanticEngine.cosineSimilarity(q, f)
        val required = broadScore >= MIN_BROAD_VISUAL_SCORE &&
            (broadScore - focusedScore) >= MIN_BROAD_VISUAL_MARGIN
        Log.i(TAG, "VISUAL_SCOPE broad=${"%.4f".format(broadScore)} focused=${"%.4f".format(focusedScore)} inventory=$required")
        return required
    }

    override suspend fun requiresSpatialLocalizationCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        if (!semanticEngine.isReady) throw IllegalStateException("semantic_spatial_localization_unavailable")
        // Short visual follow-ups such as "where is it?" or "highlight it" are
        // incomplete without the object accepted on the preceding turn. Include a
        // bounded recent context window in the semantic decision; the AR locator
        // still uses the current frame as the only coordinate source.
        val q = embedCurrentQuery(contextualVisualFollowUpQuery(query, acceptedHistory))
        val (spatial, general) = mutex.withLock {
            if (spatialLocalizationEmbedding == null) spatialLocalizationEmbedding = semanticEngine.embed("passage: $SPATIAL_LOCALIZATION_DESCRIPTOR")
            if (generalVisualAnswerEmbedding == null) generalVisualAnswerEmbedding = semanticEngine.embed("passage: $GENERAL_VISUAL_ANSWER_DESCRIPTOR")
            if (arOverlayRequestEmbedding == null) arOverlayRequestEmbedding = semanticEngine.embed("passage: $AR_OVERLAY_REQUEST_DESCRIPTOR")
            if (noArOverlayEmbedding == null) noArOverlayEmbedding = semanticEngine.embed("passage: $NO_AR_OVERLAY_DESCRIPTOR")
            spatialLocalizationEmbedding to generalVisualAnswerEmbedding
        }
        val sp = spatial ?: throw IllegalStateException("semantic_spatial_localization_descriptor_failed")
        val ge = general ?: throw IllegalStateException("semantic_general_visual_answer_descriptor_failed")
        val spatialScore = semanticEngine.cosineSimilarity(q, sp)
        val generalScore = semanticEngine.cosineSimilarity(q, ge)
        val generalAdvantage = generalScore - spatialScore
        val required = spatialScore >= MIN_SPATIAL_LOCALIZATION_SCORE &&
            generalAdvantage <= MAX_GENERAL_VISUAL_ADVANTAGE_FOR_SPATIAL
        Log.i(
            TAG,
            "SPATIAL_LOCALIZATION spatial=${"%.4f".format(spatialScore)} " +
                "general=${"%.4f".format(generalScore)} generalAdvantage=${"%.4f".format(generalAdvantage)} " +
                "required=$required gate=semantic_ambiguity_band"
        )
        return required
    }


    override suspend fun requiresArOverlayCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        if (!semanticEngine.isReady) throw IllegalStateException("semantic_ar_overlay_unavailable")
        // Preserve the explicit per-turn AR permission boundary. Only inject
        // recent conversation into this decision when the CURRENT query is itself
        // semantically context-dependent. Merely having chat history must never turn
        // a standalone troubleshooting request into an AR action.
        val rawQueryEmbedding = embedCurrentQuery(query.trim())
        val contextDependent = isContextDependentTurn(rawQueryEmbedding, acceptedHistory)
        val routedQuery = if (contextDependent) {
            contextualVisualFollowUpQuery(query, acceptedHistory)
        } else {
            query.trim()
        }
        val q = if (routedQuery == query.trim()) rawQueryEmbedding else embedCurrentQuery(routedQuery)
        val (overlay, normalVisual) = mutex.withLock {
            if (arOverlayRequestEmbedding == null) arOverlayRequestEmbedding = semanticEngine.embed("passage: $AR_OVERLAY_REQUEST_DESCRIPTOR")
            if (noArOverlayEmbedding == null) noArOverlayEmbedding = semanticEngine.embed("passage: $NO_AR_OVERLAY_DESCRIPTOR")
            arOverlayRequestEmbedding to noArOverlayEmbedding
        }
        val ar = overlay ?: throw IllegalStateException("semantic_ar_overlay_descriptor_failed")
        val noAr = normalVisual ?: throw IllegalStateException("semantic_no_ar_overlay_descriptor_failed")
        val arScore = semanticEngine.cosineSimilarity(q, ar)
        val noArScore = semanticEngine.cosineSimilarity(q, noAr)
        val margin = arScore - noArScore

        // A contextual follow-up gets the small ambiguity allowance only when the CURRENT
        // message itself is semantically closer to continuing an AR action than to simply
        // referring to/asking about a visible object. This prevents turns such as
        // "what is this?" from inheriting an old overlay intent while preserving natural
        // action continuations such as "highlight it". No static action-word list is used.
        val (actionFollowup, referenceFollowup) = mutex.withLock {
            if (arActionFollowupEmbedding == null) {
                arActionFollowupEmbedding = semanticEngine.embed("passage: $AR_ACTION_FOLLOWUP_DESCRIPTOR")
            }
            if (visualReferenceFollowupEmbedding == null) {
                visualReferenceFollowupEmbedding = semanticEngine.embed("passage: $VISUAL_REFERENCE_FOLLOWUP_DESCRIPTOR")
            }
            arActionFollowupEmbedding to visualReferenceFollowupEmbedding
        }
        val actionFollowupScore = actionFollowup?.let { semanticEngine.cosineSimilarity(rawQueryEmbedding, it) } ?: 0f
        val referenceFollowupScore = referenceFollowup?.let { semanticEngine.cosineSimilarity(rawQueryEmbedding, it) } ?: 1f
        val contextualArAction =
            contextDependent &&
                query.trim().length <= 96 &&
                acceptedHistory.isNotEmpty() &&
                actionFollowupScore >= MIN_AR_OVERLAY_SCORE &&
                actionFollowupScore >= referenceFollowupScore

        val required = arScore >= MIN_AR_OVERLAY_SCORE && (
            if (contextualArAction) margin >= -MAX_CONTEXTUAL_NO_AR_ADVANTAGE
            else margin >= MIN_AR_OVERLAY_MARGIN
        )
        Log.i(
            TAG,
            "AR_OVERLAY_PERMISSION ar=${"%.4f".format(arScore)} noAr=${"%.4f".format(noArScore)} " +
                "margin=${"%.4f".format(margin)} allowed=$required " +
                "contextDependent=$contextDependent actionFollowup=${"%.4f".format(actionFollowupScore)} " +
                "referenceFollowup=${"%.4f".format(referenceFollowupScore)} " +
                "gate=${if (contextualArAction) "contextual_ar_action" else "strict_semantic_action"}"
        )
        return required
    }

    private fun contextualVisualFollowUpQuery(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): String {
        val clean = query.trim()
        if (clean.length > 96 || acceptedHistory.isEmpty()) return clean
        val recent = acceptedHistory.takeLast(4).joinToString(" ") { message ->
            "${message.role}: ${message.content.trim().take(240)}"
        }.trim()
        if (recent.isBlank()) return clean
        return "$clean\nRecent conversation context for resolving the visible target: $recent"
    }

    /**
     * Lightweight provider-neutral seatbelt for suspiciously short model output.
     * AndroBaselineRuntime invokes this only when an answer shape warrants extra
     * scrutiny, so ordinary healthy local turns do not pay another semantic pass.
     * This uses the same E5 engine already loaded for capability routing and does
     * not retrieve or enable the ANM knowledge base.
     */
    override suspend fun validate(
        query: String,
        answer: String,
        provider: AndroBaselineRuntime.Provider,
        attachments: List<BaselineContextAttachment>,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult {
        val cleanQuery = query.trim()
        val cleanAnswer = FixSightOutputSanitizer.sanitize(answer).trim()
        if (cleanQuery.isBlank() || cleanAnswer.isBlank()) {
            return BaselineValidationResult.reject("blank_query_or_answer")
        }
        if (!semanticEngine.isReady) return BaselineValidationResult.ACCEPT

        val queryEmbedding = try {
            embedCurrentQuery(cleanQuery)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return BaselineValidationResult.ACCEPT
        }
        val answerEmbedding = semanticEngine.embed("passage: $cleanAnswer")
            ?: return BaselineValidationResult.ACCEPT

        val refs = mutex.withLock {
            if (usefulResponseEmbedding == null) usefulResponseEmbedding = semanticEngine.embed("passage: $USEFUL_RESPONSE_DESCRIPTOR")
            if (directResponseEmbedding == null) directResponseEmbedding = semanticEngine.embed("passage: $DIRECT_RESPONSE_DESCRIPTOR")
            if (fillerResponseEmbedding == null) fillerResponseEmbedding = semanticEngine.embed("passage: $FILLER_RESPONSE_DESCRIPTOR")
            if (restatementResponseEmbedding == null) restatementResponseEmbedding = semanticEngine.embed("passage: $RESTATEMENT_RESPONSE_DESCRIPTOR")
            if (contextFollowupEmbedding == null) contextFollowupEmbedding = semanticEngine.embed("passage: $CONTEXT_FOLLOWUP_DESCRIPTOR")
            if (standaloneRequestEmbedding == null) standaloneRequestEmbedding = semanticEngine.embed("passage: $STANDALONE_REQUEST_DESCRIPTOR")
            arrayOf(
                usefulResponseEmbedding,
                directResponseEmbedding,
                fillerResponseEmbedding,
                restatementResponseEmbedding,
                contextFollowupEmbedding,
                standaloneRequestEmbedding
            )
        }

        val useful = refs[0]?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val direct = refs[1]?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val filler = refs[2]?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val restatement = refs[3]?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val followupScore = refs[4]?.let { semanticEngine.cosineSimilarity(queryEmbedding, it) } ?: 0f
        val standaloneScore = refs[5]?.let { semanticEngine.cosineSimilarity(queryEmbedding, it) } ?: 0f
        val contextDependent = acceptedHistory.isNotEmpty() &&
            followupScore >= standaloneScore + CONTEXT_FOLLOWUP_MARGIN

        val currentRelevance = semanticEngine.cosineSimilarity(queryEmbedding, answerEmbedding)
        var contextualRelevance = currentRelevance
        var previousRelevance = 0f

        if (acceptedHistory.isNotEmpty()) {
            if (contextDependent) {
                val contextText = recentContext(acceptedHistory, cleanQuery, maxMessages = 4)
                val contextEmbedding = semanticEngine.embed("query: $contextText")
                if (contextEmbedding != null) contextualRelevance = semanticEngine.cosineSimilarity(contextEmbedding, answerEmbedding)
            } else {
                val priorText = buildString {
                    acceptedHistory.takeLast(4).forEach { message ->
                        append(if (message.role == "assistant") "Assistant: " else "User: ")
                        append(message.content.trim()).append('\n')
                    }
                }.trim()
                if (priorText.isNotBlank()) {
                    val priorEmbedding = semanticEngine.embed("query: $priorText")
                    if (priorEmbedding != null) previousRelevance = semanticEngine.cosineSimilarity(priorEmbedding, answerEmbedding)
                }
            }
        }

        val relevance = if (contextDependent) maxOf(currentRelevance, contextualRelevance) else currentRelevance
        val staleContext = !contextDependent && acceptedHistory.isNotEmpty() &&
            previousRelevance >= currentRelevance + STALE_CONTEXT_MARGIN

        // Do not require high query/answer cosine for every healthy answer: a joke,
        // a calculation result, a translation, or a creative response can be correct
        // without lexically/semantically resembling the request. The adequacy seatbelt
        // is therefore only applied to the short-output failure family we observed
        // ("Okay!", "Yes!", tiny fragments). Stale-topic detection remains active
        // for every answer regardless of length.
        val suspiciouslyShort = cleanAnswer.length <= 96
        val shortAdequate = !suspiciouslyShort ||
            ((relevance >= MIN_SHORT_RESPONSE_RELEVANCE || direct > filler + 0.03f) &&
                maxOf(useful, direct) + MIN_RESPONSE_PROGRESS_MARGIN >= filler)

        // Generic semantic mirror/echo gate. This is intentionally language- and
        // domain-neutral: no query words, topics, diagnoses, appliances, or other
        // static keyword tables participate. It targets the observed failure family
        // where Gemma turns "How to ...?" into "How do you ...?" and stops there.
        val restatementOnly =
            cleanAnswer.length <= RESTATEMENT_MAX_CHARS &&
            currentRelevance >= RESTATEMENT_MIN_QUERY_SIMILARITY &&
            restatement >= maxOf(useful, direct) + RESTATEMENT_MARGIN &&
            restatement >= filler

        val accepted = shortAdequate && !staleContext && !restatementOnly

        Log.i(
            TAG,
            "ANSWER_SEMANTIC_GATE provider=$provider current=${"%.4f".format(currentRelevance)} " +
                "contextual=${"%.4f".format(contextualRelevance)} previous=${"%.4f".format(previousRelevance)} " +
                "followup=${"%.4f".format(followupScore)}/${"%.4f".format(standaloneScore)} " +
                "contextDependent=$contextDependent stale=$staleContext short=$suspiciouslyShort " +
                "useful=${"%.4f".format(useful)} direct=${"%.4f".format(direct)} " +
                "filler=${"%.4f".format(filler)} restatement=${"%.4f".format(restatement)} " +
                "restatementOnly=$restatementOnly accepted=$accepted"
        )
        return when {
            staleContext -> BaselineValidationResult.reject("stale_previous_topic")
            restatementOnly -> BaselineValidationResult.reject("semantic_restatement_without_progress")
            !shortAdequate -> BaselineValidationResult.reject("short_answer_low_adequacy")
            else -> BaselineValidationResult.ACCEPT
        }
    }


    /**
     * Provider-neutral post-Local capability check. This does not verify factual truth;
     * it detects the narrower failure family where Local itself exposes that it could
     * not perform the requested task. It is semantic-only and contains no topic list.
     */
    suspend fun assessLocalAnswerSufficiency(
        query: String,
        answer: String
    ): SemanticLocalAnswerSufficiency? {
        if (query.isBlank() || answer.isBlank()) return null
        if (!semanticEngine.isReady && !semanticEngine.initialise()) return null
        val composite = semanticEngine.embed(
            "passage: User request: ${query.trim().take(1_500)}\nAssistant response: ${answer.trim().take(2_500)}"
        ) ?: return null
        val refs = mutex.withLock {
            if (localAnswerSufficientEmbedding == null) {
                localAnswerSufficientEmbedding = semanticEngine.embed("passage: $LOCAL_ANSWER_SUFFICIENT_DESCRIPTOR")
            }
            if (localAnswerInsufficientEmbedding == null) {
                localAnswerInsufficientEmbedding = semanticEngine.embed("passage: $LOCAL_ANSWER_INSUFFICIENT_DESCRIPTOR")
            }
            localAnswerSufficientEmbedding to localAnswerInsufficientEmbedding
        }
        val good = refs.first ?: return null
        val bad = refs.second ?: return null
        val goodScore = semanticEngine.cosineSimilarity(composite, good)
        val badScore = semanticEngine.cosineSimilarity(composite, bad)
        val sufficient = badScore < goodScore + 0.010f
        Log.i(
            TAG,
            "LOCAL_SUFFICIENCY sufficient=$sufficient good=${"%.4f".format(goodScore)} bad=${"%.4f".format(badScore)}"
        )
        return SemanticLocalAnswerSufficiency(sufficient, goodScore, badScore)
    }

    /**
     * Generic knowledge-answer adequacy gate. This is semantic-only: it does not
     * inspect procurement terms, acronyms, query keywords, or domain vocabularies.
     * It judges whether the answer integrates the supplied source material and
     * actually resolves the user's request rather than returning a fragment/template.
     */
    suspend fun validateKnowledgeAnswerSemantically(
        query: String,
        answer: String,
        evidence: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult? {
        if (query.isBlank() || answer.isBlank() || evidence.isBlank()) return null
        if (!semanticEngine.isReady && !semanticEngine.initialise()) return null

        val queryEmbedding = embedCurrentQuery(query)
        val answerEmbedding = semanticEngine.embed("passage: ${answer.trim()}") ?: return null
        val evidenceEmbedding = semanticEngine.embed("passage: ${evidence.trim().take(3_200)}") ?: return null

        // Latency hardening: the prior gate embedded a second query+answer composite even
        // though query relevance is already measured directly and the answer itself is
        // the object being classified as integrated/partial/source-dump. Reuse the single
        // answer embedding for shape references and keep a separate evidence embedding for
        // factual-grounding affinity. This removes one full post-generation ONNX pass
        // without weakening source/query checks or changing any routing decision.
        val refs = mutex.withLock {
            if (kbIntegratedAnswerEmbedding == null) kbIntegratedAnswerEmbedding = semanticEngine.embed("passage: $KB_INTEGRATED_ANSWER_DESCRIPTOR")
            if (kbPartialAnswerEmbedding == null) kbPartialAnswerEmbedding = semanticEngine.embed("passage: $KB_PARTIAL_ANSWER_DESCRIPTOR")
            if (kbSourceDumpEmbedding == null) kbSourceDumpEmbedding = semanticEngine.embed("passage: $KB_SOURCE_DUMP_DESCRIPTOR")
            arrayOf(kbIntegratedAnswerEmbedding, kbPartialAnswerEmbedding, kbSourceDumpEmbedding)
        }
        val integrated = refs[0]?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val partial = refs[1]?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val sourceDump = refs[2]?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val queryRelevance = semanticEngine.cosineSimilarity(queryEmbedding, answerEmbedding)
        val evidenceSupport = semanticEngine.cosineSimilarity(evidenceEmbedding, answerEmbedding)
        val answerOutcomeRefs = mutex.withLock {
            if (localAnswerSufficientEmbedding == null) localAnswerSufficientEmbedding = semanticEngine.embed("passage: $LOCAL_ANSWER_SUFFICIENT_DESCRIPTOR")
            if (localAnswerInsufficientEmbedding == null) localAnswerInsufficientEmbedding = semanticEngine.embed("passage: $LOCAL_ANSWER_INSUFFICIENT_DESCRIPTOR")
            localAnswerSufficientEmbedding to localAnswerInsufficientEmbedding
        }
        val fulfilledScore = answerOutcomeRefs.first?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val insufficientScore = answerOutcomeRefs.second?.let { semanticEngine.cosineSimilarity(answerEmbedding, it) } ?: 0f
        val groundedNonAnswer = insufficientScore >= fulfilledScore + 0.002f && insufficientScore >= 0.70f

        // Structured requests (procedure/comparison/enumeration) require the answer to
        // look more like an integrated resolution than a partial/source-dump response.
        // This is task-shape logic only: no domain vocabulary participates.
        val semanticIntent = runCatching { classifyQueryIntent(query)?.intent }.getOrNull()
        val structuredRequest = semanticIntent in setOf(
            SemanticQueryIntent.PROCEDURE,
            SemanticQueryIntent.COMPARISON,
            SemanticQueryIntent.ENUMERATION
        )

        val fragmentFailure = if (structuredRequest) {
            partial >= integrated + 0.002f
        } else {
            partial >= integrated + 0.012f
        }
        val dumpFailure = if (structuredRequest) {
            sourceDump >= integrated
        } else {
            sourceDump >= integrated + 0.018f
        }
        // A selected-KB answer has two independent obligations: answer the user's
        // request AND remain supported by the supplied evidence. Failing either side
        // is enough to reject; using && here previously allowed a query-relevant but
        // weakly grounded answer (or a grounded but poorly resolving answer) to pass.
        val weakResolution = queryRelevance < 0.48f || evidenceSupport < 0.50f
        val accepted = !groundedNonAnswer && !fragmentFailure && !dumpFailure && !weakResolution

        Log.i(
            TAG,
            "KB_ANSWER_SEMANTIC_GATE integrated=${"%.4f".format(integrated)} partial=${"%.4f".format(partial)} " +
                "dump=${"%.4f".format(sourceDump)} queryRel=${"%.4f".format(queryRelevance)} " +
                "evidence=${"%.4f".format(evidenceSupport)} fulfilled=${"%.4f".format(fulfilledScore)} " +
                "insufficient=${"%.4f".format(insufficientScore)} nonAnswer=$groundedNonAnswer accepted=$accepted"
        )
        return when {
            groundedNonAnswer -> BaselineValidationResult.reject("generic_kb_semantic_non_answer")
            fragmentFailure -> BaselineValidationResult.reject("generic_kb_semantic_partial_answer")
            dumpFailure -> BaselineValidationResult.reject("generic_kb_semantic_source_dump")
            weakResolution -> BaselineValidationResult.reject("generic_kb_semantic_low_resolution")
            else -> BaselineValidationResult.ACCEPT
        }
    }

}
