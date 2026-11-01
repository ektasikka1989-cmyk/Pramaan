package com.example.arfas

/**
 * R9: the ASHA guide database comes in two files. v1 is the original build;
 * v2 lowers non-ASHA documents' priority and adds official source URLs
 * (built by tools/kb_r9.py; passages are byte-identical). The flag picks which
 * file the install prompt names and which one the benchmark expects; both files
 * stay on the phone so Batch 7 can switch by re-installing through the picker.
 */
object ArogyaGuideDb {
    const val V1_NAME = "ASHA_Health_Guide.db"
    const val V2_NAME = "ASHA_Health_Guide_v2.db"

    /** The Download file the app currently wants (KB_PUSH pushes both). */
    fun wantedName(): String = if (ArogyaFlags.r9NewDatabase) V2_NAME else V1_NAME

    /** Wanted PRAGMA user_version: 2 for v2, 0 for v1. */
    fun wantedVersion(): Int = if (ArogyaFlags.r9NewDatabase) 2 else 0

    /** True when the installed pack is not the wanted one (-1 = none installed). */
    fun needsSwitch(activeVersion: Int): Boolean =
        activeVersion >= 0 && (activeVersion == 2) != (wantedVersion() == 2)

    fun describe(activeVersion: Int): String = when (activeVersion) {
        2 -> "Guide v2"
        0 -> "Guide v1"
        else -> "Guide ?"
    }
}

/** One-tap guide update offered on the Knowledge Base screen when the wanted file is in Downloads. */
data class KbGuideUpdate(val activeLabel: String, val wantedName: String)
