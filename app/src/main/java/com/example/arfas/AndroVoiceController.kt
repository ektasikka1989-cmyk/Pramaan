package com.example.arfas

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single process-wide voice UI/state authority, directly following AndroLLM's
 * VoiceAssistantController pattern. The foreground voice service is the only
 * writer during an active session; MainActivity only observes this state.
 */
data class AndroVoiceUiState(
    val sessionActive: Boolean = false,
    val phase: AppState = AppState.IDLE,
    val visionMode: Boolean = false,
    val knowledgeMode: Boolean = false,
    val troubleshootingMode: Boolean = false,
    val displayText: String = "",
    val transcript: String = "",
    val answerText: String = "",
    val error: String = "",
    val turnActive: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val voiceReady: Boolean = false,
    val voiceInitComplete: Boolean = false,
    val sttReady: Boolean = false,
    val ttsReady: Boolean = false,
    val runtimeHealth: String = "",
    val pendingAttachmentLabel: String = ""
)

data class AndroVoiceTurnEvent(
    val query: String,
    val result: AndroBaselineRuntime.TurnResult,
    val attachmentName: String? = null
)

data class AndroInterruptedTurnEvent(
    val query: String,
    val partialAnswer: String,
    val reason: String
)

data class AndroCurrentTurnSnapshot(
    val query: String,
    val partialAnswer: String,
    val generationActive: Boolean,
    val speakingAcceptedAnswer: Boolean
)

object AndroVoiceController {
    private val _state = MutableStateFlow(AndroVoiceUiState())
    val state: StateFlow<AndroVoiceUiState> = _state.asStateFlow()

    private val _turnEvents = MutableSharedFlow<AndroVoiceTurnEvent>(extraBufferCapacity = 32)
    val turnEvents: SharedFlow<AndroVoiceTurnEvent> = _turnEvents.asSharedFlow()
    private val _interruptedTurnEvents = MutableSharedFlow<AndroInterruptedTurnEvent>(extraBufferCapacity = 16)
    val interruptedTurnEvents: SharedFlow<AndroInterruptedTurnEvent> = _interruptedTurnEvents.asSharedFlow()
    private val stateLock = Any()

    private inline fun mutate(block: (AndroVoiceUiState) -> AndroVoiceUiState) {
        synchronized(stateLock) { _state.value = block(_state.value) }
    }

    fun beginSession(visionMode: Boolean, troubleshootingMode: Boolean = false) {
        _state.value = AndroVoiceUiState(
            sessionActive = true,
            phase = AppState.WAITING,
            visionMode = visionMode,
            troubleshootingMode = troubleshootingMode,
            displayText = if (troubleshootingMode) "Troubleshooting mode starting…" else "ArogyaDoot ready.",
            voiceInitComplete = true
        )
    }

    fun setTroubleshootingMode(enabled: Boolean) {
        mutate { it.copy(troubleshootingMode = enabled) }
    }

    fun endSession() {
        _state.value = AndroVoiceUiState()
    }

    fun setVisionMode(enabled: Boolean) {
        mutate { it.copy(visionMode = enabled) }
    }

    fun setKnowledgeMode(enabled: Boolean) {
        mutate { it.copy(knowledgeMode = enabled) }
    }

    fun setVoiceReady(ready: Boolean, sttReady: Boolean, ttsReady: Boolean, initComplete: Boolean = true) {
        mutate {
            it.copy(
                voiceReady = ready,
                voiceInitComplete = initComplete,
                sttReady = sttReady,
                ttsReady = ttsReady
            )
        }
    }

    fun setListening(active: Boolean) {
        mutate {
            val phase = when {
                active -> AppState.LISTENING
                it.speaking -> AppState.SPEAKING
                it.turnActive -> AppState.THINKING
                it.sessionActive -> AppState.WAITING
                else -> AppState.IDLE
            }
            it.copy(listening = active, phase = phase)
        }
    }

    fun setSpeaking(active: Boolean) {
        mutate {
            val phase = when {
                active -> AppState.SPEAKING
                it.listening -> AppState.LISTENING
                it.turnActive -> AppState.THINKING
                it.sessionActive -> AppState.WAITING
                else -> AppState.IDLE
            }
            it.copy(speaking = active, phase = phase)
        }
    }

    fun beginTurn(query: String) {
        mutate {
            it.copy(
                turnActive = true,
                listening = false,
                phase = AppState.THINKING,
                transcript = query,
                displayText = query,
                answerText = "",
                error = ""
            )
        }
    }

    fun setTranscriptPreview(text: String) {
        if (text.isBlank()) return
        mutate {
            if (it.turnActive || it.speaking) it else it.copy(displayText = text, transcript = text)
        }
    }

    fun updateStreamingAnswer(text: String) {
        if (text.isBlank()) return
        mutate { it.copy(displayText = text, answerText = text) }
    }

    fun finishTurn(result: AndroBaselineRuntime.TurnResult) {
        mutate {
            val phase = when {
                it.speaking -> AppState.SPEAKING
                it.listening -> AppState.LISTENING
                it.sessionActive -> AppState.WAITING
                else -> AppState.IDLE
            }
            it.copy(
                turnActive = false,
                phase = phase,
                displayText = result.text,
                answerText = result.text,
                error = ""
            )
        }
    }

    fun currentTurnSnapshot(): AndroCurrentTurnSnapshot = synchronized(stateLock) {
        val s = _state.value
        AndroCurrentTurnSnapshot(
            query = s.transcript.trim(),
            partialAnswer = s.answerText.trim(),
            generationActive = s.turnActive,
            speakingAcceptedAnswer = s.speaking && !s.turnActive
        )
    }

    fun cancelTurn(rearmListening: Boolean) {
        mutate {
            it.copy(
                turnActive = false,
                listening = rearmListening,
                speaking = false,
                phase = when {
                    rearmListening -> AppState.LISTENING
                    it.sessionActive -> AppState.WAITING
                    else -> AppState.IDLE
                }
            )
        }
    }

    fun setRuntimeHealth(label: String) {
        mutate { it.copy(runtimeHealth = label) }
    }

    fun setPendingAttachment(label: String) {
        mutate { it.copy(pendingAttachmentLabel = label) }
    }

    fun setError(message: String, keepSession: Boolean = true) {
        mutate {
            it.copy(
                error = message,
                displayText = message,
                turnActive = false,
                listening = false,
                speaking = false,
                phase = if (keepSession && it.sessionActive) AppState.WAITING else AppState.IDLE
            )
        }
    }

    fun emitAcceptedTurn(event: AndroVoiceTurnEvent) {
        _turnEvents.tryEmit(event)
    }

    fun emitInterruptedTurn(event: AndroInterruptedTurnEvent) {
        _interruptedTurnEvents.tryEmit(event)
    }
}
