#!/usr/bin/env bash
# Compile ArogyaDoot's pure-Kotlin logic with the host tests and run every suite.
set -e
cd "$(dirname "$0")"
SRC=../../app/src/main/java/com/example/arfas
: "${JSON_JAR:?set JSON_JAR to an org.json jar}"
: "${COROUTINES_JAR:?set COROUTINES_JAR to kotlinx-coroutines-core-jvm jar}"
CP="$JSON_JAR:$COROUTINES_JAR"
FILES="LogStub.kt ShareStub.kt"
for f in ArogyaVisit ArogyaSources ArogyaCapture ArogyaDangerSigns ArogyaClinicalText ArogyaFinishVisit ArogyaModelClient \
         ArogyaCloudApi ArogyaRegistry ArogyaGuided ArogyaAiExtras ArogyaFormSpecs ArogyaVisitQueue ArogyaFlags SseStreamParser \
         GenericKbPhase14Engine GenericKbRetrievalPlan GenericKbCorpusProfile GenericKbIdentifierLane GenericKbQueryPlan \
         GenericKbAliasIndex GenericKbKeyFacts AnswerProof KnowledgeSourcePresentation CoreHealthFacts KbReplyText ArogyaQuestionType \
         ArogyaMarkdown ArogyaDoseCheck ArogyaHindiTerms ArogyaGuideDb; do FILES="$FILES $SRC/$f.kt"; done
rm -rf out && mkdir out
for t in FormSpecsTest FinishVisitTest RegistryTest ApplyTest FormsTest SampleTest FlowTest CloudTest GuidedTest SaveTest SpeedTest Batch2Test; do
  echo "== $t"
  kotlinc $FILES $t.kt -cp "$CP" -d out/$t 2>&1 | grep -E "error" || true
  kotlin -cp "out/$t:$CP" ${t}Kt
done
