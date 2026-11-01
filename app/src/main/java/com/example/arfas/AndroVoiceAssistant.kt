package com.example.arfas

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap

/**
 * UI-to-service command bridge. No recognizer, TTS engine, generation Job or
 * native LiteRT object is owned here. This mirrors AndroLLM's service boundary.
 */
object AndroVoiceAssistant {
    const val ACTION_START_SESSION = "com.example.arfas.voice.START_SESSION"
    const val ACTION_STOP_SESSION = "com.example.arfas.voice.STOP_SESSION"
    const val ACTION_SUBMIT_TEXT = "com.example.arfas.voice.SUBMIT_TEXT"
    const val ACTION_INTERRUPT = "com.example.arfas.voice.INTERRUPT"
    const val ACTION_SET_VISION_MODE = "com.example.arfas.voice.SET_VISION_MODE"
    const val ACTION_SET_KNOWLEDGE_MODE = "com.example.arfas.voice.SET_KNOWLEDGE_MODE"
    const val ACTION_START_VOICE_INPUT = "com.example.arfas.voice.START_VOICE_INPUT"
    /** Keep the app alive (foreground service, no microphone) while the user picks a photo or file. */
    const val ACTION_HOLD_FOREGROUND = "com.example.arfas.voice.HOLD_FOREGROUND"
    const val ACTION_STOP_VOICE_INPUT = "com.example.arfas.voice.STOP_VOICE_INPUT"
    const val ACTION_SPEAK_TEXT = "com.example.arfas.voice.SPEAK_TEXT"
    const val ACTION_STOP_SPEECH = "com.example.arfas.voice.STOP_SPEECH"
    const val ACTION_START_TROUBLESHOOTING = "com.example.arfas.voice.START_TROUBLESHOOTING"
    const val ACTION_SET_TROUBLESHOOTING_MODE = "com.example.arfas.voice.SET_TROUBLESHOOTING_MODE"
    const val ACTION_OPEN_CONVERSATION = "com.example.arfas.voice.OPEN_CONVERSATION"

    const val EXTRA_VISION_MODE = "vision_mode"
    const val EXTRA_TEXT = "text"
    const val EXTRA_FORCE_EXTERNAL_INFORMATION = "force_external_information"
    const val EXTRA_REARM_LISTENING = "rearm_listening"
    const val EXTRA_KNOWLEDGE_MODE = "knowledge_mode"
    const val EXTRA_CONVERSATION_ID = "conversation_id"
    const val EXTRA_START_NEW_CHAT = "start_new_chat"
    const val EXTRA_TROUBLESHOOTING_MODE = "troubleshooting_mode"

    @Volatile private var visualFrameProvider: (suspend () -> Bitmap?)? = null
    @Volatile private var visualContextProvider: (() -> String)? = null

    fun setVisualFrameProvider(provider: (suspend () -> Bitmap?)?) {
        visualFrameProvider = provider
    }

    fun setVisualContextProvider(provider: (() -> String)?) {
        visualContextProvider = provider
    }

    internal suspend fun captureVisualFrame(): Bitmap? = visualFrameProvider?.invoke()
    internal fun currentVisualContext(): String = runCatching { visualContextProvider?.invoke().orEmpty() }.getOrDefault("")

    fun startSession(
        context: Context,
        visionMode: Boolean,
        knowledgeMode: Boolean = false,
        startNewChat: Boolean = false
    ) {
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_START_SESSION)
                .putExtra(EXTRA_VISION_MODE, visionMode)
                .putExtra(EXTRA_KNOWLEDGE_MODE, knowledgeMode)
                .putExtra(EXTRA_START_NEW_CHAT, startNewChat)
        )
    }

    fun stopSession(context: Context) {
        send(context, Intent(context, AndroVoiceForegroundService::class.java).setAction(ACTION_STOP_SESSION))
    }

    fun startTroubleshooting(context: Context, visionMode: Boolean = false, knowledgeMode: Boolean = false) {
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_START_TROUBLESHOOTING)
                .putExtra(EXTRA_VISION_MODE, visionMode)
                .putExtra(EXTRA_KNOWLEDGE_MODE, knowledgeMode)
                .putExtra(EXTRA_START_NEW_CHAT, true)
        )
    }


    fun setTroubleshootingMode(context: Context, enabled: Boolean) {
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_SET_TROUBLESHOOTING_MODE)
                .putExtra(EXTRA_TROUBLESHOOTING_MODE, enabled)
        )
    }

    fun openConversation(context: Context, conversationId: String) {
        val clean = conversationId.trim()
        if (clean.isBlank()) return
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_OPEN_CONVERSATION)
                .putExtra(EXTRA_CONVERSATION_ID, clean)
        )
    }

    fun submitTypedTurn(
        context: Context,
        text: String,
        forceExternalInformation: Boolean = false
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_SUBMIT_TEXT)
                .putExtra(EXTRA_TEXT, clean)
                .putExtra(EXTRA_FORCE_EXTERNAL_INFORMATION, forceExternalInformation)
        )
    }

    fun interrupt(context: Context, rearmListening: Boolean = false) {
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_INTERRUPT)
                .putExtra(EXTRA_REARM_LISTENING, rearmListening)
        )
    }

    fun setVisionMode(context: Context, enabled: Boolean) {
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_SET_VISION_MODE)
                .putExtra(EXTRA_VISION_MODE, enabled)
        )
    }

    fun startVoiceInput(context: Context) {
        send(context, Intent(context, AndroVoiceForegroundService::class.java).setAction(ACTION_START_VOICE_INPUT))
    }

    fun stopVoiceInput(context: Context) {
        send(context, Intent(context, AndroVoiceForegroundService::class.java).setAction(ACTION_STOP_VOICE_INPUT))
    }

    fun stopSpeech(context: Context) {
        send(context, Intent(context, AndroVoiceForegroundService::class.java).setAction(ACTION_STOP_SPEECH))
    }

    fun speakText(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_SPEAK_TEXT)
                .putExtra(EXTRA_TEXT, clean)
        )
    }

    fun setKnowledgeMode(context: Context, enabled: Boolean) {
        send(
            context,
            Intent(context, AndroVoiceForegroundService::class.java)
                .setAction(ACTION_SET_KNOWLEDGE_MODE)
                .putExtra(EXTRA_KNOWLEDGE_MODE, enabled)
        )
    }

    /** Legacy compatibility aliases; they now delegate to the service-owned session. */
    fun start(context: Context) = startSession(context, AndroVoiceController.state.value.visionMode)
    fun stop(context: Context) = stopSession(context)

    /**
     * Before the photo picker or camera app opens: Pramaan goes to the background with several GB of
     * models loaded, and Android killed it to free memory (the app restarted and the chat was lost).
     */
    fun holdForeground(context: Context) {
        send(context, Intent(context, AndroVoiceForegroundService::class.java).setAction(ACTION_HOLD_FOREGROUND))
    }

    private fun send(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Phase 23 text-first: only explicit microphone capture requires foreground
        // voice-service admission. Text/camera/KB/TTS commands originate from the
        // visible Activity and must not demand microphone FGS permission.
        if (intent.action == ACTION_START_VOICE_INPUT) {
            runCatching { app.startForegroundService(intent) }
                .recoverCatching { app.startService(intent) }
        } else {
            runCatching { app.startService(intent) }
        }
    }
}
