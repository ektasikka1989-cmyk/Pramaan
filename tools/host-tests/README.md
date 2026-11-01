# ArogyaDoot host tests (pure Kotlin, no Android device)

These tests cover ArogyaDoot's non-UI logic: official form definitions, AI-output grounding,
danger rules, registers, sample village, backup crypto, MCP card apply, cloud routing.
They compile the listed `app/src/main/java/com/example/arfas/*.kt` files together with two stubs
(`LogStub.kt` replaces `android.util.Log`, `ShareStub.kt` replaces Android-only `ArogyaShare`).

Last result (1 Nov 2026): all 19 suites passed — FinishVisit 34, Registry 28, Apply 22,
Forms 13, Sample 12, Flow 12, Guided 4, Speed 18, Batch2 49, Batch3 6, Batch4 15, Batch5 18,
Batch6 3, Review 28, RegisterTables 26, StockRegister 22 (FormSpecs/Cloud/Save: all checks passed).

## Requirements
- `kotlinc` 2.0+ on PATH (or the Kotlin compiler jar), JDK 17+
- `org.json` jar (e.g. `json-20240303.jar`) — Android provides org.json at runtime, the JVM does not
- `kotlinx-coroutines-core-jvm` jar (needed by ArogyaCloudApi / routing)

## Run (bash)
```bash
cd tools/host-tests
JSON_JAR=/path/to/json.jar COROUTINES_JAR=/path/to/kotlinx-coroutines-core-jvm.jar ./run.sh
```

## Which sources each suite needs
All suites: `ArogyaVisit ArogyaSources ArogyaCapture ArogyaDangerSigns ArogyaClinicalText ArogyaFinishVisit
ArogyaModelClient ArogyaCloudApi ArogyaRegistry ArogyaGuided ArogyaAiExtras ArogyaFormSpecs` + stubs.

Suggested next step: convert these to JUnit under `app/src/test/` so `gradlew test` runs them.
