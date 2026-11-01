package com.example.arfas

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AndroLLM-style owner for one user turn at a time.
 *
 * The UI never calls native LiteRT cancellation. It only cancels the owning
 * coroutine [Job]. Cancellation propagates through AndroBaselineRuntime ->
 * LocalModelManager -> AndroLLMLocalInferenceCore, where native cancel/close
 * executes on a worker dispatcher. The next turn waits behind [turnMutex]
 * until the previous turn has fully unwound.
 *
 * This mirrors AndroLLM VoiceAssistantService's `scope + genJob` ownership
 * while keeping FixSight's existing Activity/UI, routing, attachments,
 * camera and Notebook/RCA flows unchanged.
 */
class AndroTurnCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turnMutex = Mutex()
    private val turnIds = AtomicLong(0L)
    private val stateLock = Any()

    @Volatile private var activeJob: Job? = null
    @Volatile private var activeTurnId: Long = 0L

    val isTurnActive: Boolean
        get() = activeJob?.isActive == true

    fun launchTurn(
        label: String,
        block: suspend (turnId: Long) -> Unit
    ): Long {
        val turnId = turnIds.incrementAndGet()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            turnMutex.withLock {
                if (activeTurnId != turnId) return@withLock
                try {
                    Log.i(TAG, "ANDRO_TURN_BEGIN id=$turnId label=$label")
                    block(turnId)
                    Log.i(TAG, "ANDRO_TURN_END id=$turnId label=$label")
                } catch (ce: CancellationException) {
                    Log.i(TAG, "ANDRO_TURN_CANCELLED id=$turnId label=$label reason=${ce.message ?: "cancelled"}")
                    throw ce
                } catch (t: Throwable) {
                    // Match AndroLLM VoiceAssistantService: a failed turn is
                    // contained by the supervisor scope and must not take down
                    // the process/UI thread.
                    Log.e(TAG, "ANDRO_TURN_FAILED id=$turnId label=$label ${t.javaClass.simpleName}: ${t.message}", t)
                } finally {
                    synchronized(stateLock) {
                        if (activeTurnId == turnId) {
                            activeJob = null
                            activeTurnId = 0L
                        }
                    }
                }
            }
        }

        synchronized(stateLock) {
            // A replacement turn cancels only the old coroutine job. We do not
            // invoke LiteRT here. Its collector performs the native unwind.
            activeJob?.cancel(CancellationException("superseded by turn $turnId"))
            activeTurnId = turnId
            activeJob = job
            job.start()
        }
        return turnId
    }

    /** Non-blocking from the caller's perspective; safe to call on Main. */
    fun cancelActiveTurn(reason: String) {
        val job = synchronized(stateLock) { activeJob }
        if (job?.isActive == true) {
            Log.i(TAG, "ANDRO_TURN_CANCEL_REQUEST id=$activeTurnId reason=$reason")
            job.cancel(CancellationException(reason))
        }
    }

    fun close() {
        cancelActiveTurn("coordinator closed")
        scope.cancel()
        synchronized(stateLock) {
            activeJob = null
            activeTurnId = 0L
        }
    }

    private companion object {
        const val TAG = "AndroTurnCoordinator"
    }
}
