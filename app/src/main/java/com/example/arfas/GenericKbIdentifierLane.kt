package com.example.arfas

import java.text.Normalizer
import java.util.Locale

/**
 * Phase 1.4 hierarchical identifier lane.
 *
 * Corpus-agnostic: nothing here names a document, a domain, or a topic word. Structure is
 * learned from the installed pack itself:
 *  - a "structural label" is any word that the pack uses to enumerate sections
 *    (it precedes many different, mostly consecutive numbers at heading-like positions);
 *  - a number in the question is a retrieval KEY only when the question writes it after such a
 *    label and the pack contains that labelled number; otherwise it is a user VALUE (an amount,
 *    a count, a date part) and never an exact-match key.
 *
 * Admission rules for a chunk (all enforced by [admit]):
 *  1. the parent identifier is in a heading/section field, or opens a section in the body
 *     (line start or after sentence punctuation, optionally preceded by one label word);
 *  2. a requested child marker occurs after that parent and before the next sibling section;
 *  3. the chunk also contains another literal anchor from the question (a meaningful word,
 *     the question's own label word next to the parent, or an acronym of words in the chunk);
 *  4. locator fields (page / slide / sheet / row positions) never count as parent evidence.
 */
object GenericKbIdentifierLane {

    enum class Kind(val rank: Int) { EXACT(4), HIERARCHICAL(3), SECTION(2), PARENT_ONLY(1) }

    data class QueryIdentifier(
        val surface: String,
        /** Parent first, then child ordinals (1-based). */
        val parent: String,
        val childOrdinal: Int?,
        val label: String?,
        val isKey: Boolean
    )

    data class Admission(
        val kind: Kind,
        val identifier: QueryIdentifier,
        val evidence: String,
        /**
         * True when the passage's document enumerates this label around the requested number (it
         * also opens the neighbouring sections, e.g. Rule 143 or 145): the document is the one
         * that is numbered this way, not one that quotes it. Learned from the pack.
         */
        val enumerated: Boolean = true
    ) {
        /**
         * True when this passage is where the requested section is written: the identifier opens a
         * heading segment or a body line/sentence (or the requested child is there) in a document
         * that is numbered by this label. False for a heading that only refers to it ("Restrictions
         * under Rule 144 (xi) of …") or a manual quoting it ("Rule 149 of GFR provides …").
         */
        val defines: Boolean get() = enumerated && evidence != HEADING_MENTION && kind != Kind.PARENT_ONLY
    }

    /** Evidence tag of a SECTION admission whose heading only mentions the identifier mid-text. */
    const val HEADING_MENTION = "heading-mention"

    data class ChunkView(
        val heading: String,
        val locator: String,
        val content: String,
        val sourceText: String,
        /** Document the passage belongs to (same key as the corpus profile's); "" when unknown. */
        val documentKey: String = ""
    )

