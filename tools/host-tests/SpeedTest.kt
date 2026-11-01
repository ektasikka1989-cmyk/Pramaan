import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // Switches default ON (Batch 7 can turn any one off to isolate a regression).
    check("flags on", ArogyaFlags.r1WideEvidence && ArogyaFlags.r2LengthPrompt && ArogyaFlags.r9NewDatabase &&
        ArogyaFlags.r8GuidelineOnly && ArogyaFlags.r3Salvage && ArogyaFlags.coverageFuzzy &&
        ArogyaFlags.r10WideCloud && ArogyaFlags.r11LowReasoning && ArogyaFlags.speedStreamCloud &&
        ArogyaFlags.speedFastRerank && ArogyaFlags.speedSkipSecondPass && ArogyaFlags.speedWarmSearch &&
        ArogyaFlags.sopFollowUpContext)
    // Item 2 switches default to current behaviour until the phone baseline is reported.
    check("item2 defaults", ArogyaFlags.r1Profile == 1 && !ArogyaFlags.llamaBatch2048 &&
        !ArogyaFlags.llamaFlashAttn && !ArogyaFlags.salvageBeforeRecovery)
    // Fast re-rank decision (pure logic; the phone behaviour is guarded by M1).
    check("fast decisive", GenericKbPhase14Engine.fastRerankSkipped(20, floatArrayOf(9.0f, 5.0f, 4.0f, 3.0f, 2.0f, 1.0f, 0.5f, 0.1f)) == 12)
    check("fast close", GenericKbPhase14Engine.fastRerankSkipped(20, floatArrayOf(9.0f, 8.0f, 4.0f, 3.0f, 2.0f, 1.0f, 0.5f, 0.1f)) == 0)
    check("fast low", GenericKbPhase14Engine.fastRerankSkipped(20, floatArrayOf(6.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f)) == 0)
    check("fast small pool", GenericKbPhase14Engine.fastRerankSkipped(8, floatArrayOf(9.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f)) == 0)
    check("fast bad head", GenericKbPhase14Engine.fastRerankSkipped(20, floatArrayOf(9.0f, 1.0f)) == 0)
    check("fast boundary", GenericKbPhase14Engine.fastRerankSkipped(20, floatArrayOf(7.0f, 4.5f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f)) == 12)
    // SSE stream parsing.
    check("sse content", SseStreamParser.contentOf("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}") == "Hello")
    check("sse done", SseStreamParser.contentOf("data: [DONE]") == null)
    check("sse comment", SseStreamParser.contentOf(": ping") == null && SseStreamParser.contentOf("") == null)
    check("sse role", SseStreamParser.contentOf("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}") == null)
    check("sse escape", SseStreamParser.contentOf("data: {\"choices\":[{\"delta\":{\"content\":\"a\\nb\\u0939\"}}]}") == "a\nb\u0939")
    check("sse text fallback", SseStreamParser.deltaContent("{\"choices\":[{\"text\":\"hi\"}]}") == "hi")
    check("sse malformed", SseStreamParser.contentOf("data: {oops") == null)
    // Visit-AI client: minimal reasoning for Muse Spark only, behind r11LowReasoning.
    val spark = OpenAiCompatibleClient(ArogyaModelEndpoints.byId("meta")!!, { "k" })
    check("spark minimal", org.json.JSONObject(spark.requestBody("s", "u", 10)).optString("reasoning_effort") == "minimal")
    ArogyaFlags.r11LowReasoning = false
    check("spark flag off", !org.json.JSONObject(spark.requestBody("s", "u", 10)).has("reasoning_effort"))
    ArogyaFlags.r11LowReasoning = true
    check("glimmer untouched", !org.json.JSONObject(OpenAiCompatibleClient(ArogyaModelEndpoints.byId("together_glimmer")!!, { "k" }).requestBody("s", "u", 10)).has("reasoning_effort"))
    println("speed: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
