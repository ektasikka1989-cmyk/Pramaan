package com.example.arfas

import java.time.LocalDate
import java.util.UUID

/** Offline visit domain. No LLM output can change an answer, close a referral or complete a visit. */
enum class ArogyaKind(val title: String) { NEWBORN("Mother & newborn"), CHILD("Young child"), FOLLOW_UP("General follow-up") }
enum class ArogyaAnswer { DONE, CONCERN, UNKNOWN }
enum class ArogyaReferral { NONE, NEEDED, CONTACTED, ARRANGED, SEEN }

/** Live answer progress: the stage and the answer text as it is being written (a draft until checked). */
data class ArogyaProgress(val stage: String, val draft: String = "")

/** What the answer card shows while streaming: the draft text, and whether a rejected draft was just cleared. */
data class ArogyaDraftUi(val text: String = "", val checking: Boolean = false)

/**
 * Review fix (stream abort): folds progress updates into card state. Pure, host-tested.
 * A "checking" update (rejected draft cleared) empties the card so the next answer
 * never appends to rejected text; other blank updates are ignored; deltas accumulate
 * in the engine's own buffer and arrive here as the full draft so far.
 */
object ArogyaDraftUiReduce {
    fun reduce(s: ArogyaDraftUi, p: ArogyaProgress): ArogyaDraftUi = when {
        p.stage == "checking" -> ArogyaDraftUi("", true)
        p.draft.isNotBlank() -> ArogyaDraftUi(p.draft, false)
        else -> s
    }
}

/** Batch 6 (U7): referrals the ASHA has not followed up yet — recorded NEEDED, never contacted. Pure, host-tested. */
object ArogyaReferralFollowUp {
    fun pending(visits: List<ArogyaVisit>, today: LocalDate, days: Long = 7): List<ArogyaVisit> =
        visits.filter { it.completed && it.referral == ArogyaReferral.NEEDED && it.date >= today.minusDays(days).toString() }
}
data class ArogyaAttachment(val id: String, val label: String, val mime: String, val extractedText: String = "")
data class ArogyaVisit(
    val id: String = UUID.randomUUID().toString(),
    val personId: String = UUID.randomUUID().toString(),
    val name: String = "", val village: String = "", val kind: ArogyaKind = ArogyaKind.NEWBORN,
    val date: String = LocalDate.now().toString(), val birthDate: String = "", val homeBirth: Boolean = false,
    val answers: Map<String, ArogyaAnswer> = emptyMap(), val notes: String = "",
    val nextDate: String = "", val nextReason: String = "", val planConfirmed: Boolean = false,
    val referral: ArogyaReferral = ArogyaReferral.NONE, val referralNote: String = "",
    val attachments: List<ArogyaAttachment> = emptyList(), val completed: Boolean = false,
    val reviewed: Boolean = false, val demo: Boolean = false, val updatedAt: Long = System.currentTimeMillis(),
    val protocolVersion: String = ArogyaWorkflow.VERSION,
    val revision:Long=0L, val notesReviewedForDanger:Boolean=false,
    val referralEpisodeId:String="", val referralResolutionConfirmed:Boolean=false,
    val captureOriginal:String="", val captureItems:List<ArogyaCaptureItem> = emptyList(),
    /** Every guide question asked during this visit, with the answer and the guide passages behind it. */
    val guideLog:List<ArogyaGuideEntry> = emptyList()
)
/** One guide question and its answer, saved with the visit (encrypted on this phone like the rest of the visit). */
data class ArogyaGuideEntry(val at:Long, val question:String, val answer:String, val quote:String="", val quoteSource:String="",
    val sources:List<ArogyaSource> = emptyList(), val provider:String="", val elapsedMs:Long=0L, val warning:String="",
    /** Item 5: 1 = guide answer, 2 = official website, 3 = not found. */
    val tier:Int = 1,
    /** Item 5a: the ☁ Check online answer kept beside the offline one ("" when never checked). */
    val onlineAnswer:String = "", val onlineSources:List<ArogyaSource> = emptyList(), val onlineTier:Int = 0)
data class ArogyaStep(val id: String, val title: String, val instruction: String, val sourceId: String?, val priority: Boolean = false)
data class ArogyaSource(val id: String, val title: String, val locator: String, val excerpt: String, val url: String,
    /** Sentences of [excerpt] the answer was built from (highlighted when the passage is shown). */
    val highlights: List<String> = emptyList(),
    /** Item 5d: "guide" (on this phone) or "web" (official website, not in the guides). */
    val kind: String = "guide",
    /** Item 5d: publication/updated date of a web source ("" when unknown); guides use the library. */
    val date: String = "",
    /** Item 5d: domain of a web source (e.g. nhm.gov.in). */
    val domain: String = "")

