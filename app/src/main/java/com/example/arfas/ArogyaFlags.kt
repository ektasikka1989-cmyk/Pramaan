package com.example.arfas

/**
 * Switches for every answer-quality and speed change (Batch 2+). Each defaults ON;
 * the final benchmark can turn any one off to isolate a regression. Runtime vars
 * (not Settings) so tests and the demo can flip them without touching user prefs.
 */
object ArogyaFlags {
    // Batch 2 answer quality.
    @Volatile var r1WideEvidence = true      // 6 passages x ~1100 chars (else 4 x 900)
    @Volatile var r2LengthPrompt = true      // type-aware COMPLETE_ANSWER (else legacy line)
    @Volatile var r9NewDatabase = true       // v2 guide DB: precedence + URLs (else old file)
    @Volatile var r8GuidelineOnly = true     // Ask SOP: not-in-guides -> ANM message (R8)
    @Volatile var r7SourceLine = true        // answers end with "Source: title, page N" (R7)
    @Volatile var doseAgeMatch = true        // dose answers must match the asked age group
    @Volatile var hindiRewrite = true        // Hindi->English search rewrite, answer stays Hindi
    @Volatile var r3Salvage = true           // drop only the unsupported sentence (R3-lite)
    @Volatile var coverageFuzzy = true       // typo-tolerant coverage warning (§4 fix)
    // Batch 3 cloud.
    @Volatile var r10WideCloud = true        // Cloud first uses the 12 x 1600 set (else narrow)
    @Volatile var r11LowReasoning = true     // Spark KB answers: minimal reasoning effort
    // Speed work.
    @Volatile var speedStreamCloud = true    // stream Spark drafts into the card while checking
    @Volatile var speedFastRerank = true     // fewer rerank candidates when the top is clearly ahead
    @Volatile var speedSkipSecondPass = true // skip the lexical retry when the first pass is confident
    @Volatile var speedWarmSearch = true     // warm E5 + reranker sessions at startup (else first-question cost)
}
