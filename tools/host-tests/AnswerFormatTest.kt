import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    n++
    if (!ok) { fails++; println("FAIL $name $detail") } else println("PASS $name")
}
fun main() {
    // De-dup: the quote hidden when the answer already says it (>= 70% token overlap).
    val ans = "Keep the cord stump clean and dry. Apply nothing on it. The cord usually falls in 5 to 7 days."
    check("dup-contain", ArogyaAnswerFormat.isDuplicate(ans, "Keep the cord stump clean and dry."))
    check("dup-hindi", ArogyaAnswerFormat.isDuplicate(
        "नाभि की डंडी को साफ और सूखा रखें। उस पर कुछ न लगाएं।",
        "नाभि की डंडी को साफ और सूखा रखें।"))
    check("dup-distinct", !ArogyaAnswerFormat.isDuplicate(
        "Mix one ORS packet in one litre of clean drinking water. Give it after every loose motion.",
        "Keep the cord stump clean and dry."))
    check("dup-blank", !ArogyaAnswerFormat.isDuplicate(ans, "") && !ArogyaAnswerFormat.isDuplicate("", ans))
    check("dup-boundary-in", ArogyaAnswerFormat.isDuplicate(
        "one two three four five six seven eleven twelve",
        "one two three four five six seven eight nine ten"))
    check("dup-boundary-out", !ArogyaAnswerFormat.isDuplicate(
        "one two three four five six eleven twelve thirteen fourteen",
        "one two three four five six seven eight nine ten"))
    // Sections: labelled parts become headed blocks.
    val en = "Answer: The cord usually falls in a week.\nWhat to do:\n1. Wash hands.\n2. Keep it dry.\nWhen to tell the ANM:\nRedness around the stump."
    val secs = ArogyaAnswerFormat.sections(en)
    check("sec-count", secs.size == 3, secs.toString())
    check("sec-heads", secs.map { it.heading } == listOf("Answer", "What to do", "When to tell the ANM"))
    check("sec-body", "cord usually falls" in secs[0].body && "Wash hands" in secs[1].body && "Redness" in secs[2].body)
    val hi = ArogyaAnswerFormat.sections("उत्तर: डंडी सूखी रखें।\nक्या करें:\n1. हाथ धोएं।")
    check("sec-hindi", hi.map { it.heading } == listOf("उत्तर", "क्या करें"), hi.toString())
    val plain = ArogyaAnswerFormat.sections("Just an answer.\nTwo lines.")
    check("sec-plain", plain.size == 1 && plain[0].heading.isEmpty() && "Two lines" in plain[0].body)
    val md = ArogyaAnswerFormat.sections("## Answer:\nGive **ORS** now.")
    check("sec-markdown", md.size == 1 && md[0].heading == "Answer" && md[0].body == "Give ORS now.", md.toString())
    val intro = ArogyaAnswerFormat.sections("Some intro.\nAnswer: It is fine.")
    check("sec-intro", intro.size == 2 && intro[0].heading.isEmpty() && intro[1].heading == "Answer")
    // Length line per type (behind ArogyaFlags.r2LengthPrompt at the call sites).
    val proc = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.PROCEDURE)
    check("line-proc", "numbered step" in proc && "'What to do:'" in proc)
    val list = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.LIST)
    check("line-list", "one line per item" in list && "'What to do:'" in list)
    val fact = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT)
    check("line-fact", "six to ten" in fact && "'Answer:'" in fact && "'Why it matters:'" in fact && "'When to tell the ANM:'" in fact && "'Common mistakes to avoid:'" in fact)
    val urg = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.URGENT)
    check("line-urgent", "most useful action first" in urg)
    val det = ArogyaQuestionType.detailLine()
    check("line-detail", "five or more sentences" in det && "'What to do:'" in det && "every step, item, condition and limit" in det && "'Common mistakes to avoid:'" in det)
    // Part B3: cloud totals, Hindi labels, new section labels.
    val factCloud = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT, "What is ORS?", cloud = true)
    check("line-fact-cloud", "eight to fourteen" in factCloud && "six to ten" !in factCloud)
    val factHi = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT, "ओआरएस क्या है?")
    check("line-fact-hi", "'उत्तर:'" in factHi && "'क्यों ज़रूरी:'" in factHi && "'क्या करें:'" in factHi && "'ANM को कब बताएँ:'" in factHi && "'आम गलतियाँ:'" in factHi)
    val why = ArogyaAnswerFormat.sections("Answer: It is fine.\nWhy it matters:\nIt helps growth.\nCommon mistakes to avoid:\nGiving water.")
    check("sec-why", why.map { it.heading } == listOf("Answer", "Why it matters", "Common mistakes to avoid"), why.toString())
    val hiNew = ArogyaAnswerFormat.sections("उत्तर: ठीक है।\nक्यों ज़रूरी:\nविकास होता है।\nआम गलतियाँ:\nपानी देना।")
    check("sec-hi-new", hiNew.map { it.heading } == listOf("उत्तर", "क्यों ज़रूरी", "आम गलतियाँ"), hiNew.toString())
    // Part B4: family prompt (5-8 warm sentences, full inputs) + grounded keeper (max 8).
    val fp = ArogyaAiExtras.familyPrompt("Give ORS after every loose motion.", "ORS saves lives.", false)
    check("fam-ask", "5 to 8" in fp && "what not to do" in fp && "warning signs" in fp && "ASHA" in fp)
    val fpHi = ArogyaAiExtras.familyPrompt("उत्तर।", "वाक्य।", true)
    check("fam-hi", "Devanagari" in fpHi && "5 to 8" in fpHi)
    val longAns = "Give ORS. ".repeat(300)
    check("fam-full", ArogyaAiExtras.familyPrompt(longAns, "Q.", false).contains(longAns))
    val eight = (1..8).joinToString(" ") { "Keep the child warm and clean today number $it." }
    check("fam-max8", ArogyaAnswerFormat.sentenceCount(ArogyaAiExtras.groundedSentences(eight, eight, max = 8)) == 8)
    check("fam-newnum", !ArogyaAiExtras.groundedSentences("Give ORS now. Give 500 tablets daily.", "Give ORS now.", max = 8).contains("500"))
    // Part B7: inline superscript citations + flowing-paragraph line breaks.
    check("sup-single", ArogyaAnswerFormat.supDigits("2") == "²")
    check("sup-multi", ArogyaAnswerFormat.supDigits("10") == "¹⁰")
    check("sup-mixed", ArogyaAnswerFormat.supDigits("W1") == "W¹")
    check("nl-step", ArogyaAnswerFormat.needsNewLine("1. Wash hands well."))
    check("nl-bullet", ArogyaAnswerFormat.needsNewLine("• Keep it dry."))
    check("nl-flow", !ArogyaAnswerFormat.needsNewLine("Keep the cord stump clean."))
    // Sentence pieces for AROGYA_ANSWER_LEN.
    check("len-simple", ArogyaAnswerFormat.sentenceCount("One two three. Four five six!") == 2)
    check("len-numbered", ArogyaAnswerFormat.sentenceCount("1. Wash hands well.\n2. Keep it dry now.") == 2)
    check("len-hindi", ArogyaAnswerFormat.sentenceCount("साफ रखें। सूखा रखें।") == 2)
    check("len-blank", ArogyaAnswerFormat.sentenceCount("  ") == 0)
    // UI6: the model's source tags are removed from the shown answer; the app draws its own superscripts.
    val sc = ArogyaAnswerFormat::stripCitationMarkers
    check("strip-s1", sc("Keep the cord dry [S1].") == "Keep the cord dry.", sc("Keep the cord dry [S1]."))
    check("strip-list", sc("Give ORS [S1, S2] after each stool.") == "Give ORS after each stool.", sc("Give ORS [S1, S2] after each stool."))
    check("strip-num", sc("Give zinc for 14 days [1][3].") == "Give zinc for 14 days.", sc("Give zinc for 14 days [1][3]."))
    check("strip-paren", sc("Refer at once (S2).") == "Refer at once.", sc("Refer at once (S2)."))
    check("strip-source", sc("Wash hands [Source: S1] first.") == "Wash hands first.", sc("Wash hands [Source: S1] first."))
    check("strip-web", sc("See the guideline [W2].") == "See the guideline.")
    check("strip-cjk", sc("Keep warm 【1】.") == "Keep warm.")
    check("strip-hindi", sc("नाल सूखी रखें [S1]।") == "नाल सूखी रखें।", sc("नाल सूखी रखें [S1]।"))
    check("keep-link", sc("Read [the guide](https://x.in/g.pdf).") == "Read [the guide](https://x.in/g.pdf).")
    check("keep-text", sc("Give [half a cup] of food.") == "Give [half a cup] of food.")
    check("keep-dose", sc("Give 14 days (2 tablets).") == "Give 14 days (2 tablets).")
    check("keep-lines", sc("1. Wash [S1].\n2. Dry [S2].") == "1. Wash.\n2. Dry.", sc("1. Wash [S1].\n2. Dry [S2]."))
    // UI6: one superscript per run of sentences with the same sources.
    check("cite-runs", ArogyaAnswerFormat.citeAt(listOf(listOf("1"), listOf("1"), listOf("2"), emptyList(), listOf("2"))) ==
        listOf(false, true, true, false, true))
    check("cite-last", ArogyaAnswerFormat.citeAt(listOf(listOf("1", "3"))) == listOf(true))
    println("AnswerFormatTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
