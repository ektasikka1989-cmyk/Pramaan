package com.example.arfas

/**
 * The contract LocalModelManager needs from an on-device text engine. Two engines implement it:
 *  - [LlamaCppCore]: Meta Llama 3.2 (GGUF) through llama.cpp — the Meta Stack default
 *  - [LiteRtCore]: the existing LiteRT-LM engine for .litertlm bundles (unchanged behaviour)
 * LocalModelManager picks one per model file, so every feature above it (KB answers, chat, voice,
 * planners, troubleshooting text) runs on whichever model is installed.
 */
internal interface LocalInferenceCore {
    val engineName: String
    val selectedBackend: BackendType
    val resolvedContextTokens: Int
    val extendedContextTokens: Int
    val visionSupported: Boolean
    val isLoaded: Boolean
    val hasActiveSession: Boolean
    val isGenerationActive: Boolean

    suspend fun loadModel(modelPath: String, onProgress: (String) -> Unit): AndroLLMCoreLoadResult

    suspend fun generateEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double = AndroLLMLocalInferenceCore.DEFAULT_TEMPERATURE,
        onPartial: ((String) -> Unit)? = null
    ): AndroLLMCoreGeneration

    suspend fun generateVisionEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        imageBytes: ByteArray,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double = AndroLLMLocalInferenceCore.DEFAULT_TEMPERATURE
    ): AndroLLMCoreGeneration

    suspend fun generateChat(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String,
        maxOutputToken: Int,
        onDelta: ((String) -> Unit)? = null
    ): AndroLLMCoreGeneration

    fun ensureConversationForHistory(history: List<AndroLLMCoreMessage>, systemPrompt: String): Boolean
    fun markAcceptedTurn(userText: String, assistantText: String)
    fun getConversationTokenCount(): Int
    fun closeSession()
    fun unload()
    fun shutdownAsync(reason: String)
    fun retireSessionAsync(reason: String)
    fun cancelEphemeralGeneration()
    fun cancelActiveGeneration()
    fun requestCancelActiveGeneration(reason: String)
    suspend fun runSelfTest(): Boolean
    suspend fun recoverOnCpu(onProgress: (String) -> Unit = {}): Boolean
}

/** The existing LiteRT-LM engine behind the shared contract (pure delegation, no behaviour change). */
internal class LiteRtCore(private val impl: AndroLLMLocalInferenceCore) : LocalInferenceCore {
    override val engineName: String get() = "LiteRT-LM"
    override val selectedBackend: BackendType get() = impl.selectedBackend
    override val resolvedContextTokens: Int get() = impl.resolvedContextTokens
    override val extendedContextTokens: Int get() = impl.extendedContextTokens
    override val visionSupported: Boolean get() = impl.visionSupported
    override val isLoaded: Boolean get() = impl.isLoaded
    override val hasActiveSession: Boolean get() = impl.hasActiveSession
    override val isGenerationActive: Boolean get() = impl.isGenerationActive

    override suspend fun loadModel(modelPath: String, onProgress: (String) -> Unit) =
        impl.loadModel(modelPath, onProgress)

    override suspend fun generateEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double,
        onPartial: ((String) -> Unit)?
    ) = impl.generateEphemeral(history, currentUser, systemPrompt, maxOutputToken, temperature, onPartial)

    override suspend fun generateVisionEphemeral(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        imageBytes: ByteArray,
        systemPrompt: String?,
        maxOutputToken: Int,
        temperature: Double
    ) = impl.generateVisionEphemeral(history, currentUser, imageBytes, systemPrompt, maxOutputToken, temperature)

    override suspend fun generateChat(
        history: List<AndroLLMCoreMessage>,
        currentUser: String,
        systemPrompt: String,
        maxOutputToken: Int,
        onDelta: ((String) -> Unit)?
    ) = impl.generateChat(history, currentUser, systemPrompt, maxOutputToken, onDelta)

    override fun ensureConversationForHistory(history: List<AndroLLMCoreMessage>, systemPrompt: String) =
        impl.ensureConversationForHistory(history, systemPrompt)

    override fun markAcceptedTurn(userText: String, assistantText: String) = impl.markAcceptedTurn(userText, assistantText)
    override fun getConversationTokenCount(): Int = impl.getConversationTokenCount()
    override fun closeSession() = impl.closeSession()
    override fun unload() = impl.unload()
    override fun shutdownAsync(reason: String) = impl.shutdownAsync(reason)
    override fun retireSessionAsync(reason: String) = impl.retireSessionAsync(reason)
    override fun cancelEphemeralGeneration() = impl.cancelEphemeralGeneration()
    override fun cancelActiveGeneration() = impl.cancelActiveGeneration()
    override fun requestCancelActiveGeneration(reason: String) = impl.requestCancelActiveGeneration(reason)
    override suspend fun runSelfTest(): Boolean = impl.runSelfTest()
    override suspend fun recoverOnCpu(onProgress: (String) -> Unit): Boolean = impl.recoverOnCpu(onProgress)
}
