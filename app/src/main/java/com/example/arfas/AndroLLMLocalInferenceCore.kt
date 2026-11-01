package com.example.arfas

import android.content.Context
import android.util.Log
import android.os.Looper
import com.example.arfas.androllm.compat.ContainerMetadataReader
import com.example.arfas.androllm.compat.ModelFamily
import com.example.arfas.androllm.compat.ModelFamilyConfig
import com.example.arfas.androllm.compat.ModelFamilyRegistry
import com.example.arfas.androllm.compat.OutputDecoder
import com.example.arfas.androllm.compat.StopSequenceTracker
import com.example.arfas.androllm.catalog.ModelMetadataRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel as LiteRtChannel
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val CORE_TAG = "AndroLLMLocalCore"

internal data class AndroLLMCoreMessage(
    val role: String,
    val content: String
)

internal data class AndroLLMCoreGeneration(
    val text: String,
    val inferenceMs: Long,
    val completed: Boolean,
    val tokenCountAfter: Int,
    val error: String? = null,
    val sessionReused: Boolean = false,
    val stopReason: String = ""
)

internal data class AndroLLMCoreLoadResult(
    val success: Boolean,
    val backend: BackendType,
    val contextTokens: Int,
    val error: String? = null
)

private class NativeEngineInitWedgedException(message: String) : IllegalStateException(message)

/**
 * The portable local-inference slice of AndroLLM's LiteRtLmEngine.
 *
 * This class deliberately owns only the things that must remain AndroLLM-like:
 * Engine/Conversation ownership, family/template contract, sampler, accepted-history
 * reseed/KV reuse, callback streaming, stop/loop termination, reused-session watchdog,
 * context-overflow/template-mismatch recovery, cancellation and load-time coherence.
 *
 * FixSight-specific routing, cloud, Notebook/RCA, memory policy and UI do not live here.
 */
