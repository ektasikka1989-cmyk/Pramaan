package com.example.arfas

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Meta Llama 3.2 (GGUF) on the phone CPU through llama.cpp.
 *
 * Stateless per call: every generation receives the full system prompt + history + turn. The
 * native layer keeps the KV cache of the previous prompt and only processes the part that
 * changed, so the long fixed system prompt costs nothing after the first turn.
 */
internal class LlamaCppCore(private val context: Context) : LocalInferenceCore {

    companion object {
        private const val TAG = "LlamaCppCore"
        /** Llama 3.2 Instruct recommended sampling for open chat. */
        const val CHAT_TEMPERATURE = 0.6
        private const val TOP_P = 0.9f
        private const val TOP_K = 40
        /** Light penalty: breaks small-model loops without discouraging exact copying of evidence. */
        private const val REPEAT_PENALTY = 1.05f
        private const val PROMPT_SAFETY_TOKENS = 16

        /** Context size from device RAM: KV cache of a 3B model is ~112 KB per token. */
        fun contextTokensFor(totalRamMb: Long): Int = when {
            totalRamMb >= 7_500 -> 6_144
            totalRamMb >= 5_500 -> 4_096
            else -> 2_048
        }

        /**
         * Threads: the fast cores. llama.cpp gets slower when it also uses slow ("little") cores, so
         * it uses the cores whose top speed is within 80 % of the fastest, never fewer than half of
         * all cores, at most 8. Mid-range phones (4 big + 4 little: 4 threads, as before); phones
         * whose cores are all fast (Snapdragon 8 Elite: 2 x 4.3 GHz + 6 x 3.5 GHz) use all 8.
         * [maxFreqKhz]: each core's cpuinfo_max_freq; empty when unreadable (half of [cores]).
         */
        fun threadsFor(cores: Int, maxFreqKhz: List<Long> = emptyList()): Int {
            val half = (cores / 2).coerceIn(2, 4)
            val top = maxFreqKhz.maxOrNull()?.takeIf { it > 0 } ?: return half
            // 0.75, not 0.8: the S25 Ultra's Snapdragon 8 Elite has 2 x 4.47 GHz + 6 x 3.53 GHz cores
            // (0.79 of the top), so 0.8 left six fast cores idle and llama.cpp ran on 4 threads.
            val fast = maxFreqKhz.count { it >= top * 0.75 }
            return maxOf(fast, half).coerceIn(2, 8)
        }

        /** Each core's top speed from /sys (no permission needed); empty when not readable. */
        fun coreMaxFreqs(cores: Int = Runtime.getRuntime().availableProcessors()): List<Long> = (0 until cores).mapNotNull { i ->
            runCatching { java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
    }

    private val handle = AtomicLong(0L)
    private val generating = AtomicBoolean(false)
    @Volatile private var modelPath: String? = null
    @Volatile private var contextTokens: Int = 0
    @Volatile private var lastPromptTokens: Int = 0
    @Volatile private var lastGeneratedTokens: Int = 0

    /** Timings of the most recent generation (shown in diagnostics). */
    data class Timings(
        val promptTokens: Int,
        val reusedTokens: Int,
        val generatedTokens: Int,
        val timeToFirstTokenMs: Long,
        val prefillMs: Long,
        val totalMs: Long
    ) {
        val decodeTokensPerSecond: Double
            get() {
                val decodeMs = (totalMs - timeToFirstTokenMs).coerceAtLeast(1)
                return if (generatedTokens <= 1) 0.0 else (generatedTokens - 1) * 1000.0 / decodeMs
            }
    }

    @Volatile var lastTimings: Timings? = null
        private set

    /** Called after every generation (diagnostics panel). */
    @Volatile var onTimings: ((Timings, String) -> Unit)? = null

    override val engineName: String get() = "llama.cpp"
    override val selectedBackend: BackendType get() = BackendType.CPU
    override val resolvedContextTokens: Int get() = contextTokens
    override val extendedContextTokens: Int get() = contextTokens
    override val visionSupported: Boolean get() = false
    override val isLoaded: Boolean get() = handle.get() != 0L
    override val hasActiveSession: Boolean get() = isLoaded
    override val isGenerationActive: Boolean get() = generating.get()

    val loadedModelName: String? get() = modelPath?.let { File(it).name }

    fun systemInfo(): String = runCatching { LlamaNative.nativeSystemInfo() }.getOrDefault("")

    override suspend fun loadModel(modelPath: String, onProgress: (String) -> Unit): AndroLLMCoreLoadResult =
        withContext(Dispatchers.IO) {
            val file = File(modelPath)
            if (!file.exists() || file.length() < 20_000_000L) {
                return@withContext AndroLLMCoreLoadResult(false, BackendType.CPU, 0, "GGUF file missing or incomplete: ${file.name}")
            }
            if (!isGguf(file)) {
                return@withContext AndroLLMCoreLoadResult(false, BackendType.CPU, 0, "Not a GGUF file: ${file.name}")
            }
            if (!LlamaFileCheck.isComplete(file)) {
                return@withContext AndroLLMCoreLoadResult(false, BackendType.CPU, 0,
                    "Llama file is incomplete: ${LlamaFileCheck.lastReason}. Attach a complete .gguf in Settings.")
            }
            if (!LlamaNative.ensureLoaded(context.applicationInfo.nativeLibraryDir)) {
                return@withContext AndroLLMCoreLoadResult(false, BackendType.CPU, 0, "llama.cpp library missing: ${LlamaNative.loadError}")
            }
            unload()
            onProgress("Loading ${file.name}…")
            val ram = totalRamMb()
            val nCtx = contextTokensFor(ram)
            val threads = threadsFor(Runtime.getRuntime().availableProcessors(), coreMaxFreqs())
            // Item 2 switches (defaults = current behaviour): bigger logical batch for prefill,
            // physical batch fixed at the ARM sweet spot, flash attention AUTO unless forced on.
            val batch = if (ArogyaFlags.llamaBatch2048) 2048 else 512
            val fa = if (ArogyaFlags.llamaFlashAttn) 1 else -1
            val started = System.currentTimeMillis()
            var h = LlamaNative.nativeLoad(file.absolutePath, nCtx, threads, batch, 512, fa)
            if (h == 0L && LlamaNative.nativeLastError().contains("context")) {
                // Not enough free RAM for the KV cache: retry smaller before giving up.
                for (smaller in intArrayOf(2_048, 1_024).filter { it < nCtx }) {
                    Log.w(TAG, "LLAMA_RETRY_SMALLER_CTX ctx=$smaller ramMb=$ram")
                    onProgress("Low memory - retrying Llama with a smaller context ($smaller)…")
                    h = LlamaNative.nativeLoad(file.absolutePath, smaller, threads, 256, 256, fa)
                    if (h != 0L) break
                }
            }
            if (h == 0L) {
                return@withContext AndroLLMCoreLoadResult(false, BackendType.CPU, 0, LlamaNative.nativeLastError().ifBlank { "llama.cpp load failed" })
            }
            handle.set(h)
            this@LlamaCppCore.modelPath = file.absolutePath
            contextTokens = LlamaNative.nativeContextSize(h)
            Log.i(TAG, "LLAMA_LOADED file=${file.name} ctx=$contextTokens threads=$threads batch=$batch ubatch=512 flashAttn=$fa ramMb=$ram ms=${System.currentTimeMillis() - started}")
            onProgress("Warming up Llama…")
            // A real generation proves the weights, template and sampler work before READY.
            val warm = generateInternal(
                messages = listOf("system" to "You are a helpful assistant.", "user" to "Say OK."),
                maxTokens = 4,
                temperature = 0.0,
                onDelta = null
            )
            if (!warm.completed && warm.error != null) {
                unload()
                return@withContext AndroLLMCoreLoadResult(false, BackendType.CPU, 0, "Llama warm-up failed: ${warm.error}")
            }
            Log.i(TAG, "LLAMA_WARM ttftMs=${lastTimings?.timeToFirstTokenMs} system=${systemInfo().take(160)}")
            AndroLLMCoreLoadResult(true, BackendType.CPU, contextTokens, null)
        }

    override suspend fun generateEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double,
        onPartial: ((String) -> Unit)?
    ): AndroLLMCoreGeneration = generateInternal(
        messages = buildMessages(systemPrompt, history, currentUser),
        maxTokens = maxOutputToken,
        temperature = temperature,
        onDelta = onPartial
    )

    override suspend fun generateVisionEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        imageBytes: ByteArray,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double
    ): AndroLLMCoreGeneration = AndroLLMCoreGeneration(
        text = "",
        inferenceMs = 0L,
        completed = false,
        tokenCountAfter = 0,
        error = "vision_not_supported: Llama 3.2 3B is text-only; images go through the Meta vision stack"
    )

