import com.example.arfas.ArogyaSetupMatch
var tf = 0; var tp = 0
fun t(c: Boolean, m: String) { if (c) { tp++; println("PASS $m") } else { tf++; println("FAIL $m") } }
fun main() {
    t(ArogyaSetupMatch.classify("Llama-3.2-3B-Instruct-Q4_K_M.gguf") == ArogyaSetupMatch.Kind.LLAMA, "llama gguf recognised")
    t(ArogyaSetupMatch.classify("llama.GGUF") == ArogyaSetupMatch.Kind.LLAMA, "extension match is case-insensitive")
    t(ArogyaSetupMatch.classify("ggml-small.bin") == ArogyaSetupMatch.Kind.WHISPER, "whisper ggml recognised")
    t(ArogyaSetupMatch.classify("whisper-small.en.onnx") == ArogyaSetupMatch.Kind.WHISPER, "whisper onnx recognised")
    t(ArogyaSetupMatch.classify("ASHA_Health_Guide_v2.db") == ArogyaSetupMatch.Kind.GUIDE_V2, "guide v2 recognised")
    t(ArogyaSetupMatch.classify("ASHA_Health_Guide.db") == ArogyaSetupMatch.Kind.GUIDE_V1, "guide v1 recognised")
    t(ArogyaSetupMatch.classify("photo.jpg") == null, "unrelated files ignored")
    t(ArogyaSetupMatch.classify("notes.db") == null, "other databases ignored")
    t(ArogyaSetupMatch.missingFix(ArogyaSetupMatch.Kind.LLAMA).contains("Downloads"), "llama fix names Downloads")
    t(ArogyaSetupMatch.missingFix(ArogyaSetupMatch.Kind.GUIDE_V2).contains("bundled"), "v2 fix mentions the bundled copy")
    t(ArogyaSetupMatch.ASSET_GUIDE_V2 == "guide/ASHA_Health_Guide_v2.db", "bundled v2 path")
    println(if (tf == 0) "ALL $tp PASS" else "$tf FAILED, $tp passed")
    if (tf > 0) kotlin.system.exitProcess(1)
}
