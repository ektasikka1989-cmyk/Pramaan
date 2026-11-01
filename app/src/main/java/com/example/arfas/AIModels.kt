package com.example.arfas

import com.google.ar.core.Anchor
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// FEATURE FLAGS
// ─────────────────────────────────────────────────────────────────────────────

const val ENABLE_DUAL_AI_ENGINE = false

// ─────────────────────────────────────────────────────────────────────────────
// A1 — SESSION TRANSCRIPT LAYER
// The complete audit trail of everything that happened each turn.
// Never stored to disk — lives in RAM only, cleared on session end.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SessionTranscript — replaces raw conversationHistory strings.
 * Per-turn records give full attribution of which layer answered what.
 */
data class SessionTranscript(
    val sessionId:   String           = UUID.randomUUID().toString(),
    val startTime:   Long             = System.currentTimeMillis(),
    val turns:       MutableList<TurnRecord> = mutableListOf(),
    val liveSummary: LiveSummary      = LiveSummary(),
    var rca:         RCAReport?       = null
)

enum class TurnOutcome { PENDING, ACCEPTED, SAFE_FALLBACK, ERROR }

data class TurnRecord(
    val turnNumber:  Int,
    val timestamp:   Long             = System.currentTimeMillis(),
    val userSaid:    String,
    val cameraActive: Boolean         = false,
    var mlKit:       MLKitTurnResult  = MLKitTurnResult(),
    var kb:          KBTurnResult     = KBTurnResult(),
    var local:       LocalTurnResult  = LocalTurnResult(),
    var cloud:       CloudTurnResult  = CloudTurnResult(),
    var synthesis:   SynthesisResult  = SynthesisResult(),
    var finalAnswer: String           = "",
    var answeredBy:  AnswerSource     = AnswerSource.LOCAL,
    var outcome:     TurnOutcome      = TurnOutcome.PENDING,
    // Authoritative per-turn memory delta used by the RCA builder.
    var notebookUpdate: NotebookUpdate? = null
)

data class MLKitTurnResult(
    val ocr:           String          = "",
    val objects:       List<String>    = emptyList(),
    val barcode:       String          = "",
    val answeredFully: Boolean         = false
)

data class KBTurnResult(
    val domain:        String          = KBDomain.GENERAL,
    val sopsFound:     Int             = 0,
    val topSopId:      String          = "",
    val topSimilarity: Float           = 0f,
    val sops:          List<SopRecord> = emptyList()
)

data class LocalTurnResult(
    val tried:         Boolean         = false,
    val answer:        String          = "",
    val confidence:    Float           = 0f,
    val cloudBrief:    String          = "",   // Job B output — sent to cloud
    val timeMs:        Long            = 0
)

data class CloudTurnResult(
    val called:        Boolean         = false,
    val brief:         String          = "",   // what was sent to cloud (Job B brief)
    val answer:        String          = "",   // what cloud returned
    val timeMs:        Long            = 0
)

data class SynthesisResult(
    val rawCloudAnswer: String         = "",
    val removedContent: String         = "",   // what was filtered as already said
    val finalAnswer:    String         = ""    // what user actually hears
)


enum class VoiceControlIntent { NONE, CAMERA_ON, CAMERA_OFF, END_SESSION }

enum class AnswerSource {
    ML_KIT,           // barcode / OCR answered fully
    KB_DIRECT,        // SOP read directly (both models unavailable)
    LOCAL,            // Local LLM Job A, high confidence
    CLOUD,            // Cloud answered (local failed / offline)
    LOCAL_PLUS_CLOUD, // Local started, Cloud enriched, Local synthesised
    OFFLINE_FALLBACK  // Offline message
}

enum class ObservationSource {
    USER,         // User-reported claim or symptom — not visual verification
    ML_KIT,       // Deterministic on-device — no hallucination possible
    LOCAL_MODEL,  // Gemma3-1B-IT on-device — text-only
    CLOUD,        // Groq — complex reasoning
    MANUAL
}

