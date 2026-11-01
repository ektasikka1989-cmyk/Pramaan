package com.example.arfas

/**
 * Validates that a selected database was embedded by the same model and pooling recipe
 * as the on-device query embedder. This is deliberately content- and domain-independent:
 * it compares stored vectors with fresh embeddings of a few deterministic pack rows.
 */
object KbVectorSpaceCheck {
    /**
     * Tolerance measured, not chosen: the phone's int8 E5 model re-embedding rows of the
     * model-matched pack on another CPU gives cosine 0.9937–0.9990 (mean 0.9975, 150 rows),
     * because int8 activation quantisation differs by CPU. A pack built with a different model or
     * recipe gives 0.81–0.97 (mean 0.966). 0.985 separates the two with margin; 0.999 would reject
     * the correct pack on every phone. Re-measure on the phone (KB_VECTOR_SPACE log) and tighten.
     */
    const val MINIMUM_COSINE = 0.985f

    data class Result(
        val passed: Boolean,
        val checked: Int,
        val minimumCosine: Float,
        val reason: String
    )

    fun evaluate(cosines: List<Float>): Result {
        if (cosines.isEmpty()) return Result(false, 0, 0f, "no_usable_embedding_samples")
        val minimum = cosines.minOrNull() ?: 0f
        return Result(
            passed = minimum >= MINIMUM_COSINE,
            checked = cosines.size,
            minimumCosine = minimum,
            reason = if (minimum >= MINIMUM_COSINE) "ok" else "embedding_space_mismatch"
        )
    }
}