object ArogyaWorkflow {
    const val VERSION = "asha-visit-demo-2"
    /** Official-form visit snapshots (Annexure 9, HBYC, MCP ANC…): keyed by person+form+column, checklist does not apply. */
    const val OFFICIAL_FORM = "arogya-form-1"
    val SUPPORTED_VERSIONS=setOf("asha-visit-demo-1",VERSION,OFFICIAL_FORM)
    const val FRESHNESS = "Reference dates use birth as Day 1; new child workflows cover SSBSK ages 3–36 months. Confirm the applicable state/supervisor plan. Saved dates never change automatically. This is a partial checklist, not a complete clinical assessment."
    /** Never silently cut a confirmed transcription or handover mid-sentence. */
    fun appendNote(existing: String, addition: String, limit: Int = 20000): String? {
        if (addition.isBlank()) return null
        val combined = if (existing.isBlank()) addition.trim() else existing + "\n\n" + addition.trim()
        return combined.takeIf { it.length <= limit }
    }
    fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value) } catch (_: Exception) { null }
    fun steps(kind: ArogyaKind): List<ArogyaStep> {
        val common = listOf(ArogyaStep("prepare", "Prepare and ask permission", "Explain the visit. Check the register, MCP card and working equipment. Ask permission before recording or photographing anything.", "prepare"))
        val specific = when(kind) {
            ArogyaKind.NEWBORN -> listOf(
                ArogyaStep("baby_danger", "Check newborn danger signs first", "Ask and observe: unable to feed, weak cry or limpness, fast or difficult breathing, hot or cold to touch, or yellow palms/soles. Mark Concern if any sign is present; Not sure if you cannot assess. Do not wait for the checklist to finish to arrange urgent assessment.", "danger", true),
                ArogyaStep("mother_danger", "Check the mother's wellbeing", "Ask about excessive bleeding, fever, foul-smelling discharge or reduced consciousness. Mark Concern if present and arrange urgent assessment through your referral pathway.", "mother", true),
                ArogyaStep("feeding", "Ask about feeding and warmth", "Ask the mother about feeding and keeping the baby warm. Record difficulties in Notes. Use the source card for counselling; do not assume feeding is adequate from a photograph.", "feeding"),
                ArogyaStep("measure", "Record direct observations", "Measure and record weight and temperature with units in Notes, using your training and equipment. Check the MCP card for birth vaccinations. Do not infer measurements from images.", "measure"),
                ArogyaStep("counsel", "Counsel and check understanding", "Discuss the newborn danger signs and ask the caregiver to explain when and where they would seek help. Record questions or unresolved concerns.", "danger")
            )
            ArogyaKind.CHILD -> listOf(
                ArogyaStep("child_danger", "Check for illness before routine tasks", "Ask about inability to drink or breastfeed, vomiting everything, convulsions, unusual sleepiness/unconsciousness, high fever, blood in stools or fast/difficult breathing. Mark Concern if any is reported; arrange urgent assessment, without waiting for the checklist to finish.", "child_danger", true),
                ArogyaStep("feeding", "Discuss age-appropriate feeding", "Confirm age from the card. Ask what the child eats and drinks. Record concerns. Open the feeding source that applies to the child's age.", "child_feeding"),
                ArogyaStep("card", "Review the MCP card", "Review recorded growth, immunisation and the provider's next appointment with the caregiver. Record what is actually written; have unclear entries checked by the ANM.", "prepare"),
                ArogyaStep("play", "Discuss play and safety", "Ask about play, caregiver interaction and household safety. Record family questions or barriers to care.", "safety")
            )
            ArogyaKind.FOLLOW_UP -> listOf(
                ArogyaStep("concern", "Ask what has changed", "Ask about new or worsening problems and any urgent concern. Follow your local referral pathway if help is needed. This is a record of follow-up, not an automated diagnosis.", null, true),
                ArogyaStep("plan", "Review the clinician's plan", "Read the existing provider instructions with the family. Record appointments, access difficulties and questions. Do not generate or change a medicine or dose.", null),
                ArogyaStep("support", "Agree practical support", "Record the support needed: accompaniment, transport coordination, an ANM discussion or help attending the next service.", null)
            )
        }
        return common + specific + ArogyaStep("record", "Update records and agree the next action", "Record the visit in the relevant card/register. Confirm the next date and purpose with the caregiver and your applicable programme or supervisor plan.", "record")
    }
    fun concerns(v: ArogyaVisit) = steps(v.kind).filter { v.answers[it.id] == ArogyaAnswer.CONCERN }
    fun priorityConcern(v: ArogyaVisit) = steps(v.kind).any { it.priority && v.answers[it.id] == ArogyaAnswer.CONCERN }
    fun unresolved(v: ArogyaVisit) = steps(v.kind).filter { v.answers[it.id] == null || v.answers[it.id] == ArogyaAnswer.UNKNOWN }
    fun referralOpen(v: ArogyaVisit) = v.referral!=ArogyaReferral.NONE && !referralResolved(v)
    fun profileIssues(v: ArogyaVisit, today: LocalDate = LocalDate.now()): List<String> = buildList {
        if(v.id.isBlank() || v.personId.isBlank()) add("Visit and beneficiary identifiers are required.")
        if(v.protocolVersion !in SUPPORTED_VERSIONS) add("Unsupported workflow version.")
        if (v.name.isBlank()) add("Enter a name or local beneficiary code.")
        val day = parseDate(v.date)
        if (day == null || day > today) add("Enter a valid visit date, not in the future (YYYY-MM-DD).")
        val birth = parseDate(v.birthDate)
        if (v.kind != ArogyaKind.FOLLOW_UP && birth == null) add("Confirm the child's date of birth.")
        if (birth != null && day != null && birth > day) add("Birth date cannot be after the visit.")
        if (birth != null && day != null && v.kind == ArogyaKind.NEWBORN && day > birth.plusDays(41)) add("This newborn checklist is limited to the first 42 days. Start an appropriate follow-up.")
        if (birth != null && day != null && v.kind == ArogyaKind.CHILD && (day < birth.plusMonths(3) || day > birth.plusMonths(36))) add("This reference young-child checklist covers 3–36 months. Use a supervisor-led follow-up outside this range.")
    }
    fun validation(v: ArogyaVisit, today: LocalDate = LocalDate.now()): List<String> = profileIssues(v,today) + buildList {
        val day = parseDate(v.date)
        if(needsObservationReview(v)) add("Review the notes against the priority checklist and confirm concerns or uncertainty there.")
        if(steps(v.kind).any {it.priority && v.answers[it.id]==ArogyaAnswer.UNKNOWN}) add("Priority assessment remains unknown: keep as draft and record supervisor contact.")
        if(v.referral==ArogyaReferral.SEEN && !referralResolved(v)) add("Confirm and record the provider outcome before closing the referral.")
        if (steps(v.kind).any { v.answers[it.id] == null }) add("Record a response for each step; use Not sure when unassessed.")
        if ((unresolved(v).isNotEmpty() || concerns(v).isNotEmpty()) && v.notes.isBlank()) add("Record unresolved observations/concerns and the action taken in Notes.")
        if (priorityConcern(v) && v.referral == ArogyaReferral.NONE) add("Record the referral action for the urgent concern.")
        if (v.referral != ArogyaReferral.NONE && v.referralNote.isBlank()) add("Record referral destination/contact, action and outcome or pending task.")
        if (v.nextDate.isNotBlank() && (parseDate(v.nextDate) == null || (day != null && parseDate(v.nextDate)!! < day))) add("Next visit date must be valid and not before this visit.")
        if (v.nextReason.isBlank()) add("Record the next action, or why no routine date is set.")
        if (!v.planConfirmed) add("Confirm the next plan with the applicable programme/supervisor.")
        if (!v.reviewed) add("Review the record and confirm it is accurate.")
    }
    /** Reference only; never automatically schedules, closes overdue tasks, or handles at-risk care. */
    fun referenceDates(v: ArogyaVisit): List<Pair<String, LocalDate>> {
        val birth = parseDate(v.birthDate) ?: return emptyList()
        return when(v.kind) {
            ArogyaKind.NEWBORN -> (if (v.homeBirth) listOf("Day of birth" to birth) else emptyList()) +
                listOf(3L,7L,14L,21L,28L,42L).map { "Day $it (birth = Day 1)" to birth.plusDays(it-1) }
            ArogyaKind.CHILD -> (if(v.protocolVersion=="asha-visit-demo-1") listOf(3L,6L,9L,12L,15L) else listOf(3L,6L,9L,12L,18L,24L,30L,36L)).map { "Age $it months" to birth.plusMonths(it) }
            else -> emptyList()
        }
    }
    fun needsObservationReview(v:ArogyaVisit)=v.notes.isNotBlank() && !v.notesReviewedForDanger
    fun needsPriorityReview(v:ArogyaVisit)=needsObservationReview(v) || steps(v.kind).any {it.priority && v.answers[it.id]==ArogyaAnswer.UNKNOWN}
    fun referralKey(v:ArogyaVisit)=v.referralEpisodeId.ifBlank {v.id}
    fun referralResolved(v:ArogyaVisit)=v.referral==ArogyaReferral.SEEN && v.referralResolutionConfirmed && v.referralNote.isNotBlank()
    private val order=compareBy<ArogyaVisit>{it.date}.thenBy {it.updatedAt}.thenBy {it.revision}
    fun referralOpen(v:ArogyaVisit,all:List<ArogyaVisit>):Boolean {
        if(v.referral==ArogyaReferral.NONE) return false
        val latest=all.filter {it.personId==v.personId && referralKey(it)==referralKey(v) && it.referral!=ArogyaReferral.NONE}.maxWithOrNull(order) ?: v
        return !referralResolved(latest)
    }
    fun needsAction(v:ArogyaVisit,all:List<ArogyaVisit>)=referralOpen(v,all)||(!v.completed&&(priorityConcern(v)||needsPriorityReview(v)))
    fun plannedVisits(visits:List<ArogyaVisit>):List<ArogyaVisit> {
        val plans=visits.filter {it.completed}.groupBy {it.personId}.values.mapNotNull {it.maxWithOrNull(order)}.filter {it.nextDate.isNotBlank()}
        val referrals=visits.filter {it.referral!=ArogyaReferral.NONE}.groupBy {it.personId to referralKey(it)}.values.mapNotNull {it.maxWithOrNull(order)?.takeUnless {v->referralResolved(v)}}
        val attention=visits.filter {!it.completed && (priorityConcern(it)||needsPriorityReview(it))}
        return (plans+referrals+attention).distinctBy {it.id}.sortedWith(compareBy<ArogyaVisit>{!needsAction(it,visits)}.thenBy {it.nextDate.ifBlank {"0000"}})
    }
    fun followUp(v:ArogyaVisit)=ArogyaVisit(personId=v.personId,name=v.name,village=v.village,kind=v.kind,birthDate=v.birthDate,homeBirth=v.homeBirth,demo=v.demo,
        referral=if(referralOpen(v)) v.referral else ArogyaReferral.NONE,referralNote=if(referralOpen(v)) v.referralNote else "",
        referralEpisodeId=if(referralOpen(v)) referralKey(v) else "")
    fun ageMonths(v:ArogyaVisit):Long?=parseDate(v.birthDate)?.let {birth->parseDate(v.date)?.let {java.time.temporal.ChronoUnit.MONTHS.between(birth,it)}}
    fun steps(v:ArogyaVisit):List<ArogyaStep> = steps(v.kind).map {step ->
        if(v.kind==ArogyaKind.CHILD && v.protocolVersion==VERSION && step.id=="feeding") {
            val age=ageMonths(v)
            step.copy(sourceId=when {age==null->null;age<6->"child_feeding";age<9->"feeding_6_9";age<12->"feeding_9_12";age<24->"feeding_12_24";else->"feeding_24_36"},instruction="Confirm age from the MCP card. Recorded age: ${age ?: "unknown"} completed months. Review the matching source card, ask what the child eats and drinks, and record difficulties. Confirm counselling with the applicable supervisor plan.")
        } else step
    }
    fun report(v: ArogyaVisit): String = buildString {
        appendLine("AROGYADOOT — VISIT RECORD")
        if (v.demo) appendLine("SYNTHETIC DEMO — NOT A REAL PATIENT")
        appendLine(if (v.completed) "Reviewed visit record" else "DRAFT — NOT COMPLETED")
        appendLine("Visit: ${v.id}\nBeneficiary: ${v.name}\nLocal ID: ${v.personId}\nVillage: ${v.village}\nVisit date: ${v.date}\nType: ${v.kind.title}\nBirth date: ${v.birthDate}")
        appendLine("\nOBSERVATIONS (worker-confirmed; not AI diagnosis)")
        steps(v).forEach { appendLine("${it.title}: ${v.answers[it.id]?.name ?: "NOT ASSESSED"}") }
        appendLine("\nNOTES\n${v.notes}\n\nREFERRAL: ${v.referral}\n${v.referralNote}")
        appendLine("Notes/checklist review: ${v.notesReviewedForDanger}; referral episode: ${referralKey(v)}; outcome confirmed: ${v.referralResolutionConfirmed}")
        appendLine("\nNEXT VISIT: ${v.nextDate.ifBlank { "No date set" }}\n${v.nextReason}\nPlan confirmed: ${v.planConfirmed}")
        if (v.guideLog.isNotEmpty()) {
            appendLine("\nGUIDE QUESTIONS ASKED DURING THIS VISIT (${v.guideLog.size})")
            v.guideLog.forEachIndexed { i, e -> appendLine(ArogyaShare.guideEntryText(e, i + 1, withPassages = false)) }
        }
        appendLine("\nATTACHMENTS: ${v.attachments.size} (images/files not included in this report)")
        appendLine("\nSOURCES / SCOPE\n$FRESHNESS\nWorkflow: ${v.protocolVersion}")
        steps(v).mapNotNull { it.sourceId }.distinct().mapNotNull { ArogyaSources.all[it] }.forEach { appendLine("${it.title} — ${it.locator}") }
        appendLine("No diagnostic clearance or medicine recommendation is implied by completing this record.")
    }
}