enum class NotebookEvidenceType {
    USER_STATEMENT,
    OBSERVATION,
    VISUAL_CONFLICT,
    CONFIRMED_FACT,
    ACTION,
    ACTION_RESULT,
    KB_EVIDENCE,
    THEORY,
    RESOLUTION,
    OTHER
}

data class NotebookEntry(
    val content: String,
    val source: ObservationSource,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val type: NotebookEvidenceType = NotebookEvidenceType.OTHER,
    val provenanceId: String = ""
)

data class ActionEvidence(
    val action: String,
    var status: ActionStatus = ActionStatus.PENDING,
    val suggestedAt: Long = System.currentTimeMillis(),
    var resolvedAt: Long = 0L,
    var outcome: String = "",
    val provenanceTurn: Int = 0
)

data class RoutingSignals(
    var localConfidence: Float    = 0f,
    var retrievalScore: Float     = 0f,
    var hasSafetyFlag: Boolean    = false,
    var unresolvedHypotheses: Int = 0
)

data class NotebookUpdate(
    val fact: String = "",
    val theory: String = "",
    val goal: String = "",
    val checked: String = "",
    val action: String = "",
    val rejected: String = "",
    val problem: String = "",
    val userGoal: String = "",
    val currentGoal: String = "",
    val primaryTheory: String = "",
    val secondaryTheory: String = "",
    val currentAction: String = "",
    val actionStatus: ActionStatus = ActionStatus.PENDING,
    val lastKnownVisualState: String = "",
    val confirmedFacts: List<String> = emptyList(),
    val visualFindings: List<String> = emptyList(),
    val checksPerformed: List<String> = emptyList(),
    val actionsSuggested: List<String> = emptyList(),
    val rejectedTheories: List<String> = emptyList(),
    val questionsAlreadyAsked: List<String> = emptyList(),
    val visualSummary: String = "",   // Pre-turn: what the camera sees (synthesised by Job D)
    val intent: String = ""            // Pre-turn: what the user is trying to do
)

// ─────────────────────────────────────────────────────────────────────────────
// A1 — LIVE SUMMARY
// Compact 3-line brief updated by Local LLM Job D after every turn.
// This is what goes into the next turn's context packet — NOT full history.
// ─────────────────────────────────────────────────────────────────────────────

data class LiveSummary(
    var patientContext:   String                     = "",
    val confirmedFacts:   MutableList<String>        = mutableListOf(),  // max 12
    val ruledOut:         MutableList<String>        = mutableListOf(),  // max 6
    val openQuestions:    MutableList<String>        = mutableListOf(),  // max 6
    val activeProtocols:  MutableList<ActiveProtocol> = mutableListOf(),
    var languageMode:     LanguageMode               = LanguageMode.ENGLISH
) {
    /**
     * Renders the LiveSummary as the compact 3-line brief that goes into
     * every context packet. This is A1's core context compression.
     */
    fun toContextBrief(): String = buildString {
        if (patientContext.isNotBlank())
            appendLine(patientContext)
        if (confirmedFacts.isNotEmpty())
            appendLine("Done: ${confirmedFacts.takeLast(4).joinToString(". ")}")
        if (activeProtocols.isNotEmpty()) {
            val p = activeProtocols.last()
            append("Protocol: ${p.sopTitle} Step ${p.currentStep}/${p.totalSteps}.")
            if (openQuestions.isNotEmpty())
                append(" Open: ${openQuestions.first()}")
            appendLine()
        } else if (openQuestions.isNotEmpty()) {
            appendLine("Open: ${openQuestions.joinToString(". ")}")
        }
    }.trim()

    /**
     * Renders last N exchanges from TurnRecord list for the context packet.
     * A1 spec: LAST 3 EXCHANGES only.
     */
    fun lastExchanges(turns: List<TurnRecord>, n: Int = 3): String = buildString {
        turns.takeLast(n).forEach { t ->
            if (t.userSaid.isNotBlank()) appendLine("User: ${t.userSaid}")
            if (t.finalAnswer.isNotBlank()) appendLine("AI: ${t.finalAnswer}")
        }
    }.trim()
}

