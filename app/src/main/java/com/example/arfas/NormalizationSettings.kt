package com.example.arfas

data class NormalizationSettings(
    val enabled: Boolean = true,
    val numbers: Boolean = true,
    val dates: Boolean = true,
    val currency: Boolean = true,
    val units: Boolean = true,
    val math: Boolean = true,
    val emoji: Boolean = true,
    val urlsEmails: Boolean = true,
    val phones: Boolean = true,
    val abbreviations: Boolean = true,
    val debug: Boolean = false
) {
    companion object {
        val ALL_ENABLED = NormalizationSettings()
        fun from(s: FixSightVoiceSettings): NormalizationSettings = NormalizationSettings(
            enabled = s.tnEnabled,
            numbers = s.tnNumbers,
            dates = s.tnDates,
            currency = s.tnCurrency,
            units = s.tnUnits,
            math = s.tnMath,
            emoji = s.tnEmoji,
            urlsEmails = s.tnUrlsEmails,
            phones = s.tnPhones,
            abbreviations = s.tnAbbreviations,
            debug = s.tnDebug
        )
    }
}
