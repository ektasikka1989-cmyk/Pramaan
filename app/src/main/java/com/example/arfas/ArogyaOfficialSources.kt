package com.example.arfas

import java.net.URI
import java.net.HttpURLConnection
import java.net.URL

/**
 * Item 5b Tier 2: answers from official Government of India websites. Pure logic
 * (allowlist, reference parsing, page-text extraction, dates) is host-tested;
 * [fetch] does the HTTP read on the device (official domains only, bounded).
 */
object ArogyaOfficialSources {
    /** The only web domains a Tier 2 answer may cite (exact host or subdomain). */
    val ALLOW = listOf(
        "mohfw.gov.in", "main.mohfw.gov.in", "nhm.gov.in", "nhsrcindia.org",
        "ncvbdc.mohfw.gov.in", "tbcindia.gov.in", "tbcindia.mohfw.gov.in", "pib.gov.in"
    )

    /** One cited web reference: title + URL as the provider listed it. */
    data class WebRef(val title: String, val url: String)

    /** A fetched official page: URL, title, visible text and publication date ("" when unknown). */
    data class Fetched(val url: String, val title: String, val text: String, val date: String)

    fun hostOf(url: String): String = runCatching { URI(url.trim()).host?.lowercase(java.util.Locale.ROOT) }.getOrNull().orEmpty()

    /**
     * True only for the allowlist: an exact entry, a subdomain of one (*.nhm.gov.in
     * covers the state missions), or a state nrhm*.gov.in host. Look-alikes such as
     * nhm.gov.in.evil.com never match (their host ends elsewhere).
     */
    fun isAllowed(url: String): Boolean {
        val h = hostOf(url)
        if (h.isEmpty()) return false
        if (ALLOW.any { h == it || h.endsWith(".$it") }) return true
        if (h.endsWith(".gov.in") && h.removeSuffix(".gov.in").split(".").any { it.startsWith("nrhm") }) return true
        return false
    }

    /** Domain shown on the card ("Open link" opens the original on the government site). */
    fun domainOf(url: String): String = hostOf(url).removePrefix("www.")

    /**
     * Splits a live-web answer into (answer text, cited references). Reads the provider's
     * own "Sources:" list ("- title url" lines) first; markdown links and bare official
     * URLs in the body are the fallback. Non-allowlisted references are discarded here.
     */
    fun parseRefs(webText: String): Pair<String, List<WebRef>> {
        val cut = Regex("(?m)^\\s*Sources:\\s*$").find(webText)
        val answer = (if (cut != null) webText.substring(0, cut.range.first) else webText).trim()
        val tail = if (cut != null) webText.substring(cut.range.last + 1) else ""
        val refs = ArrayList<WebRef>()
        for (line in tail.lines()) {
            val m = Regex("^\\s*[-•\\d+.)\\s]+(.+?)\\s+(https?://\\S+)\\s*$").find(line.trim()) ?: continue
            val url = m.groupValues[2].trimEnd('.', ',', ')', ']', '"')
            if (isAllowed(url)) refs += WebRef(m.groupValues[1].trim().ifBlank { domainOf(url) }, url)
        }
        if (refs.isEmpty()) {
            for (m in Regex("\\[([^\\[\\]]{1,120})]\\((https?://[^)\\s]+)\\)").findAll(webText)) {
                val url = m.groupValues[2]
                if (isAllowed(url) && refs.none { it.url == url }) refs += WebRef(m.groupValues[1].trim(), url)
            }
        }
        if (refs.isEmpty()) {
            for (m in Regex("https?://[^\\s)\"']+").findAll(webText)) {
                val url = m.value.trimEnd('.', ',', ')', ']', '"')
                if (isAllowed(url) && refs.none { it.url == url }) refs += WebRef(domainOf(url), url)
            }
        }
        return answer to refs.distinctBy { it.url }
    }