data class ActiveProtocol(
    val sopId:          String,
    val sopTitle:       String,
    val source:         String,
    var currentStep:    Int                       = 1,
    val totalSteps:     Int                       = 1,
    val completedSteps: MutableList<Int>          = mutableListOf(),
    val stepNotes:      MutableMap<Int, String>   = mutableMapOf()
)

// ─────────────────────────────────────────────────────────────────────────────
// A1 — KNOWLEDGE BASE DOMAIN
// KB searched ONLY when domain is classified as non-GENERAL.
// Any domain can have a knowledge base — not restricted to ANM/health.
// ─────────────────────────────────────────────────────────────────────────────

object KBDomain {
    const val GENERAL    = "GENERAL"    // KB NOT searched
    const val TECH       = "TECH"       // generic IT/device troubleshooting
    const val HEALTH     = "HEALTH"     // ANM SOPs searched (future)
    const val AUTOMOTIVE = "AUTOMOTIVE" // automotive knowledge pack
    const val INDUSTRIAL = "INDUSTRIAL" // industrial / HVAC knowledge pack
    const val CUSTOM     = "CUSTOM"     // user-installed knowledge pack

    // Legacy metadata retained for future evidence-policy reintroduction; not used by the baseline runtime.

}

// ─────────────────────────────────────────────────────────────────────────────
// EXISTING MODELS — unchanged, kept for compatibility
// ─────────────────────────────────────────────────────────────────────────────

enum class AppState {
    IDLE, LISTENING, RECORDING, THINKING, SPEAKING,
    WAITING, WAITING_FOR_USER, CONFIRM_HIGHLIGHT, GUIDING,
    ROOT_CAUSE_FOUND, INVESTIGATION_COMPLETE
}

enum class SessionState {
    IDLE, ACTIVE_INVESTIGATION, RESOLUTION_CANDIDATE,
    RESOLUTION_PENDING, SESSION_COMPLETE
}

enum class ProblemType {
    TROUBLESHOOTING, SETUP_INSTALLATION, CONFIGURATION,
    LEARNING_EXPLANATION, NAVIGATION, PHYSICAL_REPAIR, UNKNOWN
}

enum class ActionStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

enum class InvestigationStage {
    START, UNDERSTAND_PROBLEM, COLLECT_INITIAL_VISUAL,
    FORM_INITIAL_THEORY, VERIFY, TEST_COMPONENT,
    ROOT_CAUSE_FOUND, GUIDE_REPAIR, VERIFY_REPAIR, COMPLETE
}

enum class WaitingState {
    NONE, WAITING_FOR_VOICE, WAITING_FOR_IMAGE,
    WAITING_FOR_CONFIRMATION, WAITING_FOR_ACTION,
    WAITING_FOR_MEASUREMENT, WAITING_FOR_CAMERA_MOVEMENT
}

enum class ExpectedInput { NONE, VOICE, IMAGE, CONFIRMATION, ACTION, MEASUREMENT }

enum class ImageFreshness { NEW, UNCHANGED, STALE }

enum class MarkerMode { AR_ANCHORED, IMAGE_GROUNDED_2D, MANUAL_SCREEN_FALLBACK }

data class NormalizedBBox(
    val x: Float, val y: Float, val w: Float, val h: Float
) {
    fun smooth(target: NormalizedBBox, alpha: Float = 0.4f) = NormalizedBBox(
        x = this.x + alpha * (target.x - this.x),
        y = this.y + alpha * (target.y - this.y),
        w = this.w + alpha * (target.w - this.w),
        h = this.h + alpha * (target.h - this.h)
    )
}

enum class ARAnchorType { OBJECT_ANCHOR, SCREEN_ANCHOR, WORLD_ANCHOR }