    private val ROMAN = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x",
        "xi", "xii", "xiii", "xiv", "xv", "xvi", "xvii", "xviii", "xix", "xx")

    private fun ordinalOf(token: String): Int? {
        val t = token.trim().lowercase(Locale.ROOT)
        t.toIntOrNull()?.let { return it.takeIf { n -> n in 1..99 } }
        val r = ROMAN.indexOf(t)
        if (r >= 0) return r + 1
        if (t.length == 1 && t[0] in 'a'..'z') return t[0] - 'a' + 1
        return null
    }

    /** All enumerator spellings of the Nth child: 2 -> 2 | ii | b. */
    private fun childAlternatives(n: Int): List<String> = buildList {
        add(n.toString())
        ROMAN.getOrNull(n - 1)?.let { add(it) }
        if (n in 1..26) add(('a' + (n - 1)).toString())
    }.distinct()

    /**
     * Structural context is the heading field only (rule 4). Locator fields hold page, slide,
     * sheet or row positions in whatever words the pack builder used, so they are never
     * treated as parent evidence; no list of locator words is needed.
     */
    fun structuralContext(view: ChunkView): String = view.heading

    // A number chain such as 161, 161.2, 161(ii), 161 (2), 161-2. Never part of a longer
    // numeric string such as a date (11.11.2021) or a decimal amount with more parts.
    private val QUERY_ID = Regex(
        "(?<![\\p{L}\\p{N}.,/-])(\\d{1,6})((?:[ \\t]*(?:[.\\-/][ \\t]*\\(?|\\()[ \\t]*(?:\\d{1,3}|[ivxIVX]{1,5}|[a-zA-Z])[ \\t]*\\)?){0,3})(?![\\p{L}\\p{N}])(?![ \\t]*[.\\-/,][ \\t]*\\d)"
    )
    private val LABEL_BEFORE = Regex("([\\p{L}][\\p{L}\\p{M}]{1,24})\\W{0,3}$")

    /** Numbers in the question, each classified as a retrieval key or a user value. */
    fun parseQuery(query: String, index: StructureIndex): List<QueryIdentifier> {
        val clean = Normalizer.normalize(query, Normalizer.Form.NFKC)
        return QUERY_ID.findAll(clean).mapNotNull { m ->
            val parent = m.groupValues[1].trimStart('0').ifBlank { "0" }
            val childPart = m.groupValues[2]
            val childTokens = Regex("(\\d{1,3}|[ivxIVX]{1,5}|[a-zA-Z])").findAll(childPart).map { it.value }.toList()
            // Only the first child level is resolved; deeper levels stay in the literal query.
            val child = childTokens.firstOrNull()?.let(::ordinalOf)
            if (childTokens.isNotEmpty() && child == null) return@mapNotNull null
            val label = LABEL_BEFORE.find(clean.substring(0, m.range.first))?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            QueryIdentifier(
                surface = m.value.trim(),
                parent = parent,
                childOrdinal = child,
                label = label,
                isKey = index.isKey(parent, label)
            )
        }.toList()
    }

    private fun parentRegex(parent: String): Regex =
        Regex("(?<![\\p{L}\\p{N}.])" + Regex.escape(parent) + "(?![\\p{N}])")

    private fun headingLikeBefore(prefix: String): Boolean =
        Regex("(?:^|\\n|[.;:।]\\s+)[ \\t]*(?:[\\p{L}][\\p{L}\\p{M}]{1,24}\\W{0,3})?$").containsMatchIn(prefix)

    private fun exactChildForm(parent: String, child: Int): Regex {
        val alts = childAlternatives(child).joinToString("|") { Regex.escape(it) }
        return Regex(
            "(?i)(?<![\\p{L}\\p{N}.])" + Regex.escape(parent) +
                "(?:[ \\t]*[.\\-/][ \\t]*\\(?[ \\t]*|[ \\t]*\\([ \\t]*)(?:$alts)(?![A-Za-z0-9])(?:[ \\t]*\\))?(?![ \\t]*[.\\-/][ \\t]*\\d)"
        )
    }

    private fun childMarker(child: Int): Regex {
        val alts = childAlternatives(child).joinToString("|") { Regex.escape(it) }
        return Regex("(?i)(?:(?<![\\w(])\\([ \\t]*(?:$alts)[ \\t]*\\)|(?m:^[ \\t]*(?:$alts)[.)][ \\t]))")
    }

    /**
     * Admission of one chunk for one key identifier. Returns null when the chunk does not
     * satisfy the rules. [anchors] are the question's other meaningful literal tokens.
     */
    fun admit(id: QueryIdentifier, view: ChunkView, anchors: Set<String>): Admission? {
        if (!id.isKey) return null
        val structural = structuralContext(view)
        val body = view.content
        if (!body.contains(id.parent) && !structural.contains(id.parent)) return null

        val pRx = parentRegex(id.parent)
        val inHeading = pRx.find(structural)
        // An opening written with a different label, or with none when the question names one
        // ("144 FORM GFR 4" is a page number), is not the requested section.
        val openingInBody = pRx.findAll(body).firstOrNull {
            val prefix = body.substring(0, it.range.first)
            headingLikeBefore(prefix) && labelMatches(prefix, id.label)
        }

        // Rule 3: another literal anchor from the question.
        if (!hasCoAnchor(id, view, anchors)) return null

        if (id.childOrdinal != null) {
            val exact = exactChildForm(id.parent, id.childOrdinal)
            exact.find(structural)?.let { return Admission(Kind.EXACT, id, "heading:${it.value}") }
            exact.find(body)?.let { return Admission(Kind.EXACT, id, "body:${it.value}") }
        }

        val sectionStart = when {
            inHeading != null -> 0
            openingInBody != null -> openingInBody.range.last + 1
            else -> return null // Rule 1: an in-sentence mention never creates a hierarchy.
        }
        val sectionLabel = when {
            inHeading != null -> LABEL_BEFORE.find(structural.substring(0, inHeading.range.first))?.groupValues?.get(1)
            else -> LABEL_BEFORE.find(body.substring(0, openingInBody!!.range.first))?.groupValues?.get(1)
        } ?: id.label
        val section = sectionOf(body, sectionStart, sectionLabel, id.parent)

        if (id.childOrdinal == null) {
            val evidence = when {
                inHeading != null && opensHeadingSegment(structural, pRx, id.label) -> "heading"
                openingInBody != null -> "body"
                else -> HEADING_MENTION
            }
            return Admission(Kind.SECTION, id, evidence)
        }
        val marker = childMarker(id.childOrdinal).find(section)
        return if (marker != null) Admission(Kind.HIERARCHICAL, id, "child:${marker.value.trim()}")
        else Admission(Kind.PARENT_ONLY, id, "parent-only")
    }

    /**
     * The identifier opens one of the heading's segments (segments are separated by '|', the
     * heading path): at the segment start, optionally after one label word ("Rule 144 …", "144. …").
     * A mid-segment occurrence ("Restrictions under Rule 144 (xi) of the GFR") only refers to it.
     */
    private fun opensHeadingSegment(heading: String, pRx: Regex, label: String?): Boolean =
        heading.split('|').any { segment ->
            pRx.findAll(segment).any { m ->
                val prefix = segment.substring(0, m.range.first)
                headingLikeBefore(prefix) && labelMatches(prefix, label)
            }
        }

    /** The word written directly before the number is the question's label (when it has one). */
    private fun labelMatches(prefix: String, label: String?): Boolean =
        label == null || LABEL_BEFORE.find(prefix)?.groupValues?.get(1)?.lowercase(Locale.ROOT) == label

    /** Body text from [start] up to the next sibling section (same label, different number). */
    private fun sectionOf(body: String, start: Int, label: String?, parent: String): String {
        val tail = body.substring(start.coerceIn(0, body.length))
        if (label.isNullOrBlank()) return tail
        val sibling = Regex("(?im)(?:^|[.;:।]\\s+)[ \\t]*" + Regex.escape(label) + "\\W{0,3}(\\d+)")
        for (m in sibling.findAll(tail)) {
            if (m.groupValues[1].trimStart('0') != parent) return tail.substring(0, m.range.first)
        }
        return tail
    }

    private fun hasCoAnchor(id: QueryIdentifier, view: ChunkView, anchors: Set<String>): Boolean {
        val all = view.heading + "\n" + view.content + "\n" + view.sourceText
        // The question's own label written directly before the parent ("Rule 161").
        if (!id.label.isNullOrBlank() &&
            Regex("(?i)(?<![\\p{L}])" + Regex.escape(id.label) + "\\W{0,3}" + Regex.escape(id.parent) + "(?![\\p{N}])").containsMatchIn(all)
        ) return true
        if (anchors.isEmpty()) return false
        val tokens = GenericKbText.tokens(all).toHashSet()
        if (anchors.any { it in tokens }) return true
        // An acronym of consecutive words in the chunk or its source metadata (gfr ~ General Financial Rules).
        val initials = GenericKbText.tokens(view.heading + " " + view.sourceText)
            .filter { it.firstOrNull()?.isLetter() == true }
            .joinToString("") { it.take(1) }
        return anchors.any { a -> a.length in 2..8 && a.all(Char::isLetter) && initials.contains(a) }
    }

    /**
     * Pack-derived structure: which words enumerate sections, and which numbers they label.
     * Built once per installed pack from heading fields and heading-like body positions.
     */
    class StructureIndex private constructor(
        private val numbersByLabel: Map<String, Set<String>>,
        val structuralLabels: Set<String>,
        /** Per document: structural label -> numbers that open a section there. */
        private val openingsByDocument: Map<String, Map<String, Set<String>>> = emptyMap()
    ) {
        /**
         * True when [document] opens sections [label] parent-2 .. parent+2 other than [parent] itself:
         * the document is numbered by this label around the requested section. Unknown document or
         * a pack without per-document data: true (no evidence against it).
         */
        fun enumerates(document: String, label: String?, parent: String): Boolean {
            if (label == null || openingsByDocument.isEmpty()) return true
            val numbers = openingsByDocument[document]?.get(label) ?: return document.isEmpty()
            val n = parent.toIntOrNull() ?: return parent in numbers
            return (-2..2).any { d -> d != 0 && (n + d).toString() in numbers }
        }

        /**
         * A number is a key only when the question writes it after a label that this pack
         * uses to enumerate sections, and the pack contains that labelled number. A bare or
         * differently-labelled number ("rs 11.11 lakh", "11.11") is a user value: it stays in
         * the literal query but never becomes an exact-match key.
         */
        fun isKey(parent: String, label: String?): Boolean =
            label != null && label in structuralLabels && parent in numbersByLabel[label].orEmpty()

        fun labelledNumberCount(label: String): Int = numbersByLabel[label]?.size ?: 0

        fun writeTo(out: java.io.DataOutputStream) {
            out.writeInt(numbersByLabel.size)
            numbersByLabel.forEach { (label, numbers) ->
                out.writeUTF(label)
                out.writeInt(numbers.size)
                numbers.forEach { out.writeUTF(it) }
            }
            out.writeInt(structuralLabels.size)
            structuralLabels.forEach { out.writeUTF(it) }
            out.writeInt(openingsByDocument.size)
            openingsByDocument.forEach { (doc, byLabel) ->
                out.writeUTF(doc)
                out.writeInt(byLabel.size)
                byLabel.forEach { (label, numbers) ->
                    out.writeUTF(label)
                    out.writeInt(numbers.size)
                    numbers.forEach { out.writeUTF(it) }
                }
            }
        }

        companion object {
            fun readFrom(input: java.io.DataInputStream): StructureIndex {
                val labels = input.readInt()
                val byLabel = HashMap<String, Set<String>>(labels * 4 / 3 + 16)
                repeat(labels) {
                    val label = input.readUTF()
                    val n = input.readInt()
                    val numbers = HashSet<String>(n * 4 / 3 + 4)
                    repeat(n) { numbers += input.readUTF() }
                    byLabel[label] = numbers
                }
                val structural = HashSet<String>()
                repeat(input.readInt()) { structural += input.readUTF() }
                val docs = input.readInt()
                val openings = HashMap<String, Map<String, Set<String>>>(docs * 4 / 3 + 16)
                repeat(docs) {
                    val doc = input.readUTF()
                    val labels2 = input.readInt()
                    val perLabel = HashMap<String, Set<String>>()
                    repeat(labels2) {
                        val label = input.readUTF()
                        val n = input.readInt()
                        val numbers = HashSet<String>(n * 4 / 3 + 4)
                        repeat(n) { numbers += input.readUTF() }
                        perLabel[label] = numbers
                    }
                    openings[doc] = perLabel
                }
                return StructureIndex(byLabel, structural, openings)
            }
        }

        class Builder {
            private val numbersByLabel = HashMap<String, MutableSet<String>>()
            private val headingPair = Regex("([\\p{L}][\\p{L}\\p{M}]{1,24})\\W{0,3}(\\d{1,6})(?![\\p{N}])")
            private val bodyPair = Regex("(?m)(?:^|[.;:।]\\s+)[ \\t]*([\\p{L}][\\p{L}\\p{M}]{1,24})\\W{0,3}(\\d{1,6})(?![\\p{N}])(?![.,/-]\\d)")

            private val headingNumbersByLabel = HashMap<String, MutableSet<String>>()
            private val openings = HashMap<String, HashMap<String, MutableSet<String>>>()
            private val segmentOpening = Regex("^[ \\t]*([\\p{L}][\\p{L}\\p{M}]{1,24})\\W{0,3}(\\d{1,6})(?![\\p{N}])")
            private var rows = 0
            private var rowsWithHeading = 0

            fun add(heading: String, locator: String, content: String, documentKey: String = "") {
                rows++
                if (heading.isNotBlank()) rowsWithHeading++
                val structural = heading
                headingPair.findAll(structural).forEach {
                    record(it.groupValues[1], it.groupValues[2])
                    record(it.groupValues[1], it.groupValues[2], headingNumbersByLabel)
                }
                val docOpenings = openings.getOrPut(documentKey) { HashMap() }
                structural.split('|').forEach { segment ->
                    segmentOpening.find(segment)?.let { record(it.groupValues[1], it.groupValues[2], docOpenings) }
                }
                bodyPair.findAll(content).forEach {
                    record(it.groupValues[1], it.groupValues[2])
                    record(it.groupValues[1], it.groupValues[2], docOpenings)
                }
            }

            private fun record(label: String, number: String, into: MutableMap<String, MutableSet<String>> = numbersByLabel) {
                val n = number.trimStart('0').ifBlank { return }
                into.getOrPut(label.lowercase(Locale.ROOT)) { HashSet() }.add(n)
            }

            fun build(): StructureIndex {
                // A label enumerates sections when it labels several different numbers and
                // most of them have a numeric neighbour (160/161/162). Amounts and dates do not.
                val structural = numbersByLabel.filter { (_, numbers) ->
                    if (numbers.size < 5) return@filter false
                    val ints = numbers.mapNotNull { it.toIntOrNull() }.toHashSet()
                    val withNeighbour = ints.count { (it + 1) in ints || (it - 1) in ints }
                    withNeighbour >= (ints.size + 1) / 2
                }.keys.filter { label ->
                    // When the pack has section headings, a structural label must also label
                    // sections in those heading fields; table rows alone ("Rs. 5", "Rs. 6") do not qualify.
                    val packHasHeadings = rows > 0 && rowsWithHeading * 2 >= rows
                    !packHasHeadings || (headingNumbersByLabel[label]?.size ?: 0) >= 2
                }.toSet()
                val perDocument = openings.mapValues { (_, byLabel) ->
                    byLabel.filterKeys { it in structural }.mapValues { it.value.toSet() }
                }.filterValues { it.isNotEmpty() }
                return StructureIndex(numbersByLabel.mapValues { it.value.toSet() }, structural, perDocument)
            }
        }
    }
}

/** Script-neutral tokenisation shared by the Phase 1.4 components. */
object GenericKbText {
    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*")
    fun tokens(text: String): List<String> =
        TOKEN.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)).map { it.value }.toList()
}
