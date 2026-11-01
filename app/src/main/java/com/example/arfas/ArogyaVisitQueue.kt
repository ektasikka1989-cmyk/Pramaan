package com.example.arfas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

interface ArogyaVisitPersistence {
    fun readVisits(): List<ArogyaVisit>
    fun writeVisits(visits: List<ArogyaVisit>)
}

/** A process-owned queue: UI recreation cannot cancel a pending save.
 * Complete immutable snapshots allow safe coalescing and recovery after a failed write.
 * Disk commit, not optimistic UI state, determines when saving is finished.
 */
class ArogyaVisitQueue(
    private val disk: ArogyaVisitPersistence,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    data class State(val visits: List<ArogyaVisit> = emptyList(), val loaded: Boolean = false,
        val saving: Boolean = false, val error: String = "")
    private data class Write(val revision: Long, val rows: List<ArogyaVisit>)
    private val lock = Any()
    private var revision = 0L
    private val writes = Channel<Write>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable
    init {
        scope.launch {
            try { mutable.value = State(visits = disk.readVisits(), loaded = true) }
            catch (_: Exception) {
                mutable.value = State(error = "Saved records could not be opened. No records were changed. Do not clear app data.")
                return@launch
            }
            for (write in writes) {
                val failure = try { disk.writeVisits(write.rows); false } catch (_: Exception) { true }
                synchronized(lock) {
                    val current = mutable.value
                    mutable.value = current.copy(saving = write.revision != revision,
                        error = if (failure) "Visit changes could not be saved. Tap Retry save; do not clear app data." else "")
                }
            }
        }
    }
    fun save(visit:ArogyaVisit)=synchronized(lock) {
        require(mutable.value.loaded) {"Visits have not loaded"}
        val old=mutable.value.visits.firstOrNull {it.id==visit.id}
        require(old==null || old.revision==visit.revision || isOfficial(visit)) {"Record changed: reopen it before replacing this draft"}
        commit(old,visit)
    }
    /** Runs exactly the checks [save] would, without writing — so callers can validate before touching other stores. */
    fun check(visit:ArogyaVisit)=synchronized(lock) {
        require(mutable.value.loaded) {"Visits have not loaded"}
        val old=mutable.value.visits.firstOrNull {it.id==visit.id}
        require(old==null || old.revision==visit.revision || isOfficial(visit)) {"Record changed: reopen it before replacing this draft"}
        validate(old,visit)
    }
    private fun isOfficial(v:ArogyaVisit)=v.protocolVersion==ArogyaWorkflow.OFFICIAL_FORM
    fun update(id:String,transform:(ArogyaVisit)->ArogyaVisit)=synchronized(lock) {
        require(mutable.value.loaded)
        val old=mutable.value.visits.firstOrNull {it.id==id} ?: error("Visit no longer exists")
        commit(old,transform(old))
    }
    private fun enrich(old:ArogyaVisit?,proposed:ArogyaVisit):ArogyaVisit {
        val changed=old!=null && (old.notes!=proposed.notes || old.answers!=proposed.answers || old.birthDate!=proposed.birthDate || old.kind!=proposed.kind)
        val reviewedFacts=proposed.captureItems.takeIf { ArogyaCapture.render(it)==proposed.notes } ?: emptyList()
        return proposed.copy(captureItems=reviewedFacts,revision=(old?.revision ?: 0)+1,updatedAt=maxOf(System.currentTimeMillis(),(old?.updatedAt ?: 0)+1),
            notesReviewedForDanger=if(changed) false else proposed.notesReviewedForDanger,
            referralEpisodeId=if(proposed.referral!=ArogyaReferral.NONE) proposed.referralEpisodeId.ifBlank {proposed.id} else proposed.referralEpisodeId,
            referralResolutionConfirmed=proposed.referralResolutionConfirmed && proposed.referral==ArogyaReferral.SEEN && proposed.referralNote.isNotBlank())
    }
    private fun validate(old:ArogyaVisit?,proposed:ArogyaVisit) {
        val official=isOfficial(proposed) && (old==null || isOfficial(old))
        require(old==null || !old.completed || official) {"Completed records are read-only; create a follow-up"}
        require(proposed.id.isNotBlank() && proposed.personId.isNotBlank())
        require(proposed.protocolVersion in ArogyaWorkflow.SUPPORTED_VERSIONS)
        require(old==null || (old.id==proposed.id && old.personId==proposed.personId))
        require(old==null || old.referral==ArogyaReferral.NONE || proposed.referral!=ArogyaReferral.NONE) {"Record provider outcome to close referral"}
        // Official-form snapshots carry their own fixed rules, not the draft checklist.
        val next=enrich(old,proposed)
        if(next.completed && !official) require(ArogyaWorkflow.validation(next).isEmpty()) {"Visit needs review before completion"}
    }
    private fun commit(old:ArogyaVisit?,proposed:ArogyaVisit) {
        validate(old,proposed)
        val next=enrich(old,proposed)
        enqueue(mutable.value.visits.filterNot {it.id==next.id}+next)
    }
    fun delete(id: String) = synchronized(lock) {
        require(mutable.value.loaded) { "Visits have not loaded" }
        enqueue(mutable.value.visits.filterNot { it.id == id })
    }
    /** Demo reset: removes ONLY synthetic (demo=true) records and adds [seed] in one atomic write. Real records are never touched. */
    fun resetDemo(seed: List<ArogyaVisit>) = synchronized(lock) {
        require(mutable.value.loaded) { "Visits have not loaded" }
        require(seed.all { it.demo }) { "Demo seed must contain synthetic records only" }
        enqueue(mutable.value.visits.filterNot { it.demo } + seed)
    }
    fun retry() = synchronized(lock) {
        require(mutable.value.loaded) { "Unreadable records cannot be overwritten" }
        enqueue(mutable.value.visits)
    }
    private fun enqueue(rows: List<ArogyaVisit>) {
        revision++
        mutable.value = mutable.value.copy(visits = rows, saving = true)
        check(writes.trySend(Write(revision, rows)).isSuccess)
    }
}