data class VisualMarker(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    var bbox: NormalizedBBox,
    var type: ARMarkerType = ARMarkerType.BOX_HIGHLIGHT,
    var color: ARColor = ARColor.GREEN,
    var mode: MarkerMode,
    var anchor: Anchor? = null,
    var anchorType: ARAnchorType = ARAnchorType.SCREEN_ANCHOR,
    var isLocked: Boolean = false,
    var detectionCount: Int = 1,
    var lastSeen: Long = System.currentTimeMillis(),
    var visible: Boolean = true,
    var identityKey: String? = null,
    // Perspective reference for anchored BOX/CIRCLE overlays. These values are
    // initialized on the first tracked frame so marker size changes naturally
    // as camera-to-anchor distance changes instead of remaining screen-fixed.
    var referenceDistanceMeters: Float? = null,
    var referenceBoxWidth: Float? = null,
    var referenceBoxHeight: Float? = null
)

data class InvestigationProgress(
    val problemUnderstood: Boolean        = false,
    val initialEvidenceCollected: Boolean = false,
    val theoryFormed: Boolean             = false,
    val rootCauseSuspected: Boolean       = false,
    val rootCauseConfirmed: Boolean       = false,
    val solutionValidated: Boolean        = false
)

data class VisualStateSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val description: String,
    val indicators: Map<String, String> = emptyMap(),
    val actionTaken: String? = null
)

data class VisualNotebook(
    var currentScene: String = "",
    val detectedObjects: MutableList<String>         = mutableListOf(),
    val observedConditions: MutableList<String>      = mutableListOf(),
    val importantVisualEvidence: MutableList<String> = mutableListOf(),
    var lastVisualTimestamp: Long = 0
)

/**
 * InvestigationNotebook — session memory.
 * In A1, this is updated primarily by Local LLM Job D via LiveSummary.
 */
/**
 * Provider-neutral immutable view of one FixSight turn.
 *
 * This does not replace Notebook, KB, camera or conversation history. It is only
 * the stable hand-off contract between FixSight orchestration and whichever
 * reasoning provider (Local Gemma or Cloud) executes the turn.
 */
data class TurnContextSnapshot(
    val userQuery: String,
    val troubleshooting: Boolean,
    val kbSelected: Boolean,
    val visionActive: Boolean,
    val notebookContext: String = "",
    val recentConversation: String = "",
    val knowledgeEvidence: String = "",
    val attachmentEvidence: String = "",
    val currentVisualEvidence: String = "",
    val networkAvailable: Boolean = false
) {
    fun renderForReasoning(maxChars: Int = 8_000): String = buildString {
        appendLine("<fixsight_turn_context>")
        appendLine("mode=${if (troubleshooting) "TROUBLESHOOTING" else "CONVERSATION"}")
        appendLine("kb_selected=$kbSelected")
        appendLine("vision_active=$visionActive")
        if (notebookContext.isNotBlank()) {
            appendLine("<notebook>")
            appendLine(notebookContext.trim())
            appendLine("</notebook>")
        }
        if (recentConversation.isNotBlank()) {
            appendLine("<recent_turns>")
            appendLine(recentConversation.trim())
            appendLine("</recent_turns>")
        }
        if (knowledgeEvidence.isNotBlank()) {
            appendLine("<knowledge_evidence>")
            appendLine(knowledgeEvidence.trim())
            appendLine("</knowledge_evidence>")
        }
        if (attachmentEvidence.isNotBlank()) {
            appendLine("<attachment_evidence>")
            appendLine(attachmentEvidence.trim())
            appendLine("</attachment_evidence>")
        }
        if (currentVisualEvidence.isNotBlank()) {
            appendLine("<current_visual_evidence>")
            appendLine(currentVisualEvidence.trim())
            appendLine("</current_visual_evidence>")
        }
        appendLine("</fixsight_turn_context>")
    }.take(maxChars)
}

enum class EscalationReason {
    VISUAL_LOCAL_INSUFFICIENT,
    LOCAL_INFERENCE_FAILURE,
    FRESH_INFORMATION,
    SOURCE_GROUNDED_SYNTHESIS,
    SAFETY,
    DEVICE_CAPABILITY,
    USER_REQUESTED_CLOUD,
    OTHER
}