    /** Visible text of an HTML page: scripts/styles dropped, tags and entities cleaned. */
    fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        var s = html.replace(Regex("(?is)<(script|style|noscript|nav|footer)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<(br|p|div|li|tr|h[1-6])[^>]*>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "'").replace("&lsquo;", "'")
            .replace("&ldquo;", "\"").replace("&rdquo;", "\"").replace("&ndash;", "–").replace("&mdash;", "—")
        s = s.replace(Regex("&#(\\d+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault(" ") }
        return s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private val MONTHS = mapOf(
        "january" to "01", "february" to "02", "march" to "03", "april" to "04",
        "may" to "05", "june" to "06", "july" to "07", "august" to "08",
        "september" to "09", "october" to "10", "november" to "11", "december" to "12"
    )
    private val DATE_PATTERNS = listOf(
        Regex("(?i)(?:published|updated|posted|date|as on|dated)[^\\n]{0,30}?(\\d{1,2})[\\s-]+([A-Za-z]+)[\\s,-]+(20\\d{2})"),
        Regex("\\b(\\d{1,2})[\\s-]+([A-Za-z]+)[\\s,-]+(20\\d{2})\\b"),
        Regex("(?i)\\b([A-Za-z]+)\\s+(\\d{1,2}),\\s+(20\\d{2})\\b"),
        Regex("\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b"),
        Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](20\\d{2})\\b")
    )

    /**
     * Publication/updated date of a page as written ("12 March 2025"); "" when none is
     * found. Prefers a labelled date (Published/Updated) over a bare one.
     */
    fun findDate(text: String): String {
        for (p in DATE_PATTERNS) {
            val m = p.find(text) ?: continue
            return m.value.trim().replace(Regex("(?i)^(published|updated|posted|date|as on|dated)\\s*[:\\-]?\\s*"), "").trim()
        }
        return ""
    }

    /**
     * A found date normalised to yyyy-MM-dd for newer/older comparison; "" when the
     * raw text does not parse (then no conflict line is ever shown).
     */
    fun dateKey(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        fun monthNum(name: String): String? = MONTHS[name.lowercase(java.util.Locale.ROOT)]
        var m = Regex("(?i)^(\\d{1,2})[\\s-]+([A-Za-z]+)[\\s,-]+(20\\d{2})$").find(t)
        if (m != null) return "${m.groupValues[3]}-${monthNum(m.groupValues[2]) ?: return ""}-${m.groupValues[1].padStart(2, '0')}"
        m = Regex("(?i)^([A-Za-z]+)\\s+(\\d{1,2}),\\s+(20\\d{2})$").find(t)
        if (m != null) return "${m.groupValues[3]}-${monthNum(m.groupValues[1]) ?: return ""}-${m.groupValues[2].padStart(2, '0')}"
        m = Regex("^(20\\d{2})-(\\d{2})-(\\d{2})$").find(t)
        if (m != null) return t
        m = Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](20\\d{2})$").find(t)
        if (m != null) return "${m.groupValues[3]}-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[1].padStart(2, '0')}"
        return ""
    }

    /** True when the website guidance is newer than the guide document (both dates known). */
    fun isNewer(webDate: String, guideDate: String): Boolean {
        val w = dateKey(webDate)
        val g = dateKey(guideDate)
        return w.isNotEmpty() && g.isNotEmpty() && w > g
    }

    /**
     * Reads one official page (allowlisted HTTPS hosts only, 8 s, 400 KB cap). PDFs are
     * not fetched (no parser on the phone): they stay openable links but ground nothing.
     */
    fun fetch(url: String, timeoutMs: Int = 8_000): Fetched? {
        if (!isAllowed(url)) return null
        if (url.lowercase().contains(".pdf")) return Fetched(url, domainOf(url), "", "")
        return runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ArogyaDoot/1.0 (ASHA guide citations)")
            }
            c.connect()
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { it.readNBytes(400_000) }
            val html = bytes.toString(Charsets.UTF_8)
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
                ?.let { stripHtml(it) }?.take(160).orEmpty()
            val text = stripHtml(html).take(60_000)
            if (text.length < 200) return null
            Fetched(url, title.ifBlank { domainOf(url) }, text, findDate(text.take(8_000)))
        }.getOrNull()
    }
}
