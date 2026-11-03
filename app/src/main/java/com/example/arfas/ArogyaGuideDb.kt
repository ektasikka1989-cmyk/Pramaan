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

/** One row of the Official guides library: source metadata + on-phone counts. */
data class GuideDoc(
    val sourceName: String, val title: String, val authority: String, val category: String,
    val language: String, val sourceUrl: String, val registerUrl: String,
    val retrievedUtc: String, val publicationDate: String,
    val passages: Int, val pages: Int
)

/** Pure Official-guides-library logic (grouping, links, totals). No Android, host-tested. */
object ArogyaGuideLibrary {
    /** Display groups in order; anything unrecognised keeps its own authority as the group. */
    fun group(authority: String): String {
        val a = authority.lowercase()
        return when {
            "tuberculosis" in a -> "Central TB Division"
            "vector borne" in a -> "NCVBDC"
            "rashtriya bal" in a || a == "rbsk" -> "RBSK"
            "resource centre" in a || "nhsrc" in a -> "NHSRC/NHM"
            "national health mission" in a -> "NHM"
            "ministry of health" in a || a == "mohfw" -> "MoHFW"
            else -> authority.ifBlank { "Other" }
        }
    }

    fun groupOrder(): List<String> = listOf("MoHFW", "NHSRC/NHM", "NHM", "NCVBDC", "Central TB Division", "RBSK")

    /** source_url first, register_url as fallback, "" when no link was recorded. */
    fun link(doc: GuideDoc): String = doc.sourceUrl.ifBlank { doc.registerUrl }

    /** Host part for display (nhm.gov.in); "" when there is no link. */
    fun domain(doc: GuideDoc): String = runCatching {
        val u = link(doc).trim()
        if (u.isEmpty()) "" else u.substringAfter("://").substringBefore("/").removePrefix("www.")
    }.getOrDefault("")

    /** (documents, pages, passages) across the installed library. */
    fun totals(docs: List<GuideDoc>): Triple<Int, Int, Int> =
        Triple(docs.size, docs.sumOf { it.pages }, docs.sumOf { it.passages })

    /**
     * Deep link into the official copy: PDFs open at the cited page ("page 18" → #page=18).
     * Non-PDF links and locators without a page number are returned unchanged.
     */
    fun pageUrl(url: String, locator: String): String {
        if (!url.startsWith("http")) return url
        val page = Regex("""pages?\s+(\d+)""", RegexOption.IGNORE_CASE).find(locator)?.groupValues?.get(1)
            ?: Regex("""\bp\.?\s*(\d+)""", RegexOption.IGNORE_CASE).find(locator)?.groupValues?.get(1)
            ?: return url
        if (!url.contains(".pdf", ignoreCase = true) || url.contains('#')) return url
        return url + "#page=$page"
    }

    /** "68 PDFs → 5,389 passages" style header numbers. */
    fun grouped(docs: List<GuideDoc>): List<Pair<String, List<GuideDoc>>> {
        val order = groupOrder()
        return docs.groupBy { group(it.authority) }.toList()
            .sortedWith(compareBy({ order.indexOf(it.first).takeIf { i -> i >= 0 } ?: 99 }, { it.first }))
            .map { (g, ds) -> g to ds.sortedBy { it.title } }
    }
}