data class EscalationEpisode(
    val reason: EscalationReason,
    val startedAtTurnEpochMs: Long,
    val active: Boolean = true,
    val dependentTurnsUsed: Int = 0,
    val maxDependentTurns: Int = 8,
    val episodeGoal: String = "",
    val lastCloudQuestion: String = "",
    val dependencyUnknownTurns: Int = 0
)

data class InvestigationNotebook(
    var problem: String              = "",
    var userGoal: String             = "",
    var currentGoal: String          = "",
    var nextGoal: String             = "",
    val confirmedFacts: MutableList<String>         = mutableListOf(),
    val visualFindings: MutableList<String>         = mutableListOf(),
    val checksPerformed: MutableList<String>        = mutableListOf(),
    val actionsSuggested: MutableList<String>       = mutableListOf(),
    val userResponses: MutableList<String>          = mutableListOf(),

    var problemType: ProblemType     = ProblemType.UNKNOWN,
    var primaryTheory: String        = "",
    var secondaryTheory: String      = "",
    val rejectedTheories: MutableList<String>       = mutableListOf(),
    val questionsAlreadyAsked: MutableList<String>  = mutableListOf(),
    var currentAction: String        = "",
    var actionStatus: ActionStatus   = ActionStatus.PENDING,

    val visualTimeline: MutableList<VisualStateSnapshot> = mutableListOf(),
    var lastKnownVisualState: String = "",
    val visualNotebook: VisualNotebook = VisualNotebook(),

    var lastUserObservation: String  = "",
    var conversationSummary: String  = "",
    var forcedHighlightTarget: String? = null,
    var languageMode: LanguageMode   = LanguageMode.ENGLISH,

    val typedEntries: MutableList<NotebookEntry>    = mutableListOf(),
    val actionEvidence: MutableList<ActionEvidence>  = mutableListOf(),
    val routingSignals: RoutingSignals              = RoutingSignals(),

    // ML Kit on-device results
    var mlKitOcrText: String                        = "",
    val mlKitDetectedObjects: MutableList<String>   = mutableListOf(),
    var mlKitBarcodeValue: String                   = "",

    var lastAnswerSource: ObservationSource         = ObservationSource.CLOUD,
    var lastAnswerOffline: Boolean                  = false,

    // User reports and visual conflicts remain separate from confirmed facts so
    // a conversational claim cannot silently override a current camera frame.
    val userStatements: MutableList<String>         = mutableListOf(),
    val visualConflicts: MutableList<String>        = mutableListOf()
)

enum class ARAction { HIGHLIGHT, POINT, LABEL, NONE }
enum class ARMarkerType { BOX_HIGHLIGHT, CIRCLE_HIGHLIGHT, ARROW_POINTER, TEXT_LABEL, INSTRUCTION_CARD }
enum class ARColor { RED, GREEN, YELLOW, BLUE }

data class ARIntent(
    val action: ARAction           = ARAction.NONE,
    val targetObject: String       = "",
    val type: ARMarkerType         = ARMarkerType.BOX_HIGHLIGHT,
    val color: ARColor             = ARColor.BLUE,
    val labelText: String?         = null,
    val direction: String?         = null
)

data class DetectedObject(
    val label: String,
    val bbox: NormalizedBBox,
    val confidence: Float = 1.0f,
    // Optional dimensions of the image in which bbox was predicted. When known,
    // VisualEngine maps image-normalized coordinates through the camera preview's
    // center-crop geometry before ARCore hit-testing.
    val sourceImageWidth: Int = 0,
    val sourceImageHeight: Int = 0
)

data class InvestigationConfidence(val confidence: Int? = 0)

data class ConversationTurn(
    val lastUserMessage: String?   = null,
    val lastAIMessage: String?     = null,
    val pendingQuestion: String?   = null,
    val expectedInput: ExpectedInput? = ExpectedInput.NONE
)

data class VisionSnapshot(
    val frameId: String?           = UUID.randomUUID().toString(),
    val imageHash: String?         = null,
    val freshness: ImageFreshness? = ImageFreshness.NEW,
    val uploadRequired: Boolean?   = true,
    val compressionQuality: Int?   = 80,
    val regionOfInterest: NormalizedBBox? = null,
    val changeScore: Float?        = 1.0f
)

