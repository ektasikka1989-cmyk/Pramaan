import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // R2: question types (English, Hindi, Hinglish).
    check("t-procedure", ArogyaQuestionType.kindOf("How should a mother prepare ORS at home?") == ArogyaQuestionType.Kind.PROCEDURE)
    check("t-procedure-hi", ArogyaQuestionType.kindOf("घर पर ORS कैसे बनाएं?") == ArogyaQuestionType.Kind.PROCEDURE)
    check("t-list", ArogyaQuestionType.kindOf("Which spacing contraceptive methods are available?") == ArogyaQuestionType.Kind.LIST)
    check("t-fact", ArogyaQuestionType.kindOf("What is the zinc dose for a baby?") == ArogyaQuestionType.Kind.FACT)
    // Review: "what are the danger signs" asks FOR the signs (list every item), even with a referral phrase.
    check("t-urgent", ArogyaQuestionType.kindOf("What are the danger signs in a newborn that need immediate referral?") == ArogyaQuestionType.Kind.LIST)
    check("t-urgent-hi", ArogyaQuestionType.kindOf("नवजात शिशु में खतरे के लक्षण क्या हैं?") == ArogyaQuestionType.Kind.LIST)
    check("t-urgent-kept", ArogyaQuestionType.kindOf("Baby is bleeding heavily, what should I do?") == ArogyaQuestionType.Kind.URGENT)
    check("t-hinglish", ArogyaQuestionType.kindOf("Baby ko diarrhoea ho to ORS kaise dena chahiye?") == ArogyaQuestionType.Kind.PROCEDURE)
    val proc = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.PROCEDURE)
    check("t-line-proc", "numbered step" in proc && "three to six" !in proc)
    val fact = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT)
    check("t-line-fact", "six to ten" in fact)
    // Markdown strip (display only).
    check("md-bold", ArogyaMarkdown.strip("Give **ORS** now.") == "Give ORS now.")
    check("md-bullet", ArogyaMarkdown.strip("* Wash hands\n- Boil water") == "• Wash hands\n• Boil water")
    check("md-head", ArogyaMarkdown.strip("## Steps\nDo this.") == "Steps\nDo this.")
    check("md-code", ArogyaMarkdown.strip("Use `ORS` now.") == "Use ORS now.")
    check("md-link", ArogyaMarkdown.strip("See [guide](https://x.gov.in/g.pdf) now.") == "See guide now.")
    check("md-keep", ArogyaMarkdown.strip("Give 2*3 spoons of salt_sugar mix.") == "Give 2*3 spoons of salt_sugar mix.")
    // Dose safety.
    check("dose-q", ArogyaDoseCheck.isDoseQuestion("What is the weekly IFA dose for a school-going adolescent girl?"))
    check("dose-notq", !ArogyaDoseCheck.isDoseQuestion("How many home visits should an ASHA make?"))
    check("dose-group-adol", ArogyaDoseCheck.askedGroup("What is the weekly IFA dose for a school-going adolescent girl?") == ArogyaDoseCheck.Group.ADOLESCENT)
    check("dose-group-infant", ArogyaDoseCheck.askedGroup("What is the zinc dose for a 4-month-old baby?") == ArogyaDoseCheck.Group.INFANT)
    check("dose-group-hi", ArogyaDoseCheck.askedGroup("दस्त में 4 महीने के बच्चे को जिंक की खुराक क्या है?") == ArogyaDoseCheck.Group.INFANT)
    val evOk = "Adolescent girls 10-19 years: one blue IFA tablet weekly (100 mg iron)."
    val evWrong = "Children 5-9 years: one pink IFA tablet weekly (45 mg iron)."
    check("dose-match", ArogyaDoseCheck.check("weekly IFA dose for a school-going adolescent girl?", "Give 100 mg weekly.", evOk) == ArogyaDoseCheck.Verdict.MATCH)
    check("dose-mismatch", ArogyaDoseCheck.check("weekly IFA dose for a school-going adolescent girl?", "Give 45 mg weekly.", evWrong) == ArogyaDoseCheck.Verdict.MISMATCH)
    check("dose-uncertain", ArogyaDoseCheck.check("weekly IFA dose?", "Give 100 mg weekly.", "Give 100 mg weekly.") == ArogyaDoseCheck.Verdict.UNCERTAIN)
    check("dose-caution", "ANM" in ArogyaDoseCheck.caution(ArogyaDoseCheck.Group.ADOLESCENT, false))
    // Hindi search views (retrieval only; question untouched).
    check("hi-needs", ArogyaHindiTerms.needsViews("नवजात शिशु में खतरे के लक्षण क्या हैं?"))
    check("hi-hinglish", ArogyaHindiTerms.needsViews("bacche ko dast ho toh kya karna chahiye"))
    check("hi-not", !ArogyaHindiTerms.needsViews("What is the zinc dose for a baby?"))
    val v14 = ArogyaHindiTerms.views("नवजात शिशु में खतरे के लक्षण क्या हैं?")
    check("hi-q14", v14.size == 1 && "newborn" in v14[0] && "danger" in v14[0])
    val v17 = ArogyaHindiTerms.views("गर्भवती महिला की ANC जांच कितनी बार होनी चाहिए?")
    check("hi-q17", v17.size == 1 && "pregnant" in v17[0])
    // Coverage matching: typo-tolerant when the switch is on.
    check("cov-dist", AnswerProof.editDistance("umblical", "umbilical", 2) == 1)
    check("cov-same", AnswerProof.editDistance("dose", "dose", 1) == 0)
    check("cov-fuzzy", AnswerProof.fuzzyHit("umblical", listOf("nothing", "umbilical", "cord")))
    check("cov-nofuzzy", !AnswerProof.fuzzyHit("pregnancy", listOf("nothing", "umbilical", "cord")))
    check("cov-alias", AnswerProof.aliasHit("dast", listOf("child", "diarrhoea", "days")))
    val q = "What should be applied to the umblical cord stump of a newborn baby?"
    val ev = listOf("Nothing is to be applied on the umbilical cord stump of the newborn baby every day.")
    check("cov-typo", AnswerProof.missingTopicWords(q, ev).isEmpty())
    ArogyaFlags.coverageFuzzy = false
    check("cov-legacy", AnswerProof.missingTopicWords("What about the umbillical stump?", ev).isNotEmpty())
    check("cov-legacy-typo-fixed", AnswerProof.missingTopicWords(q, ev).isEmpty())
    ArogyaFlags.coverageFuzzy = true
    check("cov-real-gap", AnswerProof.missingTopicWords("Explain tender procurement purchase rules", ev).isNotEmpty())
    // Quote bullets stripped.
    check("q-bullet", KnowledgeSourcePresentation.stripBullet("* Nothing is to be applied.") == "Nothing is to be applied.")
    check("q-num", KnowledgeSourcePresentation.stripBullet("2) Wash hands.") == "Wash hands.")
    check("q-plain", KnowledgeSourcePresentation.stripBullet("Wash hands.") == "Wash hands.")
    // R1-lite window cuts keep paragraphs.
    val raw = "Intro line here.\n\n1. First step of the method with enough words to fill.\n2. Second step of the method with enough words to fill.\n\nLast paragraph here."
    val w = GenericKbKeyFacts.windowR1(raw, listOf(GenericKbKeyFacts.Fact(0, 0, "Second step of the method", 1f)), 0, 120)
    check("r1-newline", "\n" in w)
    check("r1-short", GenericKbKeyFacts.windowR1("short text", emptyList(), 0, 1100) == "short text")
    // R9 guide selection.
    check("r9-want", ArogyaGuideDb.wantedName() == "ASHA_Health_Guide_v2.db" && ArogyaGuideDb.wantedVersion() == 2)
    check("r9-switch", ArogyaGuideDb.needsSwitch(0) && !ArogyaGuideDb.needsSwitch(2) && !ArogyaGuideDb.needsSwitch(-1))
    ArogyaFlags.r9NewDatabase = false
    check("r9-old", ArogyaGuideDb.wantedName() == "ASHA_Health_Guide.db" && !ArogyaGuideDb.needsSwitch(0))
    ArogyaFlags.r9NewDatabase = true
    check("r9-desc", ArogyaGuideDb.describe(2) == "Guide v2")
    // New switches default ON.
    check("flags2", ArogyaFlags.r7SourceLine && ArogyaFlags.doseAgeMatch && ArogyaFlags.hindiRewrite && ArogyaFlags.speedWarmSearch)
    println("batch2: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
