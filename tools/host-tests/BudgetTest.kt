import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // Part B2: one token budget for every generation.
    check("lang-en", ArogyaBudget.langOf("How to make ORS at home?") == "en")
    check("lang-hi", ArogyaBudget.langOf("ORS kaise banaye?") != "en") // roman Hindi is not English
    check("lang-hi-deva", ArogyaBudget.langOf("बच्चे को टीका कब लगेगा?") == "hi")
    check("lang-hinglish", ArogyaBudget.langOf("ORS kaise banana hai") == "hinglish")
    check("mult-en", ArogyaBudget.tokens(600, "en") == 600)
    check("mult-hi", ArogyaBudget.tokens(600, "hi") == 1500)
    check("mult-hinglish", ArogyaBudget.tokens(1000, "hinglish") == 1300)
    check("cap-exact", ArogyaBudget.tokens(600, "en", promptTokens = 3696, contextWindow = 4096) == 400)
    check("cap-min", ArogyaBudget.tokens(1000, "hi", promptTokens = 3900, contextWindow = 4096) == 256)
    check("floor-min", ArogyaBudget.tokens(100, "en", promptTokens = 4000, contextWindow = 4096) == 256)
    check("base-fact", ArogyaBudget.baseFor("ask_sop_fact", false) == 600 && ArogyaBudget.baseFor("ask_sop_fact", true) == 1200)
    check("base-proc", ArogyaBudget.baseFor("ask_sop_procedure", false) == 800 && ArogyaBudget.baseFor("ask_sop_procedure", true) == 1600)
    check("base-list", ArogyaBudget.baseFor("ask_sop_list", false) == 800 && ArogyaBudget.baseFor("ask_sop_list", true) == 1600)
    check("base-urgent", ArogyaBudget.baseFor("ask_sop_urgent", false) == 800 && ArogyaBudget.baseFor("ask_sop_urgent", true) == 1600)
    check("base-detail", ArogyaBudget.baseFor("ask_sop_detail", false) == 1000 && ArogyaBudget.baseFor("ask_sop_detail", true) == 2400)
    check("base-check", ArogyaBudget.baseFor("check_online", true) == 1600)
    check("base-family", ArogyaBudget.baseFor("family_explain", false) == 450 && ArogyaBudget.baseFor("family_explain", true) == 900)
    check("base-anm", ArogyaBudget.baseFor("anm_message", false) == 450 && ArogyaBudget.baseFor("anm_message", true) == 900)
    check("base-card", ArogyaBudget.baseFor("card_read", false) == 900 && ArogyaBudget.baseFor("card_read", true) == 1500)
    check("base-person", ArogyaBudget.baseFor("person_extract", false) == 900 && ArogyaBudget.baseFor("person_extract", true) == 1500)
    check("base-stock", ArogyaBudget.baseFor("voice_stock", false) == 400 && ArogyaBudget.baseFor("voice_stock", true) == 900)
    check("base-notes", ArogyaBudget.baseFor("organise_notes", false) == 700 && ArogyaBudget.baseFor("organise_notes", true) == 1500)
    check("base-finish", ArogyaBudget.baseFor("finish_visit", false) == 900 && ArogyaBudget.baseFor("finish_visit", true) == 1500)
    check("sop-fact-en", ArogyaBudget.askSop(ArogyaQuestionType.Kind.FACT, "What is ORS?", false) == 600)
    check("sop-fact-hi", ArogyaBudget.askSop(ArogyaQuestionType.Kind.FACT, "ओआरएस क्या है?", false) == 1500)
    check("sop-proc-cloud", ArogyaBudget.askSop(ArogyaQuestionType.Kind.PROCEDURE, "How to make ORS?", true) == 1600)
    check("ceil-fact", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.FACT) == 600)
    check("ceil-proc", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.PROCEDURE) == 800)
    check("ceil-list", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.LIST) == 800)
    check("ceil-urgent", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.URGENT) == 800)
    println("budget: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