data class NotebookDelta(
    val newFacts: List<String>?       = emptyList(),
    val removedFacts: List<String>?   = emptyList(),
    val newChecks: List<String>?      = emptyList(),
    val completedChecks: List<String>? = emptyList(),
    val updatedTheory: String?        = null,
    val updatedConfidence: Int?       = null
)

data class TechnicianDecision(
    val observation: String?          = null,
    val reasoningSummary: String?     = null,
    val nextQuestion: String?         = null,
    val repairInstruction: String?    = null,
    val confidence: Int?              = 0,
    val expectedInput: ExpectedInput? = ExpectedInput.VOICE,
    val waitingState: WaitingState?   = WaitingState.NONE,
    val notebookDelta: NotebookDelta? = null,
    val arIntents: List<ARIntent>?    = emptyList(),
    val reasoningRequired: Boolean?   = true
)

data class InvestigationPlanner(
    val goal: String?              = null,
    val currentTheory: String?     = null,
    val confidence: Int?           = null,
    val missingEvidence: String?   = null,
    val nextBestAction: String?    = null
)

data class InvestigationResult(
    val status: String,
    val observation: String,
    val turnOutcome: TurnOutcome = TurnOutcome.ACCEPTED,
    val hypothesis: String             = "",
    val alternativeHypotheses: List<Hypothesis> = emptyList(),
    val reasoning: String,
    val nextQuestion: String?          = null,
    val label: String?                 = null,
    val bbox: NormalizedBBox?          = null,
    val markerType: String             = "BOX",
    val color: String?                 = null,
    val arIntents: List<ARIntent>      = emptyList(),
    val detectedObjects: List<DetectedObject> = emptyList(),
    val requiresConfirmation: Boolean  = false,
    val supportsAR: Boolean            = true,
    val lookingFor: String             = "",
    val progress: InvestigationProgress = InvestigationProgress(),
    val stage: InvestigationStage      = InvestigationStage.START,
    val confirmedEvidence: List<String> = emptyList(),
    val notebookUpdate: NotebookUpdate? = null,
    val technicianDecision: TechnicianDecision? = null,
    val conversationTurn: ConversationTurn?     = null
)

data class EvidenceItem(
    val timestamp: Long    = System.currentTimeMillis(),
    val observation: String,
    val source: String,
    val imageId: String?   = null
)

@Deprecated("Legacy") data class VisualEvidence(
    val objects: List<String>                = emptyList(),
    val text: List<String>                   = emptyList(),
    val boundingBoxes: List<BBoxInfo>        = emptyList(),
    val visualObservations: List<String>     = emptyList()
)

@Deprecated("Legacy") data class BBoxInfo(val label: String, val bbox: NormalizedBBox)

data class Hypothesis(val description: String)

@Deprecated("Legacy") data class CaseFile(
    var problemStatement: String                        = "",
    var currentGoal: String                             = "",
    var currentHypothesis: String                       = "",
    val alternativeHypotheses: MutableList<Hypothesis>  = mutableListOf(),
    val evidenceLedger: MutableList<EvidenceItem>       = mutableListOf(),
    val questionsAsked: MutableList<String>             = mutableListOf(),
    val answersReceived: MutableList<String>            = mutableListOf(),
    var investigationStage: InvestigationStage          = InvestigationStage.START,
    var conversationSummary: String                     = "",
    var lastTechnicianResponse: String                  = "",
    var lastQuestion: String                            = "",
    var missingEvidence: String                         = "",
    var targetObject: String?                           = null,
    var targetDescription: String?                      = null,
    var targetBoundingBox: NormalizedBBox?              = null,
    var highlightRecommendation: Boolean                = false
)

