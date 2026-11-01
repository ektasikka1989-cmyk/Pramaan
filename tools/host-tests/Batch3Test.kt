import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // R11: procedures/lists get room for every step/item; facts keep 400.
    check("r11-proc", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.PROCEDURE) == 560)
    check("r11-list", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.LIST) == 560)
    check("r11-fact", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.FACT) == 400)
    check("r11-urgent", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.URGENT) == 400)
    check("r11-kind", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.kindOf("How should a mother prepare ORS at home?")) == 560)
    // R10/R11 switches default ON.
    check("flags3", ArogyaFlags.r10WideCloud && ArogyaFlags.r11LowReasoning)
    println("batch3: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