internal class AndroLLMLocalInferenceCore(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationLock = ReentrantLock()
    private val generationActive = AtomicBoolean(false)
    val isGenerationActive: Boolean get() = generationActive.get()
    private val reuseBroken = AtomicBoolean(false)

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var activeEphemeralConversation: Conversation? = null
    @Volatile private var familyConfig: ModelFamilyConfig? = null
    @Volatile private var outputDecoder: OutputDecoder? = null
    @Volatile private var effectiveStopSequences: List<String> = emptyList()
    @Volatile private var conversationSystemPrompt: String? = null
    @Volatile private var consumedTurns: List<AndroLLMCoreMessage> = emptyList()
    @Volatile private var loadedModelPath: String? = null

    @Volatile var selectedBackend: BackendType = BackendType.AUTO
        private set

    @Volatile var resolvedContextTokens: Int = 0
        private set

    /**
     * Larger KV cache requested from LiteRT-LM (EngineConfig.maxNumTokens) for selected-KB
     * answers; 0 = the model's own default. [resolvedContextTokens] stays the model default so
     * every other mode keeps its existing budgets; only the KB path reads this value.
     */
    @Volatile var extendedContextTokens: Int = 0
        private set
    @Volatile private var lastLoadRequestedExtension: Boolean = false

    @Volatile var lastSessionReused: Boolean = false
        private set

    /** Runtime-probed model capability. No filename/topic rules: LiteRT-LM itself
     *  tells us whether a vision executor can be initialized for the selected bundle. */
    @Volatile var visionSupported: Boolean = false
        private set
    @Volatile private var visionDeclaredByModel: Boolean = false

    val isLoaded: Boolean get() = engine != null
    val hasActiveSession: Boolean get() = conversation != null

    suspend fun loadModel(
        modelPath: String,
        onProgress: (String) -> Unit
    ): AndroLLMCoreLoadResult = withContext(Dispatchers.IO) {
        unloadInternal(evictMetadata = false)
        loadedModelPath = modelPath
        selectedBackend = BackendType.AUTO
        resolvedContextTokens = 0
        extendedContextTokens = 0
        reuseBroken.set(false)
        lastSessionReused = false
        visionSupported = false
        visionDeclaredByModel = false

        val modelFile = File(modelPath)
        val artifact = LiteRtArtifactValidator.validateLitertLm(modelPath)
        if (!artifact.valid) {
            return@withContext AndroLLMCoreLoadResult(
                success = false,
                backend = BackendType.AUTO,
                contextTokens = 0,
                error = artifact.error.ifBlank { "Model file is not a valid LiteRT-LM container" }
            )
        }
        Log.i(CORE_TAG, "ANDROLLM_CORE_ARTIFACT_OK format=${artifact.format} version=${artifact.version} bytes=${modelFile.length()}")

        val container = try {
            ContainerMetadataReader.read(modelFile)
        } catch (t: Throwable) {
            Log.w(CORE_TAG, "ANDROLLM_CORE_CONTAINER_METADATA_UNREADABLE ${t.javaClass.simpleName}: ${t.message}")
            null
        }

        val modelTypeName = container?.metadata?.modelTypeName
        val registeredModality = ModelMetadataRegistry.containerTypeFor(modelTypeName)?.modality
        visionDeclaredByModel = modelTypeName == "gemma3n" ||
            modelTypeName == "gemma4" ||
            registeredModality == ModelMetadataRegistry.RuntimeModality.VISION
        Log.i(
            CORE_TAG,
            "ANDROLLM_CORE_MODEL_CAPABILITY modelType=${modelTypeName ?: "unknown"} " +
                "visionDeclared=$visionDeclaredByModel"
        )

        val resolution = ModelFamilyRegistry.resolve(container?.metadata, modelFile.name, null)
        familyConfig = if (resolution.family == ModelFamily.GENERIC) {
            val embedded = container?.metadata?.jinjaPromptTemplate
            if (embedded.isNullOrBlank()) resolution.config else resolution.config.copy(chatTemplate = embedded)
        } else {
            resolution.config
        }
        val containerStops = container?.metadata?.stopTokens.orEmpty().filter { it.isNotBlank() }
        effectiveStopSequences = (familyConfig!!.stopSequences + containerStops).distinct()
        outputDecoder = OutputDecoder(familyConfig!!, extraStopSequences = containerStops)

        val metadataContext = container?.metadata?.maxNumTokens ?: 0
        resolvedContextTokens = metadataContext.takeIf { it > 0 } ?: 0
        Log.i(
            CORE_TAG,
            "ANDROLLM_CORE_FAMILY family=${resolution.family.displayName} source=${resolution.source.name} " +
                "stops=${effectiveStopSequences.size} metadataContext=$resolvedContextTokens"
        )
        Log.i(
            CORE_TAG,
            "ANDROLLM_CORE_SYSTEM_STRATEGY family=${resolution.family.displayName} " +
                "strategy=${if (resolution.family == ModelFamily.GEMMA) "fold_first_user" else "native_system_instruction"}"
        )

        // AndroLLM AUTO semantics adapted to the LiteRT API available in FixSight:
        // GPU first, then CPU. Crucially, a backend that initializes but fails the
        // one-time coherence self-test is ALSO rejected and Auto falls through to CPU.
        var gpu = tryLoadBackend(modelPath, BackendType.GPU, onProgress)
        if (!gpu.success && lastLoadRequestedExtension && gpu.error?.startsWith(NATIVE_INIT_WEDGED_PREFIX) != true) {
            Log.w(CORE_TAG, "KB_CONTEXT_EXTENSION_RETRY backend=GPU reason=${gpu.error}; retrying with the model default")
            gpu = tryLoadBackend(modelPath, BackendType.GPU, onProgress, allowContextExtension = false)
        }
        if (gpu.success) return@withContext gpu
        if (gpu.error?.startsWith(NATIVE_INIT_WEDGED_PREFIX) == true) {
            Log.e(CORE_TAG, "ANDROLLM_CORE_NATIVE_INIT_WEDGED backend=GPU; CPU fallback suppressed to avoid parallel native initializers")
            return@withContext gpu
        }

        Log.w(CORE_TAG, "ANDROLLM_CORE_GPU_REJECTED reason=${gpu.error}; falling back to CPU")
        var cpu = tryLoadBackend(modelPath, BackendType.CPU, onProgress)
        if (!cpu.success && lastLoadRequestedExtension && cpu.error?.startsWith(NATIVE_INIT_WEDGED_PREFIX) != true) {
            Log.w(CORE_TAG, "KB_CONTEXT_EXTENSION_RETRY backend=CPU reason=${cpu.error}; retrying with the model default")
            cpu = tryLoadBackend(modelPath, BackendType.CPU, onProgress, allowContextExtension = false)
        }
        if (cpu.success) return@withContext cpu

        unloadInternal(evictMetadata = false)
        AndroLLMCoreLoadResult(
            success = false,
            backend = BackendType.CPU,
            contextTokens = resolvedContextTokens,
            error = "Local model failed on GPU and CPU. GPU: ${gpu.error}; CPU: ${cpu.error}"
        )
    }

    private suspend fun tryLoadBackend(
        modelPath: String,
        backendType: BackendType,
        onProgress: (String) -> Unit,
        allowContextExtension: Boolean = true
    ): AndroLLMCoreLoadResult {
        try { engine?.close() } catch (_: Throwable) {}
        extendedContextTokens = 0
        // The model's default window (container metadata, else the value probed on an earlier
        // launch). Extension is requested only when that default is known and smaller than the
        // KB target; an unknown default is never overridden (it could be larger).
        val baseContext = resolvedContextTokens.takeIf { it > 0 } ?: rememberedProbedContext(modelPath)
        // GPU only: on CPU, prompt processing is too slow for twice the evidence within the KB
        // answer timeout, so CPU keeps the model default.
        val requestedContext = if (allowContextExtension && backendType == BackendType.GPU &&
            baseContext in 1 until KB_TARGET_CONTEXT_TOKENS
        ) {
            KB_TARGET_CONTEXT_TOKENS
        } else 0
        lastLoadRequestedExtension = requestedContext > 0
        Log.i(
            CORE_TAG,
            "KB_CONTEXT_EXTENSION backend=$backendType modelDefault=$baseContext requested=${requestedContext.takeIf { it > 0 } ?: "none"}"
        )
        engine = null
        closeSession()

        val backend = when (backendType) {
            BackendType.GPU -> Backend.GPU()
            else -> Backend.CPU()
        }

        var candidate: Engine? = null
        return try {
            onProgress("Loading local model on ${backendType.name}…")

            // Capability probe, not a filename allow-list. Try to initialize the same
            // selected .litertlm with a vision executor first. Text-only bundles reject
            // that configuration and are immediately reopened as text-only. For GPU
            // language inference we also try a CPU vision executor before giving up,
            // because some devices expose only one of those vision backends.
            fun newEngine(vision: Backend?, maxTokens: Int?): Engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = vision,
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath
                )
            )
            // Vision support is preserved first; within each vision option the larger KV cache
            // is tried before the model default, so a device that cannot allocate it loses only
            // the extension, never vision or the backend.
            val contextCandidates: List<Int?> = if (requestedContext > 0) listOf(requestedContext, null) else listOf(null)

            val visionCandidates: List<Backend?> = if (!visionDeclaredByModel) {
                listOf(null)
            } else when (backendType) {
                BackendType.GPU -> listOf(Backend.GPU(), Backend.CPU(), null)
                else -> listOf(Backend.CPU(), null)
            }
            var initialized: Engine? = null
            var visionEnabled = false
            var lastInitError: Throwable? = null
            var initializedContext: Int? = null
            candidates@ for (vision in visionCandidates) {
                for (maxTokens in contextCandidates) {
                    val probe = try {
                        val candidateEngine = newEngine(vision, maxTokens)
                        initializeEngineBounded(candidateEngine, backendType, vision)
                    } catch (t: Throwable) {
                        if (t is NativeEngineInitWedgedException) throw t
                        lastInitError = t
                        if (maxTokens != null) {
                            Log.w(CORE_TAG, "KB_CONTEXT_EXTENSION_INIT_FAIL backend=$backendType maxTokens=$maxTokens ${t.javaClass.simpleName}: ${t.message}")
                        }
                        null
                    }
                    if (probe != null) {
                        initialized = probe
                        visionEnabled = vision != null
                        initializedContext = maxTokens
                        break@candidates
                    }
                }
            }
            candidate = initialized ?: throw (lastInitError ?: IllegalStateException("Engine initialization failed"))
            val loaded = checkNotNull(candidate)
            engine = loaded
            selectedBackend = backendType
            visionSupported = visionEnabled
            Log.i(CORE_TAG, "ANDROLLM_CORE_DELEGATE_ACTIVE backend=$backendType vision=$visionSupported")

            extendedContextTokens = initializedContext ?: 0
            if (extendedContextTokens > 0 && resolvedContextTokens <= 0) {
                // Default already known from an earlier probe; probing the extended engine would
                // report the extension and silently change every other mode's budget.
                resolvedContextTokens = baseContext
            }
            if (resolvedContextTokens <= 0) {
                resolvedContextTokens = detectMaxContext(loaded)
                Log.i(CORE_TAG, "ANDROLLM_CORE_CONTEXT_PROBE maxTokens=$resolvedContextTokens")
                if (resolvedContextTokens > 0) rememberProbedContext(modelPath, resolvedContextTokens)
            }
            Log.i(
                CORE_TAG,
                "KB_CONTEXT_EXTENSION_ACTIVE backend=$backendType modelDefault=$resolvedContextTokens kbContext=${extendedContextTokens.takeIf { it > 0 } ?: resolvedContextTokens}"
            )

            onProgress("Warming up local model…")
            val warmupMs = runWarmup(loaded)
            Log.i(CORE_TAG, "ANDROLLM_CORE_WARMUP backend=$backendType ms=$warmupMs")

            onProgress("Checking local model output…")
            when (val selfTest = runCoherenceSelfTest(loaded)) {
                is AndroLLMCoherenceResult.Pass -> {
                    Log.i(CORE_TAG, "ANDROLLM_CORE_SELFTEST_PASS backend=$backendType")
                    AndroLLMCoreLoadResult(true, backendType, resolvedContextTokens, null)
                }
                is AndroLLMCoherenceResult.Fail -> {
                    Log.e(CORE_TAG, "ANDROLLM_CORE_SELFTEST_FAIL backend=$backendType reason=${selfTest.reason}")
                    runCatching { loaded.close() }
                    if (engine === loaded) engine = null
                    AndroLLMCoreLoadResult(false, backendType, resolvedContextTokens, selfTest.reason)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(CORE_TAG, "ANDROLLM_CORE_BACKEND_LOAD_FAIL backend=$backendType ${t.javaClass.simpleName}: ${t.message}")
            try { candidate?.close() } catch (_: Throwable) {}
            if (engine === candidate) engine = null
            AndroLLMCoreLoadResult(false, backendType, resolvedContextTokens, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun initializeEngineBounded(
        candidate: Engine,
        backendType: BackendType,
        vision: Backend?
    ): Engine {
        val visionLabel = when (vision) {
            null -> "NONE"
            else -> vision.javaClass.simpleName
        }
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "PramaanLiteRtInit-${backendType.name}").apply { isDaemon = true }
        }
        val future = executor.submit(Callable {
            candidate.initialize()
            true
        })
        val started = System.currentTimeMillis()
        return try {
            future.get(ENGINE_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.i(
                CORE_TAG,
                "ANDROLLM_CORE_ENGINE_INIT_OK backend=$backendType vision=$visionLabel ms=${System.currentTimeMillis() - started}"
            )
            candidate
        } catch (_: TimeoutException) {
            Log.e(
                CORE_TAG,
                "ANDROLLM_CORE_ENGINE_INIT_TIMEOUT backend=$backendType vision=$visionLabel timeoutMs=$ENGINE_INIT_TIMEOUT_MS"
            )
            future.cancel(true)
            executor.shutdownNow()
            val stopped = runCatching {
                executor.awaitTermination(ENGINE_INIT_CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!stopped) {
                throw NativeEngineInitWedgedException(
                    "$NATIVE_INIT_WEDGED_PREFIX backend=$backendType vision=$visionLabel timeoutMs=$ENGINE_INIT_TIMEOUT_MS"
                )
            }
            runCatching { candidate.close() }
            throw TimeoutException("Engine initialization timed out on $backendType/$visionLabel")
        } catch (t: Throwable) {
            future.cancel(true)
            runCatching { candidate.close() }
            throw t
        } finally {
            executor.shutdownNow()
        }
    }

    /** Direct AndroLLM warm-up semantics: isolated session, one token, active timeout cancel. */
    private suspend fun runWarmup(eng: Engine): Long {
        var conv: Conversation? = null
        val ref = AtomicReference<Conversation?>(null)
        val started = System.currentTimeMillis()
        val done = AtomicBoolean(false)
        val watchdog = scope.launch {
            try {
                delay(WARMUP_TIMEOUT_MS)
                if (!done.get()) {
                    Log.w(CORE_TAG, "ANDROLLM_CORE_WARMUP_TIMEOUT ms=$WARMUP_TIMEOUT_MS")
                    ref.get()?.let { runCatching { it.cancelProcess() } }
                }
            } catch (_: CancellationException) {}
        }
        return try {
            withContext(Dispatchers.Default) {
                conv = createConversationWithFamilyFlags(
                    eng,
                    conversationConfig(
                        temperature = DEFAULT_TEMPERATURE,
                        topP = DEFAULT_TOP_P,
                        topK = DEFAULT_TOP_K,
                        seed = 0
                    )
                )
                ref.set(conv)
                conv!!.sendMessage(Message.user("Hi"), maxOutputToken = 1)
                done.set(true)
                (System.currentTimeMillis() - started).coerceAtLeast(1L)
            }
        } catch (t: Throwable) {
            done.set(true)
            Log.w(CORE_TAG, "ANDROLLM_CORE_WARMUP_FAILED ${t.message}")
            -1L
        } finally {
            watchdog.cancel()
            try { conv?.close() } catch (_: Throwable) {}
        }
    }

    /** Package-adapted equivalent of AndroLLM repository's one-time probeCoherence(). */
    private suspend fun runCoherenceSelfTest(eng: Engine): AndroLLMCoherenceResult {
        var conv: Conversation? = null
        val ref = AtomicReference<Conversation?>(null)
        val done = AtomicBoolean(false)
        val watchdog = scope.launch {
            try {
                delay(SELF_TEST_TIMEOUT_MS)
                if (!done.get()) {
                    Log.e(CORE_TAG, "ANDROLLM_CORE_SELFTEST_TIMEOUT ms=$SELF_TEST_TIMEOUT_MS")
                    ref.get()?.let { runCatching { it.cancelProcess() } }
                }
            } catch (_: CancellationException) {}
        }
        val text = try {
            withContext(Dispatchers.Default) {
                conv = createConversationWithFamilyFlags(
                    eng,
                    conversationConfig(
                        temperature = 0.0,
                        topP = DEFAULT_TOP_P,
                        topK = DEFAULT_TOP_K,
                        seed = 0
                    )
                )
                ref.set(conv)
                val message = conv!!.sendMessage(
                    Message.user("Hi"),
                    maxOutputToken = SELF_TEST_MAX_TOKENS,
                    thinkingConfig = thinkingConfig()
                )
                val raw = AndroMessageText.extract(message)
                done.set(true)
                (outputDecoder?.clean(AndroMessageText.cleanSerialized(raw)) ?: AndroMessageText.cleanSerialized(raw)).trim()
            }
        } catch (t: Throwable) {
            done.set(true)
            Log.e(CORE_TAG, "ANDROLLM_CORE_SELFTEST_EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
            return AndroLLMCoherenceResult.Fail(
                if (t.message.isNullOrBlank()) "self-test generation failed" else t.message!!
            )
        } finally {
            watchdog.cancel()
            try { conv?.close() } catch (_: Throwable) {}
        }

        val coherence = AndroLLMCoherenceChecker.check(text)
        if (coherence !is AndroLLMCoherenceResult.Pass) return coherence
        val leaked = CONTROL_TOKEN_MARKERS.firstOrNull { text.contains(it) }
        if (leaked != null) {
            return AndroLLMCoherenceResult.Fail(
                "model decodes the '$leaked' control token instead of assistant text; template/tokenizer mismatch"
            )
        }
        return AndroLLMCoherenceResult.Pass
    }

    /**
     * Exact AndroLLM family flag enforcement point. The flag is global and is
     * read by nativeCreateConversation, therefore it is set immediately before
     * every Conversation creation.
     */
    @OptIn(ExperimentalApi::class)
    private fun createConversationWithFamilyFlags(
        eng: Engine,
        config: ConversationConfig,
        preserveModelMultimodalTemplate: Boolean = false
    ): Conversation {
        val fam = familyConfig
        if (preserveModelMultimodalTemplate) {
            // Multimodal LiteRT-LM bundles (Gemma 3n, etc.) carry modality-aware
            // prompt metadata/templates that insert the image/audio placeholders the
            // native data processor expects. Replacing that template with AndroLLM's
            // text-only family template makes Content.ImageBytes arrive with zero
            // rendered image placeholders and LiteRT-LM rejects the turn with:
            // "Provided more images than expected in the prompt."
            // Keep the native model template for vision turns only. Text inference
            // continues to use the exact AndroLLM family template below.
            ExperimentalFlags.overwritePromptTemplate = null
            ExperimentalFlags.filterChannelContentFromKvCache = fam?.thinkingChannel != null
        } else if (fam == null) {
            ExperimentalFlags.overwritePromptTemplate = null
            ExperimentalFlags.filterChannelContentFromKvCache = false
        } else {
            ExperimentalFlags.overwritePromptTemplate = fam.chatTemplate
            ExperimentalFlags.filterChannelContentFromKvCache = fam.thinkingChannel != null
        }
        return eng.createConversation(config)
    }

    /**
     * Gemma's official/AndroLLM lenient chat template intentionally omits a
     * `system` role. FixSight still needs one stable assistant contract, so for
     * Gemma only we fold the system instruction into the FIRST user message.
     *
     * This keeps AndroLLM's family template and native Conversation/KV ownership
     * intact while ensuring the instruction actually reaches the model.
     */
    private fun foldsSystemIntoFirstUser(): Boolean =
        familyConfig?.family == ModelFamily.GEMMA

    private fun foldedSystemUserText(systemPrompt: String, userText: String): String =
        buildString {
            append("INSTRUCTIONS FOR THE ASSISTANT:\n")
            append(systemPrompt.trim())
            append("\n\nUSER REQUEST:\n")
            append(userText.trim())
        }

    private fun nativeSeedMessages(
        seed: List<AndroLLMCoreMessage>,
        systemPrompt: String?
    ): List<Message> {
        if (seed.isEmpty()) return emptyList()
        val fold = foldsSystemIntoFirstUser() && !systemPrompt.isNullOrBlank()
        var folded = false
        return seed.map { m ->
            if (m.role == "assistant" || m.role == "model") {
                Message.model(m.content)
            } else {
                val content = if (fold && !folded) {
                    folded = true
                    foldedSystemUserText(systemPrompt.orEmpty(), m.content)
                } else m.content
                Message.user(content)
            }
        }
    }

    private fun nativeCurrentUserText(
        currentUser: String,
        seed: List<AndroLLMCoreMessage>,
        systemPrompt: String?
    ): String =
        if (foldsSystemIntoFirstUser() && seed.isEmpty() && !systemPrompt.isNullOrBlank()) {
            foldedSystemUserText(systemPrompt.orEmpty(), currentUser)
        } else {
            currentUser.trim()
        }

    private fun conversationConfig(
        initialMessages: List<Message> = emptyList(),
        systemPrompt: String? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        topP: Double = DEFAULT_TOP_P,
        topK: Int = DEFAULT_TOP_K,
        seed: Int = 0
    ): ConversationConfig {
        val fam = familyConfig
        val base = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP,
                temperature = temperature,
                seed = seed.coerceAtLeast(0)
            ),
            initialMessages = initialMessages,
            channels = fam?.thinkingChannel?.let {
                listOf(LiteRtChannel(it.channelName, it.start, it.end))
            }
        )
        return if (systemPrompt.isNullOrBlank() || foldsSystemIntoFirstUser()) {
            base
        } else {
            base.copy(systemInstruction = com.google.ai.edge.litertlm.Contents.of(systemPrompt))
        }
    }

    fun ensureConversationForHistory(
        history: List<AndroLLMCoreMessage>,
        systemPrompt: String
    ): Boolean {
        val eng = engine ?: return false
        val seed = sanitizeSeed(history)
        val lookupStarted = System.currentTimeMillis()

        conversationLock.lock()
        return try {
            val live = conversation
            val canReuse = !reuseBroken.get() &&
                live != null &&
                conversationSystemPrompt == systemPrompt &&
                seed == consumedTurns

            if (canReuse) {
                lastSessionReused = true
                Log.i(
                    CORE_TAG,
                    "ANDROLLM_CORE_SESSION_LOOKUP reuse=true turns=${seed.size} " +
                        "ms=${System.currentTimeMillis() - lookupStarted} kvTokens=${getTokenCountUnsafe(live)}"
                )
                Log.i(CORE_TAG, "ANDROLLM_SESSION_LOOKUP reuse=true turns=${seed.size} kvTokens=${getTokenCountUnsafe(live)}")
                true
            } else {
                val reason = when {
                    reuseBroken.get() -> "template_drift_latch"
                    live == null -> "no_resident_session"
                    conversationSystemPrompt != systemPrompt -> "system_prompt_changed"
                    else -> "history_changed"
                }
                try { live?.close() } catch (_: Throwable) {}
                val native = nativeSeedMessages(seed, systemPrompt)
                conversation = createConversationWithFamilyFlags(
                    eng,
                    conversationConfig(initialMessages = native, systemPrompt = systemPrompt)
                )
                conversationSystemPrompt = systemPrompt
                consumedTurns = seed
                lastSessionReused = false
                Log.i(
                    CORE_TAG,
                    "ANDROLLM_CORE_SESSION_LOOKUP reuse=false reason=$reason turns=${seed.size} " +
                        "ms=${System.currentTimeMillis() - lookupStarted}"
                )
                Log.i(CORE_TAG, "ANDROLLM_SESSION_LOOKUP reuse=false reason=$reason turns=${seed.size}")
                true
            }
        } catch (t: Throwable) {
            Log.e(CORE_TAG, "ANDROLLM_CORE_SESSION_CREATE_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
            try { conversation?.close() } catch (_: Throwable) {}
            conversation = null
            conversationSystemPrompt = null
            consumedTurns = emptyList()
            lastSessionReused = false
            false
        } finally {
            conversationLock.unlock()
        }
    }

    /**
     * AndroLLM's actual chat continuation path, adapted to return one complete
     * result because FixSight deliberately validates the full answer before TTS.
     */
    suspend fun generateChat(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String,
        maxOutputToken: Int,
        onDelta: ((String) -> Unit)? = null
    ): AndroLLMCoreGeneration {
        val eng = engine ?: return failed("LOCAL_NOT_READY")
        if (!ensureConversationForHistory(history, systemPrompt)) return failed("NO_CONVERSATION")
        if (!acquireGenerationSlot()) return failed("GENERATION_ALREADY_IN_PROGRESS")

        cancelEphemeralGeneration()
        val startedAt = System.currentTimeMillis()
        val historyForTurn = sanitizeSeed(history)
        val nativeCurrentUser = nativeCurrentUserText(currentUser, historyForTurn, systemPrompt)
        val messages = historyForTurn + AndroLLMCoreMessage("user", currentUser)
        var conv = currentConversation() ?: run {
            generationActive.set(false)
            return failed("NO_CONVERSATION")
        }
        val reusedAtStart = lastSessionReused
        val raw = StringBuilder()
        val emittedClean = StringBuilder()
        val tracker = StopSequenceTracker(effectiveStopSequences)
        val loopGuard = AndroGenerationLoopGuard()
        val anyCallbackSeen = AtomicBoolean(false)
        val callbackCount = AtomicLong(0L)
        val lastCallbackAt = AtomicLong(System.currentTimeMillis())
        val promptChars = messages.sumOf { it.content.length } + currentUser.length + (systemPrompt?.length ?: 0)
        val firstCallbackBudgetMs = firstCallbackTimeoutMs(promptChars)
        val stopDetected = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val tokenCount = AtomicLong(0L)
        val result = CompletableDeferred<AndroLLMCoreGeneration>()
        var sendAttempts = 0

        /*
         * Actual AndroLLM boundary:
         *   MessageText.extract(partial) -> accumulated raw text
         *   -> NativeToolCallScanner.strip(accumulated)
         *   -> OutputDecoder.clean(accumulated)
         *   -> OutputSanitizer at the presentation/chat boundary.
         *
         * Do not decode individual fragments and do not invent a JSON-prefix
         * quarantine. Accumulated cleaning is what prevents partial native tool
         * syntax / serialized control content from becoming visible speech.
         */
        fun decodedAccumulated(rawText: String): String {
            val stripped = NativeToolCallScanner.strip(rawText)
            return outputDecoder?.clean(stripped) ?: stripped
        }

        fun clean(rawText: String): String =
            FixSightOutputSanitizer.sanitize(decodedAccumulated(rawText)).trim()

        fun streamingClean(rawText: String): String =
            FixSightOutputSanitizer.streamingReady(decodedAccumulated(rawText))

        fun tokenCountAfter(): Int = getConversationTokenCount()

        fun complete(
            text: String,
            completed: Boolean,
            error: String?,
            stopReason: String,
            persistTranscript: Boolean
        ) {
            if (!terminal.compareAndSet(false, true)) return
            val finalText = text.trim()
            if (persistTranscript && completed && finalText.isNotBlank() && conversation != null) {
                consumedTurns = messages + AndroLLMCoreMessage("assistant", finalText)
            }
            result.complete(
                AndroLLMCoreGeneration(
                    text = finalText,
                    inferenceMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
                    completed = completed && finalText.isNotBlank(),
                    tokenCountAfter = tokenCountAfter(),
                    error = error,
                    sessionReused = reusedAtStart,
                    stopReason = stopReason
                )
            )
        }

        fun retireAndComplete(text: String, reason: String) {
            if (stopDetected.compareAndSet(false, true)) {
                scope.launch(Dispatchers.Default) {
                    try { conv.cancelProcess() } catch (_: Throwable) {}
                    conversationLock.lock()
                    try {
                        try { if (conversation === conv) conversation?.close() else conv.close() } catch (_: Throwable) {}
                        if (conversation === conv) {
                            conversation = null
                            conversationSystemPrompt = null
                            consumedTurns = emptyList()
                        }
                    } finally {
                        conversationLock.unlock()
                    }
                    complete(text, text.isNotBlank(), if (text.isBlank()) "EMPTY_GENERATION" else null, reason, false)
                }
            }
        }

        lateinit var callback: MessageCallback

        fun retryOnFreshSession(reason: String, latchReuseBroken: Boolean) {
            if (sendAttempts >= MAX_STREAM_SEND_ATTEMPTS - 1 || terminal.get()) {
                complete("", false, reason, "recovery_exhausted", false)
                return
            }
            sendAttempts++
            callbackCount.set(0L)
            lastCallbackAt.set(System.currentTimeMillis())
            if (latchReuseBroken) reuseBroken.set(true)
            Log.w(CORE_TAG, "ANDROLLM_CORE_STREAM_RECOVERY reason=$reason attempt=${sendAttempts + 1}")
            Log.w(CORE_TAG, "ANDROLLM_STREAM_RETRY reason=$reason attempt=${sendAttempts + 1}")
            scope.launch(Dispatchers.Default) {
                try {
                    runCatching { conv.cancelProcess() }
                    conv = reseedAfterOverflow(eng, messages, systemPrompt)
                    lastSessionReused = false
                    conv.sendMessageAsync(
                        Message.user(nativeCurrentUser),
                        callback,
                        maxOutputToken = boundedOutputTokens(maxOutputToken),
                        thinkingConfig = thinkingConfig()
                    )
                } catch (t: Throwable) {
                    complete(
                        "",
                        false,
                        t.javaClass.simpleName + ":" + (t.message ?: "generation failed"),
                        "recovery_failed",
                        false
                    )
                }
            }
        }

        callback = object : MessageCallback {
            override fun onMessage(partial: Message) {
                anyCallbackSeen.set(true)
                if (terminal.get() || stopDetected.get()) return
                try {
                    val fragment = AndroMessageText.extract(partial)
                    if (fragment.isNotEmpty()) {
                        callbackCount.incrementAndGet()
                        lastCallbackAt.set(System.currentTimeMillis())
                    }
                    if (fragment.isEmpty()) return
                    tokenCount.incrementAndGet()
                    raw.append(fragment)

                    if (tracker.feed(fragment) != null) {
                        val cut = raw.substring(0, tracker.stopStartIndex.toInt().coerceIn(0, raw.length))
                        retireAndComplete(clean(cut), "stop_sequence")
                        return
                    }
                    if (loopGuard.feed(fragment)) {
                        Log.w(CORE_TAG, "ANDROLLM_CORE_LOOP_DETECTED ${loopGuard.detail}")
                        retireAndComplete(clean(raw.toString()), "loop_detected")
                        return
                    }

                    // Mirror AndroLLM's holdback so a split stop marker is never
                    // considered user-visible output before the marker completes.
                    val emissionPoint = (raw.length - tracker.holdbackLength).coerceAtLeast(0)
                    val fullClean = streamingClean(raw.substring(0, emissionPoint))
                    // AndroLLM-style user-visible delta emission. OutputDecoder can
                    // normalize early fragments, so emit only when the clean prefix
                    // remains stable; otherwise wait for a later callback/final text.
                    val delta = fullClean.removePrefix(emittedClean.toString())
                    if (delta.isNotEmpty()) {
                        emittedClean.append(delta)
                        try { onDelta?.invoke(delta) }
                        catch (t: Throwable) {
                            Log.w(CORE_TAG, "ANDROLLM_CORE_DELTA_CONSUMER_FAILED ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    complete("", false, t.javaClass.simpleName + ":" + (t.message ?: "callback failed"), "callback_error", false)
                }
            }

            override fun onDone() {
                anyCallbackSeen.set(true)
                lastCallbackAt.set(System.currentTimeMillis())
                if (terminal.get() || stopDetected.get()) return
                try {
                    val streamFinal = streamingClean(raw.toString())
                    val tail = streamFinal.removePrefix(emittedClean.toString())
                    if (tail.isNotEmpty()) {
                        emittedClean.append(tail)
                        try { onDelta?.invoke(tail) }
                        catch (t: Throwable) { Log.w(CORE_TAG, "ANDROLLM_CORE_DELTA_CONSUMER_FAILED ${t.message}") }
                    }
                    val finalText = clean(raw.toString())
                    complete(
                        finalText,
                        finalText.isNotBlank(),
                        if (finalText.isBlank()) "EMPTY_GENERATION" else null,
                        "eos",
                        finalText.isNotBlank()
                    )
                } catch (t: Throwable) {
                    complete("", false, t.javaClass.simpleName + ":" + (t.message ?: "onDone failed"), "done_error", false)
                }
            }

            override fun onError(error: Throwable) {
                anyCallbackSeen.set(true)
                lastCallbackAt.set(System.currentTimeMillis())
                if (terminal.get()) return
                if (stopDetected.get()) return
                if (sendAttempts < MAX_STREAM_SEND_ATTEMPTS - 1 && isReseedable(error)) {
                    retryOnFreshSession(error.message ?: "recoverable send failure", isReuseMismatch(error))
                } else {
                    complete(
                        "",
                        false,
                        error.javaClass.simpleName + ":" + (error.message ?: "generation failed"),
                        "native_error",
                        false
                    )
                }
            }
        }

        // Direct adaptation of AndroLLM DefaultEngineRepository stall protection.
        // The watchdog runs on the engine-owned multi-threaded scope, so it still
        // executes if a native send/callback thread wedges. First-token budget scales
        // with prompt size; once decoding starts, 30s without a callback is a stall.
        val watchdog: Job = scope.launch {
            try {
                while (!terminal.get() && !stopDetected.get()) {
                    delay(WATCHDOG_POLL_MS)
                    val seen = callbackCount.get()
                    val budget = if (seen == 0L) firstCallbackBudgetMs else INTER_CALLBACK_TIMEOUT_MS
                    val idleFor = System.currentTimeMillis() - lastCallbackAt.get()
                    if (idleFor <= budget) continue

                    Log.e(
                        CORE_TAG,
                        "ANDROLLM_CORE_STALL idle=${idleFor}ms budget=${budget}ms " +
                            "phase=${if (seen == 0L) "first_callback" else "mid_decode"} reused=$reusedAtStart"
                    )
                    if (seen == 0L && reusedAtStart && sendAttempts < MAX_STREAM_SEND_ATTEMPTS - 1) {
                        lastCallbackAt.set(System.currentTimeMillis())
                        retryOnFreshSession("STALLED_REUSED_SESSION_NO_CALLBACK", false)
                        continue
                    }

                    withContext(Dispatchers.Default) {
                        runCatching { conv.cancelProcess() }
                        conversationLock.lock()
                        try {
                            runCatching { if (conversation === conv) conversation?.close() else conv.close() }
                            if (conversation === conv) {
                                conversation = null
                                conversationSystemPrompt = null
                                consumedTurns = emptyList()
                                lastSessionReused = false
                            }
                        } finally {
                            conversationLock.unlock()
                        }
                    }
                    complete(
                        "",
                        false,
                        "GENERATION_STALLED_${if (seen == 0L) "FIRST_CALLBACK" else "MID_DECODE"}",
                        "watchdog_stall",
                        false
                    )
                    break
                }
            } catch (_: CancellationException) {
                // Normal completion cancels the watchdog.
            } catch (t: Throwable) {
                Log.w(CORE_TAG, "ANDROLLM_CORE_WATCHDOG_FAILED ${t.message}")
            }
        }

        try {
            Log.i(
                CORE_TAG,
                "ANDROLLM_CORE_GENERATE reused=$reusedAtStart historyTurns=${historyForTurn.size} " +
                    "kvTokens=${getConversationTokenCount()} maxOutput=${boundedOutputTokens(maxOutputToken)}"
            )
            try {
                // AndroLLM invariant: potentially blocking LiteRT JNI is never
                // invoked from Android Main. Keep it on the engine worker.
                withContext(Dispatchers.Default) {
                    conv.sendMessageAsync(
                        Message.user(nativeCurrentUser),
                        callback,
                        maxOutputToken = boundedOutputTokens(maxOutputToken),
                        thinkingConfig = thinkingConfig()
                    )
                }
            } catch (t: Throwable) {
                complete(
                    "",
                    false,
                    t.javaClass.simpleName + ":" + (t.message ?: "send failed"),
                    "send_failed",
                    false
                )
            }
            return result.await()
        } catch (ce: CancellationException) {
            // Same ownership pattern as AndroLLM callbackFlow cancellation:
            // the cancelled collector performs native unwind off Main, then the
            // turn job is allowed to finish. NonCancellable guarantees cleanup.
            withContext(NonCancellable + Dispatchers.Default) {
                try { conv.cancelProcess() } catch (_: Throwable) {}
                try { conv.close() } catch (_: Throwable) {}
                conversationLock.lock()
                try {
                    if (conversation === conv) {
                        conversation = null
                        conversationSystemPrompt = null
                        consumedTurns = emptyList()
                        lastSessionReused = false
                    }
                } finally {
                    conversationLock.unlock()
                }
            }
            throw ce
        } finally {
            watchdog.cancel()
            generationActive.set(false)
        }
    }

    /**
     * Throwaway generation for FixSight's detached evidence/memory helpers.
     * It uses the SAME model-family/template/sampler contract but never mutates
     * the resident public chat Conversation or its KV bookkeeping.
     */
    suspend fun generateEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double = DEFAULT_TEMPERATURE,
        /** Raw text fragments while the answer is written (preview only; the final text is cleaned). */
        onPartial: ((String) -> Unit)? = null
    ): AndroLLMCoreGeneration {
        val eng = engine ?: return failed("LOCAL_NOT_READY")
        if (!acquireGenerationSlot()) return failed("GENERATION_ALREADY_IN_PROGRESS")
        val started = System.currentTimeMillis()
        var conv: Conversation? = null
        var watchdog: Job? = null
        val terminal = AtomicBoolean(false)
        val callbackCount = AtomicLong(0L)
        val lastCallbackAt = AtomicLong(started)
        val raw = StringBuilder()
        val result = CompletableDeferred<AndroLLMCoreGeneration>()
        // Same loop detector as resident chat: a degenerate repetition is stopped within seconds
        // instead of running to the output limit; the text before it is kept (callers trim the tail).
        val loopGuard = AndroGenerationLoopGuard()
        // Completed when the native decode has really ended (onDone/onError). The Conversation is
        // closed only after that: closing it while a cancelled decode is still unwinding freed
        // native memory under the decoder thread (SIGSEGV after ANDROLLM_EPHEMERAL_LOOP_GUARD).
        val nativeEnded = CompletableDeferred<Unit>()

        fun finish(text: String, completed: Boolean, error: String?, stopReason: String) {
            if (!terminal.compareAndSet(false, true)) return
            val plain = AndroMessageText.cleanSerialized(text)
            val clean = (outputDecoder?.clean(plain) ?: plain).trim()
            result.complete(
                AndroLLMCoreGeneration(
                    text = clean,
                    inferenceMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
                    completed = completed && clean.isNotBlank(),
                    tokenCountAfter = 0,
                    error = if (completed && clean.isNotBlank()) error else error ?: "EMPTY_GENERATION",
                    sessionReused = false,
                    stopReason = stopReason
                )
            )
        }

        try {
            val seed = sanitizeSeed(history)
            val native = nativeSeedMessages(seed, systemPrompt)
            val nativeCurrentUser = nativeCurrentUserText(currentUser, seed, systemPrompt)
            conv = createConversationWithFamilyFlags(
                eng,
                conversationConfig(
                    initialMessages = native,
                    systemPrompt = systemPrompt,
                    temperature = temperature,
                    topP = DEFAULT_TOP_P,
                    topK = DEFAULT_TOP_K,
                    seed = 0
                )
            )
            activeEphemeralConversation = conv

            val promptChars = currentUser.length + (systemPrompt?.length ?: 0) + history.sumOf { it.content.length }
            val firstCallbackBudgetMs = firstCallbackTimeoutMs(promptChars)
            val hardTimeoutMs = hardGenerationTimeoutMs(maxOutputToken)

            val callback = object : MessageCallback {
                override fun onMessage(partial: Message) {
                    if (terminal.get()) return
                    try {
                        val fragment = AndroMessageText.extract(partial)
                        if (fragment.isNotEmpty()) {
                            callbackCount.incrementAndGet()
                            lastCallbackAt.set(System.currentTimeMillis())
                            raw.append(fragment)
                            if (onPartial != null && !terminal.get()) runCatching { onPartial(fragment) }
                            if (loopGuard.feed(fragment)) {
                                Log.w(CORE_TAG, "ANDROLLM_EPHEMERAL_LOOP_GUARD ${loopGuard.detail} chars=${raw.length}")
                                finish(raw.toString(), raw.isNotEmpty(), null, "ephemeral_loop_guard")
                                val live = conv
                                // Cancel only; the Conversation is closed in `finally` once the
                                // native decode has ended (nativeEnded).
                                scope.launch(Dispatchers.Default) { runCatching { live?.cancelProcess() } }
                            }
                        }
                    } catch (t: Throwable) {
                        finish("", false, t.javaClass.simpleName + ":" + (t.message ?: "ephemeral callback failed"), "ephemeral_callback_error")
                    }
                }

                override fun onDone() {
                    lastCallbackAt.set(System.currentTimeMillis())
                    finish(raw.toString(), raw.isNotEmpty(), null, "ephemeral_eos")
                    nativeEnded.complete(Unit)
                }

                override fun onError(error: Throwable) {
                    lastCallbackAt.set(System.currentTimeMillis())
                    finish("", false, error.javaClass.simpleName + ":" + (error.message ?: "ephemeral generation failed"), "ephemeral_native_error")
                    nativeEnded.complete(Unit)
                }
            }

            // Same execution contract as AndroLLM generateQuiet/chat generation: a
            // caller coroutine timeout alone cannot reliably interrupt blocking JNI.
            // This engine-owned watchdog actively aborts the native Conversation on a
            // true first-token/mid-decode stall while allowing healthy long answers to
            // continue as long as callbacks keep arriving.
            watchdog = scope.launch {
                try {
                    while (!terminal.get()) {
                        delay(WATCHDOG_POLL_MS)
                        val now = System.currentTimeMillis()
                        val seen = callbackCount.get()
                        val idleBudget = if (seen == 0L) firstCallbackBudgetMs else INTER_CALLBACK_TIMEOUT_MS
                        val idleFor = now - lastCallbackAt.get()
                        val totalFor = now - started
                        val stalled = idleFor > idleBudget
                        val hardExpired = totalFor > hardTimeoutMs
                        if (!stalled && !hardExpired) continue

                        val reason = if (hardExpired) "EPHEMERAL_HARD_TIMEOUT" else
                            "EPHEMERAL_STALLED_${if (seen == 0L) "FIRST_CALLBACK" else "MID_DECODE"}"
                        Log.e(
                            CORE_TAG,
                            "ANDROLLM_EPHEMERAL_WATCHDOG reason=$reason idle=${idleFor}ms " +
                                "budget=${idleBudget}ms total=${totalFor}ms hard=${hardTimeoutMs}ms"
                        )
                        val live = conv
                        runCatching { live?.cancelProcess() }
                        // The Conversation is closed in `finally`, after the native decode ended.
                        finish("", false, reason, "ephemeral_watchdog")
                        break
                    }
                } catch (_: CancellationException) {
                    // Normal completion/caller cancellation.
                } catch (t: Throwable) {
                    Log.w(CORE_TAG, "ANDROLLM_EPHEMERAL_WATCHDOG_FAILED ${t.message}")
                }
            }

            withContext(Dispatchers.Default) {
                conv.sendMessageAsync(
                    Message.user(nativeCurrentUser),
                    callback,
                    maxOutputToken = boundedOutputTokens(maxOutputToken),
                    thinkingConfig = thinkingConfig()
                )
            }
            return result.await()
        } catch (ce: CancellationException) {
            // Make coroutine deadlines real: actively abort native decode before
            // propagating cancellation, exactly like AndroLLM's quiet-generation
            // watchdog. This prevents a timed-out planner/KB retry from holding the
            // generation slot for tens of seconds after the caller has moved on.
            withContext(NonCancellable + Dispatchers.Default) {
                runCatching { conv?.cancelProcess() }
            }
            throw ce
        } catch (t: Throwable) {
            return AndroLLMCoreGeneration(
                text = "",
                inferenceMs = System.currentTimeMillis() - started,
                completed = false,
                tokenCountAfter = 0,
                error = t.javaClass.simpleName + ":" + (t.message ?: "ephemeral generation failed"),
                stopReason = "ephemeral_error"
            )
        } finally {
            watchdog?.cancel()
            if (activeEphemeralConversation === conv) activeEphemeralConversation = null
            withContext(NonCancellable + Dispatchers.Default) {
                val c = conv
                if (c != null) {
                    // A stopped generation (loop guard, watchdog, cancellation) may still be unwinding
                    // in native code: cancel it and wait until it reports the end before closing.
                    if (!nativeEnded.isCompleted) {
                        runCatching { c.cancelProcess() }
                        val ended = withTimeoutOrNull(EPHEMERAL_NATIVE_END_WAIT_MS) { nativeEnded.await() } != null
                        if (!ended) {
                            // Give the decoder an extra grace period before closing (as before this fix).
                            Log.w(CORE_TAG, "ANDROLLM_EPHEMERAL_CLOSE_DEFERRED native decode did not end within ${EPHEMERAL_NATIVE_END_WAIT_MS}ms")
                            delay(EPHEMERAL_UNWIND_GRACE_MS)
                        }
                    }
                    runCatching { c.close() }
                }
            }
            generationActive.set(false)
        }
    }


    /**
     * Throwaway image+text generation for a selected multimodal LiteRT-LM bundle.
     * The image never enters resident public-chat KV; accepted text is committed by
     * FixSight's normal turn boundary. This keeps model switching safe and makes the
     * text-only path unchanged.
     */
    suspend fun generateVisionEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        imageBytes: ByteArray,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double = DEFAULT_TEMPERATURE
    ): AndroLLMCoreGeneration {
        val eng = engine ?: return failed("LOCAL_NOT_READY")
        if (!visionSupported) return failed("LOCAL_VISION_NOT_SUPPORTED")
        if (imageBytes.isEmpty()) return failed("LOCAL_VISION_IMAGE_EMPTY")
        if (!acquireGenerationSlot()) return failed("GENERATION_ALREADY_IN_PROGRESS")
        val started = System.currentTimeMillis()
        var conv: Conversation? = null
        try {
            val seed = sanitizeSeed(history)
            val native = nativeSeedMessages(seed, systemPrompt)
            val nativeCurrentUser = nativeCurrentUserText(currentUser, seed, systemPrompt)
            conv = createConversationWithFamilyFlags(
                eng,
                conversationConfig(
                    initialMessages = native,
                    systemPrompt = systemPrompt,
                    temperature = temperature,
                    topP = DEFAULT_TOP_P,
                    topK = DEFAULT_TOP_K,
                    seed = 0
                ),
                preserveModelMultimodalTemplate = true
            )
            Log.i(
                CORE_TAG,
                "ANDROLLM_CORE_VISION_TEMPLATE strategy=model_native_multimodal " +
                    "visionSupported=$visionSupported imageBytes=${imageBytes.size}"
            )
            activeEphemeralConversation = conv
            val input = Message.user(
                Contents.of(
                    listOf(
                        Content.Text(nativeCurrentUser),
                        Content.ImageBytes(imageBytes)
                    )
                )
            )
            val raw = withContext(Dispatchers.Default) {
                val message = conv.sendMessage(
                    input,
                    maxOutputToken = boundedOutputTokens(maxOutputToken),
                    thinkingConfig = thinkingConfig()
                )
                AndroMessageText.extract(message)
            }
            val plain = AndroMessageText.cleanSerialized(raw)
            val clean = (outputDecoder?.clean(plain) ?: plain).trim()
            return AndroLLMCoreGeneration(
                text = clean,
                inferenceMs = System.currentTimeMillis() - started,
                completed = clean.isNotBlank(),
                tokenCountAfter = 0,
                error = if (clean.isBlank()) "EMPTY_GENERATION" else null,
                sessionReused = false,
                stopReason = "vision_ephemeral_eos"
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return AndroLLMCoreGeneration(
                text = "",
                inferenceMs = System.currentTimeMillis() - started,
                completed = false,
                tokenCountAfter = 0,
                error = t.javaClass.simpleName + ":" + (t.message ?: "vision generation failed"),
                stopReason = "vision_ephemeral_error"
            )
        } finally {
            if (activeEphemeralConversation === conv) activeEphemeralConversation = null
            try { conv?.close() } catch (_: Throwable) {}
            generationActive.set(false)
        }
    }

    fun cancelEphemeralGeneration() {
        val conv = activeEphemeralConversation ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scope.launch(Dispatchers.Default) { runCatching { conv.cancelProcess() } }
        } else {
            runCatching { conv.cancelProcess() }
        }
    }

    /** Detach the resident Conversation without invoking blocking native code. */
    private fun detachResidentConversation(): Conversation? {
        conversationLock.lock()
        return try {
            val live = conversation
            conversation = null
            conversationSystemPrompt = null
            consumedTurns = emptyList()
            lastSessionReused = false
            live
        } finally {
            conversationLock.unlock()
        }
    }

    /**
     * UI-safe retirement used by Activity/service teardown. Native close runs on
     * the AndroLLM engine worker scope, never on Android Main.
     */
    fun retireSessionAsync(reason: String) {
        val live = detachResidentConversation() ?: return
        scope.launch(Dispatchers.Default) {
            Log.i(CORE_TAG, "ANDROLLM_CORE_RETIRE_ASYNC reason=$reason")
            runCatching { live.close() }
        }
    }

    /**
     * UI-safe cancellation request. This mirrors AndroLLM's voice contract:
     * voice/UI cancels its owning job; if an explicit native abort is still
     * needed (watchdog/teardown), it is dispatched to the engine scope.
     */
    fun requestCancelActiveGeneration(reason: String) {
        val ephemeral = activeEphemeralConversation
        val live = detachResidentConversation()
        scope.launch(Dispatchers.Default) {
            Log.i(CORE_TAG, "ANDROLLM_CORE_CANCEL_ASYNC reason=$reason")
            if (ephemeral != null) runCatching { ephemeral.cancelProcess() }
            if (live != null) {
                runCatching { live.cancelProcess() }
                runCatching { live.close() }
            }
        }
    }

    fun closeSession() {
        // Native Conversation.close() is blocking. If a legacy/UI caller reaches
        // this method, detach immediately and close on the engine scope. Worker
        // callers preserve sequencing by closing synchronously off Main.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            retireSessionAsync("main_thread_close")
            return
        }
        val live = detachResidentConversation()
        try { live?.close() } catch (_: Throwable) {}
    }

    fun cancelActiveGeneration() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            requestCancelActiveGeneration("main_thread_cancel")
            return
        }
        cancelEphemeralGeneration()
        val live = detachResidentConversation()
        try { live?.cancelProcess() } catch (_: Throwable) {}
        try { live?.close() } catch (_: Throwable) {}
    }

    fun getConversationTokenCount(): Int {
        conversationLock.lock()
        return try {
            getTokenCountUnsafe(conversation)
        } finally {
            conversationLock.unlock()
        }
    }

    private fun getTokenCountUnsafe(conv: Conversation?): Int = try {
        conv?.getTokenCount() ?: 0
    } catch (t: Throwable) {
        Log.w(CORE_TAG, "ANDROLLM_CORE_TOKEN_COUNT_UNAVAILABLE ${t.javaClass.simpleName}: ${t.message}")
        0
    }

    fun markAcceptedTurn(userText: String, assistantText: String) {
        // On natural EOS the upstream chat engine already persists exactly the
        // generated assistant text in its internal transcript bookkeeping. This
        // adapter hook exists only to verify alignment with FixSight's accepted
        // truth; if it diverges, retire so the next turn reseeds authoritatively.
        val u = userText.trim()
        val a = assistantText.trim()
        val tail = consumedTurns.takeLast(2)
        val nativeVisibleAssistant = tail.lastOrNull()
            ?.takeIf { it.role == "assistant" }
            ?.content
            ?.let { FixSightOutputSanitizer.compactForLive(it).trim() }
            .orEmpty()
        val aligned = tail.size == 2 && tail[0].role == "user" && tail[0].content == u &&
            tail[1].role == "assistant" && nativeVisibleAssistant == a
        if (!aligned && conversation != null) {
            Log.w(CORE_TAG, "ANDROLLM_CORE_ACCEPTED_TRANSCRIPT_MISMATCH — retiring resident session")
            closeSession()
        }
    }

    suspend fun recoverOnCpu(onProgress: (String) -> Unit = {}): Boolean {
        val path = loadedModelPath ?: return false
        if (selectedBackend == BackendType.CPU && engine != null) return true
        closeSession()
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        val result = tryLoadBackend(path, BackendType.CPU, onProgress)
        return result.success
    }

    suspend fun runSelfTest(): Boolean {
        val eng = engine ?: return false
        return runCoherenceSelfTest(eng) is AndroLLMCoherenceResult.Pass
    }

    /**
     * AndroLLM-style process/activity teardown: never wait on native LiteRT from
     * Android Main. Give the owning generation job a short chance to unwind;
     * then retire Conversation/Engine on the engine worker scope.
     */
    fun shutdownAsync(reason: String) {
        scope.launch(Dispatchers.Default) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (generationActive.get() && System.nanoTime() < deadline) {
                delay(25L)
            }
            if (generationActive.get()) {
                Log.w(CORE_TAG, "ANDROLLM_CORE_SHUTDOWN_FORCE reason=$reason generationActive=true")
            }
            val live = detachResidentConversation()
            if (live != null) {
                runCatching { live.cancelProcess() }
                runCatching { live.close() }
            }
            activeEphemeralConversation?.let { ephemeral ->
                runCatching { ephemeral.cancelProcess() }
                runCatching { ephemeral.close() }
            }
            activeEphemeralConversation = null
            runCatching { engine?.close() }
            engine = null
            familyConfig = null
            outputDecoder = null
            effectiveStopSequences = emptyList()
            generationActive.set(false)
            Log.i(CORE_TAG, "ANDROLLM_CORE_SHUTDOWN_DONE reason=$reason")
        }
    }

    fun unload() {
        unloadInternal(evictMetadata = true)
    }

    private fun unloadInternal(evictMetadata: Boolean) {
        cancelActiveGeneration()
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        familyConfig = null
        outputDecoder = null
        effectiveStopSequences = emptyList()
        resolvedContextTokens = 0
        extendedContextTokens = 0
        selectedBackend = BackendType.AUTO
        reuseBroken.set(false)
        lastSessionReused = false
        if (evictMetadata) {
            loadedModelPath?.let { runCatching { ContainerMetadataReader.evictCache(File(it)) } }
        }
    }

    private fun currentConversation(): Conversation? {
        conversationLock.lock()
        return try { conversation } finally { conversationLock.unlock() }
    }

    private fun reseedAfterOverflow(
        eng: Engine,
        messages: List<AndroLLMCoreMessage>,
        systemPrompt: String
    ): Conversation {
        val trimmed = messages.takeLast(CONTEXT_TRIM_KEEP_TURNS)
        val reseed = sanitizeSeed(trimmed.dropLast(1))
        val native = nativeSeedMessages(reseed, systemPrompt)
        conversationLock.lock()
        return try {
            try { conversation?.close() } catch (_: Throwable) {}
            val fresh = createConversationWithFamilyFlags(
                eng,
                conversationConfig(initialMessages = native, systemPrompt = systemPrompt)
            )
            conversation = fresh
            conversationSystemPrompt = systemPrompt
            consumedTurns = reseed
            lastSessionReused = false
            fresh
        } finally {
            conversationLock.unlock()
        }
    }

    private fun sanitizeSeed(turns: List<AndroLLMCoreMessage>): List<AndroLLMCoreMessage> {
        val out = ArrayList<AndroLLMCoreMessage>(turns.size)
        var expectUser = true
        for (m in turns) {
            val role = m.role.lowercase()
            val isUser = role == "user"
            val isAssistant = role == "assistant" || role == "model"
            if (!isUser && !isAssistant) continue
            if (isUser != expectUser) continue
            out.add(AndroLLMCoreMessage(if (isUser) "user" else "assistant", m.content.trim()))
            expectUser = !expectUser
        }
        if (out.isNotEmpty() && out.last().role == "user") {
            out.removeAt(out.size - 1)
        }
        return out.filter { it.content.isNotBlank() }
    }

    private fun isContextOverflow(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("token ids are too long", ignoreCase = true) ||
            msg.contains("exceeding the maximum number of tokens", ignoreCase = true) ||
            msg.contains("context window", ignoreCase = true) ||
            msg.contains("prefill work group size exceeds available state entries", ignoreCase = true)
    }

    private fun isReuseMismatch(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("does not start with the previous rendered template string", ignoreCase = true) ||
            msg.contains("rendered template string", ignoreCase = true)
    }

    private fun isReseedable(e: Throwable): Boolean =
        isContextOverflow(e) || isReuseMismatch(e) ||
            (e.message ?: "").contains("STALLED_REUSED_SESSION_NO_CALLBACK", ignoreCase = true)

    private fun thinkingConfig() = ThinkingConfig(enableThinking = false)

    private fun boundedOutputTokens(requested: Int): Int =
        requested.takeIf { it > 0 && it < UNLIMITED_MAX_TOKENS_SENTINEL }
            ?.coerceAtMost(STREAMING_MAX_OUTPUT_TOKENS)
            ?.coerceAtLeast(16)
            ?: STREAMING_MAX_OUTPUT_TOKENS

    private suspend fun acquireGenerationSlot(): Boolean {
        if (generationActive.compareAndSet(false, true)) return true
        val deadline = System.nanoTime() + GENERATION_DRAIN_WAIT_NS
        while (generationActive.get() && System.nanoTime() < deadline) {
            delay(GENERATION_DRAIN_POLL_MS)
        }
        return generationActive.compareAndSet(false, true)
    }

    private fun detectMaxContext(eng: Engine): Int {
        val probePrompt = "The quick brown fox jumps over the lazy dog. ".repeat(800)
        val conv = try {
            createConversationWithFamilyFlags(eng, conversationConfig())
        } catch (e: Throwable) {
            Log.w(CORE_TAG, "ANDROLLM_CORE_CONTEXT_PROBE_CREATE_FAIL ${e.message}")
            return 0
        }
        return try {
            conv.sendMessage(Message.user(probePrompt), maxOutputToken = 1)
            0
        } catch (e: Throwable) {
            parseMaxContextLimit(e.message)
        } finally {
            runCatching { conv.close() }
        }
    }

    /** Probed model-default window from an earlier launch, keyed by model file name and size. */
    private fun rememberedProbedContext(modelPath: String): Int = runCatching {
        val f = File(context.filesDir, CONTEXT_PROBE_FILE)
        if (!f.exists()) return@runCatching 0
        val key = contextProbeKey(modelPath)
        f.readLines().firstOrNull { it.substringBeforeLast('=') == key }
            ?.substringAfterLast('=')?.trim()?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    private fun rememberProbedContext(modelPath: String, tokens: Int) {
        runCatching {
            val f = File(context.filesDir, CONTEXT_PROBE_FILE)
            val key = contextProbeKey(modelPath)
            val kept = if (f.exists()) f.readLines().filter { it.isNotBlank() && it.substringBeforeLast('=') != key } else emptyList()
            f.writeText((kept + "$key=$tokens").joinToString("\n") + "\n")
        }
    }

    private fun contextProbeKey(modelPath: String): String {
        val f = File(modelPath)
        return "${f.name}:${f.length()}"
    }

    private fun parseMaxContextLimit(message: String?): Int {
        val msg = message ?: return 0
        val idx = msg.indexOf(">=")
        if (idx < 0) return 0
        val after = msg.substring(idx + 2).trim()
        return after.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun failed(reason: String): AndroLLMCoreGeneration = AndroLLMCoreGeneration(
        text = "",
        inferenceMs = 0L,
        completed = false,
        tokenCountAfter = getConversationTokenCount(),
        error = reason,
        sessionReused = lastSessionReused,
        stopReason = "failed"
    )

    companion object {
        /**
         * KV-cache size requested for selected-KB answers when the model default is smaller.
         * Gemma 3n/Gemma 3 are trained for 32K context; 8K doubles a 4K default (about twice the
         * evidence) while keeping memory and prompt-processing time moderate. Falls back to the
         * model default automatically if the device cannot initialise it.
         */
        private const val KB_TARGET_CONTEXT_TOKENS = 8_192
        private const val CONTEXT_PROBE_FILE = "model_context_probe.txt"
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95
        const val DEFAULT_TEMPERATURE = 0.8
        /**
         * Knowledge-base answers are decoded greedily (no random sampling). At the chat default of
         * 0.8 a small model sometimes picks a less likely token while copying from a passage, which is
         * how "50%" became "5%" and "APBG" became "ePBG", and why the same question gave different
         * answers. Grounded answers should be the model's most likely reading of the evidence.
         */
        const val KB_ANSWER_TEMPERATURE = 0.0
        private const val CONTEXT_TRIM_KEEP_TURNS = 12
        private const val MAX_STREAM_SEND_ATTEMPTS = 2
        private const val FIRST_CALLBACK_TIMEOUT_FLOOR_MS = 5_000L
        private const val FIRST_CALLBACK_TIMEOUT_MAX_MS = 240_000L
        private const val MS_PER_PROMPT_TOKEN = 50L
        private const val WATCHDOG_POLL_MS = 500L
        private const val INTER_CALLBACK_TIMEOUT_MS = 30_000L
        private const val EPHEMERAL_UNWIND_GRACE_MS = 2_000L
        /** Longest wait for a cancelled native decode to report its end before the Conversation is closed. */
        private const val EPHEMERAL_NATIVE_END_WAIT_MS = 8_000L
        private const val HARD_GENERATION_FLOOR_MS = 300_000L
        private const val HARD_GENERATION_MAX_MS = 1_800_000L
        private const val MS_PER_GENERATED_TOKEN = 2_000L

        private fun hardGenerationTimeoutMs(maxTokens: Int): Long {
            val budget = maxTokens.coerceAtLeast(0).toLong() * MS_PER_GENERATED_TOKEN + HARD_GENERATION_FLOOR_MS
            return budget.coerceIn(HARD_GENERATION_FLOOR_MS, HARD_GENERATION_MAX_MS)
        }

        private fun firstCallbackTimeoutMs(promptLengthChars: Int): Long {
            val estimatedTokens = promptLengthChars.coerceAtLeast(0) / 4
            return (FIRST_CALLBACK_TIMEOUT_FLOOR_MS + estimatedTokens * MS_PER_PROMPT_TOKEN)
                .coerceIn(FIRST_CALLBACK_TIMEOUT_FLOOR_MS, FIRST_CALLBACK_TIMEOUT_MAX_MS)
        }
        private const val ENGINE_INIT_TIMEOUT_MS = 45_000L
        private const val ENGINE_INIT_CANCEL_GRACE_MS = 1_500L
        private const val NATIVE_INIT_WEDGED_PREFIX = "NATIVE_INIT_WEDGED"
        private const val WARMUP_TIMEOUT_MS = 90_000L
        private const val SELF_TEST_TIMEOUT_MS = 30_000L
        private const val SELF_TEST_MAX_TOKENS = 12
        private const val GENERATION_DRAIN_WAIT_NS = 120_000_000_000L
        private const val GENERATION_DRAIN_POLL_MS = 25L
        private const val STREAMING_MAX_OUTPUT_TOKENS = 8192
        private const val UNLIMITED_MAX_TOKENS_SENTINEL = 65536

        private val CONTROL_TOKEN_MARKERS = listOf(
            "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|end_of_text|>",
            "<|end_of_turn|>", "<think>", "</think>", "<bos>", "<eos>", "<pad>", "<unk>",
            "<start_of_turn>", "<end_of_turn>"
        )
    }
}