data class InvestigationSession(
    val sessionId: String                               = UUID.randomUUID().toString(),
    var problemStatement: String                        = "",
    val evidence: MutableList<EvidenceItem>             = mutableListOf(),
    val conversationHistory: MutableList<String>        = mutableListOf(),
    var currentQuestion: String?                        = null,
    var currentObjective: String                        = "Gather initial context",
    var missingEvidence: String                         = "Problem description",
    var currentLookingFor: String                       = "",
    var progress: InvestigationProgress                 = InvestigationProgress(),
    var stage: InvestigationStage                       = InvestigationStage.START,
    val askedQuestions: MutableSet<String>              = mutableSetOf(),
    val confirmedEvidenceTags: MutableSet<String>       = mutableSetOf(),
    var currentHypothesis: String                       = "",
    val caseFile: CaseFile                              = CaseFile(),
    val notebook: InvestigationNotebook                 = InvestigationNotebook(),
    val startTime: Long                                 = System.currentTimeMillis(),
    var endTime: Long                                   = 0,
    var mode: InvestigationMode                         = InvestigationMode.DIAGNOSE,
    val planner: InvestigationPlanner?                  = InvestigationPlanner(),
    // A1: SessionTranscript replaces raw conversationHistory for context management
    val transcript: SessionTranscript                   = SessionTranscript()
)

enum class ARTool { BOX, ARROW, TEXT, AI_MARKER, INSTRUCTION_CARD }
enum class LanguageMode { ENGLISH, HINDI, HINGLISH }
enum class ARState { IDLE, DETECTING, VALIDATING, RENDERING, LOCKED }
enum class InvestigationMode { DIAGNOSE, LOCATE }

data class FrameSnapshot(
    val timestamp: Long              = System.currentTimeMillis(),
    val anchors: Map<String, Anchor> = emptyMap()
)

// ─────────────────────────────────────────────────────────────────────────────
// RCA REPORT
// ─────────────────────────────────────────────────────────────────────────────

data class RCAReport(
    val sessionDurationMs:       Long,
    val totalTurns:              Int,
    val steps:                   List<RCAStep>        = emptyList(),
    val references:              List<SOPReference>   = emptyList(),
    val outcome:                 String,
    val recommendedFollowUp:     String               = "",
    // Phase18 customer-facing session report fields
    val sessionId:               String               = "",
    val startedAtMs:             Long                 = 0L,
    val endedAtMs:               Long                 = 0L,
    val goal:                    String               = "",
    val sessionStatus:           String               = "",
    val rootCauseConfidence:     String               = "",
    val sourcesUsed:             List<String>         = emptyList(),
    // Legacy fields retained for existing UI/runtime compatibility
    val problemStatement:        String               = "",
    val rootCause:               String               = "",
    val confirmedFacts:          List<String>         = emptyList(),
    val checksPerformed:         List<String>         = emptyList(),
    val rejectedTheories:        List<String>         = emptyList(),
    val actionsTaken:            List<String>         = emptyList(),
    val visualFindings:          List<String>         = emptyList(),
    val resolution:              String               = "",
    val sessionDurationMinutes:  Int                  = 0
)

data class RCAStep(
    val stepNumber:  Int,
    val description: String,
    val sopId:       String               = "",
    val source:      String               = "",
    val result:      String,
    val summary:     String               = "",
    val answeredBy:  AnswerSource         = AnswerSource.LOCAL,
    val answeredByLabel: String           = "On-device AI",
    val timestampMs: Long                 = 0L,
    val elapsedMs:   Long                 = 0L,
    val visualUsed:  Boolean              = false,
    val attachmentName: String            = ""
)

data class SOPReference(
    val sopId:   String,
    val title:   String,
    val source:  String,
    val page:    String                   = ""
)

data class NotebookDisplay(
    val problem:             String,
    val currentGoal:         String,
    val primaryTheory:       String,
    val secondaryTheory:     String = "",
    val confirmedFacts:      List<String>,
    val visualFindings:      List<String> = emptyList(),
    val checksPerformed:     List<String>,
    val actionsTried:        List<String>,
    val rejectedTheories:    List<String>,
    val seenObjects:         List<String>,
    val visualState:         String,
    val lastUserObservation: String       = "",
    val lastAnswerSource:    ObservationSource = ObservationSource.CLOUD,
    val lastAnswerOffline:   Boolean = false
)
