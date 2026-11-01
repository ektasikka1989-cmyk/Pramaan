package com.example.arfas

/**
 * Gemma-only compatibility slice copied from the current AndroLLM family contract.
 * FixSight keeps a fixed Gemma .litertlm model, so the multi-family catalog is not needed.
 */
object AndroGemmaCompat {
    const val TEMPERATURE: Double = 0.8
    const val TOP_P: Double = 0.95
    const val TOP_K: Int = 40
    const val STREAMING_MAX_OUTPUT_TOKENS: Int = 8192

    val stopSequences: List<String> = listOf("<eos>", "<end_of_turn>")

    val specialTokens: List<String> = listOf(
        "<bos>", "<eos>", "<pad>", "<unk>", "<start_of_turn>", "<end_of_turn>"
    )

    /** AndroLLM's current Gemma lenient template. */
    val chatTemplate: String =
        "{{ bos_token }}" +
        "{% for message in messages %}" +
        "{% if message['role'] == 'system' %}{% set skip = true %}{% else %}{% set skip = false %}{% endif %}" +
        "{% if not skip %}" +
        "{% if message['role'] == 'user' %}{% set role = 'user' %}" +
        "{% elif message['role'] == 'model' %}{% set role = 'model' %}{% endif %}" +
        "{{ '<start_of_turn>' + role + '\\n' + message['content'] | trim + '<end_of_turn>\\n' }}" +
        "{% endif %}" +
        "{% endfor %}" +
        "{% if add_generation_prompt %}{{ '<start_of_turn>model\\n' }}{% endif %}"

    private val CONTROL_TOKEN_MARKERS = listOf(
        "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|end_of_text|>",
        "<|end_of_turn|>", "<|assistant|>", "<|user|>", "<|system|>",
        "<think>", "</think>", "<bos>", "<eos>", "<pad>", "<unk>",
        "<start_of_turn>", "<end_of_turn>", "<|start_header_id|>", "<|end_header_id|>",
        "<|tool_call_start|>", "<|tool_call_end|>", "<|tool_call|>", "<tool_call|>", "<|tool_call>"
    )

    private val controlPattern = Regex(CONTROL_TOKEN_MARKERS.joinToString("|") { Regex.escape(it) })

    fun stripControlTokens(text: String): String {
        if (text.isEmpty()) return text
        return controlPattern.replace(text, "")
    }

    fun cleanOutput(raw: String): String {
        var text = raw
        val firstStop = stopSequences.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (firstStop != null) text = text.substring(0, firstStop)
        return stripControlTokens(text).trim()
    }
}
