package com.example.arfas

import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI surface of libpramaan_llama.so (app/src/main/cpp/pramaan_llama.cpp, llama.cpp b11799).
 * Keep every signature in sync with the C++ file.
 */
internal object LlamaNative {
    const val RC_EOS = 0
    const val RC_MAX_TOKENS = 1
    const val RC_CANCELLED = 2
    const val RC_CONTEXT_FULL = 3
    const val RC_STOPPED_BY_CLIENT = 4
    const val RC_TEMPLATE_FAILED = -1
    const val RC_TOKENIZE_FAILED = -2
    const val RC_PROMPT_TOO_LONG = -3
    const val RC_DECODE_FAILED = -4
    const val RC_BAD_HANDLE = -5

    /** Index of each value in the stats array filled by [nativeGenerate]. */
    const val STAT_PROMPT_TOKENS = 0
    const val STAT_REUSED_TOKENS = 1
    const val STAT_GENERATED_TOKENS = 2
    const val STAT_TTFT_MS = 3
    const val STAT_TOTAL_MS = 4
    const val STAT_PREFILL_MS = 5

    private val loaded = AtomicBoolean(false)
    @Volatile var loadError: String? = null
        private set

    /** Receives UTF-8 bytes that always end on a character boundary. Return false to stop. */
    fun interface TokenSink {
        fun onPiece(bytes: ByteArray): Boolean
    }

    /**
     * Loads the native library once and registers the ggml CPU backends from [nativeLibDir]
     * (Context.applicationInfo.nativeLibraryDir; requires extracted native libs, see BUILD.md).
     */
    @Synchronized
    fun ensureLoaded(nativeLibDir: String): Boolean {
        if (loaded.get()) return true
        return try {
            val override = System.getProperty("pramaan.llama.lib")  // desktop tests only
            if (override.isNullOrBlank()) System.loadLibrary("pramaan_llama") else System.load(override)
            nativeInit(nativeLibDir)
            loaded.set(true)
            loadError = null
            true
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    fun returnCodeName(rc: Int): String = when (rc) {
        RC_EOS -> "eos"
        RC_MAX_TOKENS -> "max_tokens"
        RC_CANCELLED -> "cancelled"
        RC_CONTEXT_FULL -> "context_full"
        RC_STOPPED_BY_CLIENT -> "stopped_by_client"
        RC_TEMPLATE_FAILED -> "template_failed"
        RC_TOKENIZE_FAILED -> "tokenize_failed"
        RC_PROMPT_TOO_LONG -> "prompt_too_long"
        RC_DECODE_FAILED -> "decode_failed"
        RC_BAD_HANDLE -> "bad_handle"
        else -> "rc_$rc"
    }

    external fun nativeInit(nativeLibDir: String)
    external fun nativeSystemInfo(): String
    external fun nativeLastError(): String
    /**
     * [nUbatch] is the physical batch (512 is the ARM sweet spot); [flashAttn] is
     * -1 = llama.cpp AUTO, 0 = off, 1 = on.
     */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, nBatch: Int, nUbatch: Int, flashAttn: Int): Long
    external fun nativeContextSize(handle: Long): Int
    external fun nativeCancel(handle: Long)
    external fun nativeFree(handle: Long)
    external fun nativeCountTokens(handle: Long, text: String): Int
    external fun nativeCountPromptTokens(handle: Long, roles: Array<String>, contents: Array<String>): Int
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Int,
        sink: TokenSink?,
        stats: LongArray
    ): Int
}
