package com.example.arfas

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Callback-backed LiteRT-LM streaming bridge — matches real AndroLLM production approach.
 *
 * FIX: The SDK Flow-returning sendMessageAsync overload is deliberately NOT used because
 * its onDone callback references SendChannel.close$default which no longer exists in
 * kotlinx-coroutines 1.8+/1.9, causing a NoSuchMethodError crash on the native callback thread.
 * This is the same fix the real AndroLLM LiteRtLmEngine uses.
 *
 * FIX: prompt is wrapped in Message.user() — the real AndroLLM engine sends
 * Message.user(text) not a raw string to sendMessageAsync.
 *
 * FIX: ThinkingConfig(enableThinking=false) is set to prevent <think> token leaks
 * from models like Qwen3 that emit thinking markers as ordinary tokens when not configured.
 */
fun Conversation.safeSendMessageAsync(
    textPrompt: String,
    maxOutputToken: Int = 2048,
    reusedSession: Boolean = false,
    stopSequences: List<String>,
    cleanOutput: (String) -> String,
    onConversationRetired: () -> Unit = {}
): Flow<String> = callbackFlow {
    val terminal = AtomicBoolean(false)
    val anyCallbackSeen = AtomicBoolean(false)
    val stopDetected = AtomicBoolean(false)
    val tracker = AndroStopSequenceTracker(stopSequences)
    val loopGuard = AndroGenerationLoopGuard()
    val raw = StringBuilder()
    val emitted = StringBuilder()

    fun emitDelta(fullClean: String) {
        val old = emitted.toString()
        val delta = if (fullClean.startsWith(old)) fullClean.substring(old.length) else fullClean
        if (delta.isNotEmpty()) {
            if (fullClean.startsWith(old)) emitted.append(delta) else {
                emitted.setLength(0)
                emitted.append(fullClean)
            }
            trySend(delta)
        }
    }

    fun clean(rawText: String): String = cleanOutput(rawText)

    fun retireAfterStop() {
        // Exact AndroLLM behavior: an explicit stop sequence is a clean user-visible
        // end of turn, but the native decode is actively unwound off the callback
        // thread and that Conversation is retired. Reusing it after a forced stop can
        // leave the next turn silent/wedged. Natural native EOS still preserves KV.
        if (!stopDetected.compareAndSet(false, true)) return
        launch(Dispatchers.Default) {
            runCatching { this@safeSendMessageAsync.cancelProcess() }
            runCatching { this@safeSendMessageAsync.close() }
            runCatching { onConversationRetired() }
            if (terminal.compareAndSet(false, true)) this@callbackFlow.close()
        }
    }

    val callback = object : MessageCallback {
        override fun onMessage(message: Message) {
            anyCallbackSeen.set(true)
            if (terminal.get() || stopDetected.get()) return
            try {
                val text = AndroMessageText.extract(message)
                if (text.isEmpty()) return
                raw.append(text)

                if (tracker.feed(text) != null) {
                    val cut = raw.substring(0, tracker.stopStartIndex.toInt().coerceIn(0, raw.length))
                    emitDelta(clean(cut))
                    retireAfterStop()
                    return
                }
                if (loopGuard.feed(text)) {
                    emitDelta(clean(raw.toString()))
                    retireAfterStop()
                    return
                }

                val emissionPoint = (raw.length - tracker.holdbackLength).coerceAtLeast(0)
                emitDelta(clean(raw.substring(0, emissionPoint)))
            } catch (t: Throwable) {
                if (terminal.compareAndSet(false, true)) this@callbackFlow.close(t)
            }
        }

        override fun onDone() {
            anyCallbackSeen.set(true)
            if (terminal.get() || stopDetected.get()) return
            try {
                emitDelta(clean(raw.toString()))
                if (terminal.compareAndSet(false, true)) this@callbackFlow.close()
            } catch (t: Throwable) {
                if (terminal.compareAndSet(false, true)) this@callbackFlow.close(t)
            }
        }

        override fun onError(error: Throwable) {
            anyCallbackSeen.set(true)
            if (stopDetected.get()) {
                // Expected error from the explicit-stop cancel/close unwind. The
                // retirement coroutine owns terminal completion.
                return
            } else if (terminal.compareAndSet(false, true)) {
                this@callbackFlow.close(error)
            }
        }
    }

    // AndroLLM parity: a reused Conversation can accept sendMessageAsync() yet
    // never issue ANY callback. Detect that silent stall early and retire it so
    // LocalModelManager can perform its one bounded fresh-session retry.
    val firstCallbackWatchdog = if (reusedSession) {
        launch(Dispatchers.Default) {
            delay(FIRST_CALLBACK_TIMEOUT_MS)
            if (!anyCallbackSeen.get() && !terminal.get() && !stopDetected.get()) {
                runCatching { this@safeSendMessageAsync.cancelProcess() }
                runCatching { this@safeSendMessageAsync.close() }
                runCatching { onConversationRetired() }
                if (terminal.compareAndSet(false, true)) {
                    this@callbackFlow.close(IllegalStateException("STALLED_REUSED_SESSION_NO_CALLBACK"))
                }
            }
        }
    } else null

    try {
        // FIX: wrap prompt in Message.user() — real AndroLLM sends Message.user(text)
        // FIX: pass ThinkingConfig(enableThinking=false) — prevents <think> token leaks
        // FIX: pass explicit maxOutputToken cap — prevents unbounded generation
        sendMessageAsync(
            Message.user(textPrompt),
            callback,
            maxOutputToken = maxOutputToken.coerceIn(16, 2048),
            thinkingConfig = ThinkingConfig(enableThinking = false)
        )
    } catch (t: Throwable) {
        terminal.set(true)
        close(t)
    }

    awaitClose {
        firstCallbackWatchdog?.cancel()
        if (!terminal.get()) {
            try { cancelProcess() } catch (_: Throwable) {}
        }
    }
}

private const val FIRST_CALLBACK_TIMEOUT_MS = 25_000L