    override suspend fun generateChat(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String,
        maxOutputToken: Int,
        onDelta: ((String) -> Unit)?
    ): AndroLLMCoreGeneration = generateInternal(
        messages = buildMessages(systemPrompt, history, currentUser),
        maxTokens = maxOutputToken,
        temperature = CHAT_TEMPERATURE,
        onDelta = onDelta
    )

    private fun buildMessages(
        systemPrompt: String?,
        history: List<AndroLLMCoreMessage>,
        currentUser: String
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!systemPrompt.isNullOrBlank()) out += "system" to systemPrompt.trim()
        history.forEach { m ->
            val role = when (m.role.lowercase()) {
                "assistant", "model" -> "assistant"
                "system" -> "system"
                else -> "user"
            }
            if (m.content.isNotBlank()) out += role to m.content.trim()
        }
        out += "user" to currentUser.trim()
        return out
    }

    /**
     * Fits the messages into the context: drop the oldest history turns first, then shorten the
     * longest message in the middle (evidence) keeping its start and its end (the question).
     */
    private fun fitToContext(messages: List<Pair<String, String>>, maxTokens: Int, h: Long): List<Pair<String, String>>? {
        val limit = contextTokens - maxTokens - PROMPT_SAFETY_TOKENS
        if (limit <= 64) return null
        var current = messages
        fun count(list: List<Pair<String, String>>): Int = LlamaNative.nativeCountPromptTokens(
            h, list.map { it.first }.toTypedArray(), list.map { it.second }.toTypedArray()
        )
        var tokens = count(current)
        if (tokens in 0..limit) return current
        // 1) oldest history (keep system at 0 and the current user turn at the end)
        while (tokens > limit && current.size > 2) {
            val firstHistory = if (current.first().first == "system") 1 else 0
            if (firstHistory >= current.size - 1) break
            current = current.toMutableList().also { it.removeAt(firstHistory) }
            tokens = count(current)
        }
        // 2) shorten the longest message, keeping head and tail
        var guard = 0
        while (tokens > limit && guard++ < 6) {
            val idx = current.indices.maxByOrNull { current[it].second.length } ?: break
            val text = current[idx].second
            val ratio = (limit.toDouble() / tokens.coerceAtLeast(1)) * 0.92
            val keep = (text.length * ratio).toInt().coerceAtLeast(200)
            if (keep >= text.length) break
            val tail = minOf(700, keep / 3)
            val shortened = text.take(keep - tail).trimEnd() + "\n…\n" + text.takeLast(tail).trimStart()
            current = current.toMutableList().also { it[idx] = it[idx].first to shortened }
            tokens = count(current)
            Log.w(TAG, "LLAMA_PROMPT_SHORTENED message=$idx chars=${text.length}->${shortened.length} tokens=$tokens limit=$limit")
        }
        return if (tokens in 0..limit) current else null
    }

    private suspend fun generateInternal(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double,
        onDelta: ((String) -> Unit)?
    ): AndroLLMCoreGeneration = coroutineScope {
        val h = handle.get()
        if (h == 0L) {
            return@coroutineScope AndroLLMCoreGeneration("", 0L, false, 0, "llama_not_loaded")
        }
        val budget = maxTokens.coerceIn(16, (contextTokens / 2).coerceAtLeast(16))
        val fitted = withContext(Dispatchers.IO) { fitToContext(messages, budget, h) }
            ?: return@coroutineScope AndroLLMCoreGeneration("", 0L, false, 0, "prompt_too_long")

        val text = StringBuilder()
        val loopGuard = RepetitionGuard()
        var guardStopped = false
        val sink = LlamaNative.TokenSink { bytes ->
            val piece = String(bytes, Charsets.UTF_8)
            text.append(piece)
            if (loopGuard.isLooping(text)) {
                guardStopped = true
                return@TokenSink false
            }
            if (onDelta != null && piece.isNotEmpty()) runCatching { onDelta(piece) }
            true
        }
        val stats = LongArray(6)
        var done = false
        // Coroutine cancellation (user tapped stop, turn superseded) -> native cancel flag.
        val watcher = launch {
            try { awaitCancellation() } finally { if (!done) LlamaNative.nativeCancel(h) }
        }
        generating.set(true)
        val rc = try {
            withContext(Dispatchers.IO) {
                LlamaNative.nativeGenerate(
                    h,
                    fitted.map { it.first }.toTypedArray(),
                    fitted.map { it.second }.toTypedArray(),
                    budget,
                    temperature.toFloat(),
                    TOP_P,
                    TOP_K,
                    REPEAT_PENALTY,
                    -1,
                    sink,
                    stats
                )
            }
        } finally {
            done = true
            generating.set(false)
            watcher.cancel()
        }
        val timings = Timings(
            promptTokens = stats[LlamaNative.STAT_PROMPT_TOKENS].toInt(),
            reusedTokens = stats[LlamaNative.STAT_REUSED_TOKENS].toInt(),
            generatedTokens = stats[LlamaNative.STAT_GENERATED_TOKENS].toInt(),
            timeToFirstTokenMs = stats[LlamaNative.STAT_TTFT_MS],
            prefillMs = stats[LlamaNative.STAT_PREFILL_MS],
            totalMs = stats[LlamaNative.STAT_TOTAL_MS]
        )
        lastTimings = timings
        lastPromptTokens = timings.promptTokens
        lastGeneratedTokens = timings.generatedTokens
        val stopReason = when {
            guardStopped -> "ephemeral_loop_guard"
            else -> "llama_" + LlamaNative.returnCodeName(rc)
        }
        val prefillTps = (timings.promptTokens - timings.reusedTokens).coerceAtLeast(0) * 1000.0 /
            timings.prefillMs.coerceAtLeast(1)
        Log.i(
            TAG,
            "LLAMA_GENERATE stop=$stopReason prompt=${timings.promptTokens} reused=${timings.reusedTokens} " +
                "gen=${timings.generatedTokens} ttftMs=${timings.timeToFirstTokenMs} prefillMs=${timings.prefillMs} " +
                "prefillTps=${"%.1f".format(prefillTps)} " +
                "totalMs=${timings.totalMs} tokPerSec=${"%.1f".format(timings.decodeTokensPerSecond)}"
        )
        runCatching { onTimings?.invoke(timings, stopReason) }
        val completed = rc == LlamaNative.RC_EOS || rc == LlamaNative.RC_MAX_TOKENS || rc == LlamaNative.RC_CONTEXT_FULL
        val error = when {
            completed || guardStopped -> null
            rc == LlamaNative.RC_CANCELLED -> "cancelled"
            else -> LlamaNative.returnCodeName(rc) + ": " + LlamaNative.nativeLastError()
        }
        if (rc == LlamaNative.RC_CANCELLED) {
            // Propagate a real coroutine cancellation; a native-only cancel is a normal result.
            coroutineContext.ensureActiveOrThrow()
        }
        AndroLLMCoreGeneration(
            text = text.toString().trim(),
            inferenceMs = timings.totalMs,
            completed = completed || guardStopped,
            tokenCountAfter = timings.promptTokens + timings.generatedTokens,
            error = error,
            sessionReused = timings.reusedTokens > 0,
            stopReason = stopReason
        )
    }

    private fun kotlin.coroutines.CoroutineContext.ensureActiveOrThrow() {
        val job = this[kotlinx.coroutines.Job]
        if (job != null && !job.isActive) throw CancellationException("llama generation cancelled")
    }

    override fun ensureConversationForHistory(history: List<AndroLLMCoreMessage>, systemPrompt: String): Boolean = isLoaded
    override fun markAcceptedTurn(userText: String, assistantText: String) = Unit
    override fun getConversationTokenCount(): Int = lastPromptTokens + lastGeneratedTokens
    override fun closeSession() = Unit

    override fun unload() {
        val h = handle.getAndSet(0L)
        if (h != 0L) {
            runCatching { LlamaNative.nativeFree(h) }
            Log.i(TAG, "LLAMA_UNLOADED")
        }
        contextTokens = 0
    }

    override fun shutdownAsync(reason: String) {
        Log.i(TAG, "LLAMA_SHUTDOWN reason=$reason")
        Thread({ unload() }, "llama-shutdown").start()
    }

    override fun retireSessionAsync(reason: String) = Unit

    override fun cancelEphemeralGeneration() = cancelActiveGeneration()

    override fun cancelActiveGeneration() {
        val h = handle.get()
        if (h != 0L && generating.get()) LlamaNative.nativeCancel(h)
    }

    override fun requestCancelActiveGeneration(reason: String) {
        Log.i(TAG, "LLAMA_CANCEL reason=$reason")
        cancelActiveGeneration()
    }

    override suspend fun runSelfTest(): Boolean {
        if (!isLoaded) return false
        val result = generateInternal(listOf("user" to "Reply with OK."), 4, 0.0, null)
        return result.completed
    }

    override suspend fun recoverOnCpu(onProgress: (String) -> Unit): Boolean {
        val path = modelPath ?: return false
        return loadModel(path, onProgress).success
    }

    private fun totalRamMb(): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024 * 1024)
    }.getOrDefault(4_096L)

    private fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
        }
    }.getOrDefault(false)
}

/**
 * Stops a generation that repeats itself ("the the the", a sentence over and over), which small
 * models sometimes do with greedy decoding. Checks the tail only, every few characters.
 */
internal class RepetitionGuard(private val checkEvery: Int = 24) {
    private var lastCheckedLength = 0

    fun isLooping(text: CharSequence): Boolean {
        if (text.length - lastCheckedLength < checkEvery) return false
        lastCheckedLength = text.length
        return endsWithRepeat(text)
    }

    companion object {
        /** True when the text ends with the same unit (6..200 chars) repeated: 4x if short, else 3x. */
        fun endsWithRepeat(text: CharSequence): Boolean {
            val s = text.toString()
            val n = s.length
            for (unit in 6..200) {
                val times = if (unit < 20) 4 else 3
                if (unit * times > n) break
                val last = s.substring(n - unit)
                if (last.isBlank()) continue
                var ok = true
                for (k in 2..times) {
                    val start = n - unit * k
                    if (s.regionMatches(start, last, 0, unit).not()) { ok = false; break }
                }
                if (ok) return true
            }
            return false
        }
    }
}
