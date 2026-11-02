package com.example.arfas

/**
 * Host-test stub for the AI request types (the real ones live in the Android-only
 * ArogyaVisitAi.kt). Only the properties ArogyaOnlinePolicy.prepare() reads are
 * modelled; never compiled into the app.
 */
enum class ArogyaAiTask { EXPLAIN, ORGANISE_NOTES, HANDOVER }
data class ArogyaAiRequest(
    val task: ArogyaAiTask,
    val visit: ArogyaVisit,
    val question: String = "",
    val hindi: Boolean = false,
    val topic: String = "",
    val previousExchange: String = "",
    val onlineCheck: Boolean = false
)
