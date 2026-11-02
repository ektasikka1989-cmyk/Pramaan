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
    check("line-fact", "three to five" in fact && "'Answer:'" in fact && "'When to tell the ANM:'" in fact)
    val urg = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.URGENT)
    check("line-urgent", "most useful action first" in urg)
    val det = ArogyaQuestionType.detailLine()
    check("line-detail", "five or more sentences" in det && "'What to do:'" in det && "every step, item, condition and limit" in det)
    // Sentence pieces for AROGYA_ANSWER_LEN.
    check("len-simple", ArogyaAnswerFormat.sentenceCount("One two three. Four five six!") == 2)
    check("len-numbered", ArogyaAnswerFormat.sentenceCount("1. Wash hands well.\n2. Keep it dry now.") == 2)
    check("len-hindi", ArogyaAnswerFormat.sentenceCount("साफ रखें। सूखा रखें।") == 2)
    check("len-blank", ArogyaAnswerFormat.sentenceCount("  ") == 0)
    println("AnswerFormatTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
