# ArogyaDoot host tests on Windows (mirrors run.sh). Run from repo root:
#   powershell -ExecutionPolicy Bypass -File tools\host-tests\run.ps1
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe')) {
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}
$kotlincBin = 'C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin'
$env:PATH = "$kotlincBin;$env:PATH"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
$SRC = '..\..\app\src\main\java\com\example\arfas'
$CP = "lib\json-20240303.jar;lib\kotlinx-coroutines-core-jvm-1.11.0.jar;lib\kotlin-stdlib-2.1.20.jar"
$QCP = '"' + $CP + '"' # quote: cmd.exe splits args on ';'
$FILES = @('LogStub.kt', 'ShareStub.kt', 'ContextStub.kt', 'AiRequestStub.kt')
foreach ($f in 'ArogyaVisit', 'ArogyaSources', 'ArogyaCapture', 'ArogyaDangerSigns', 'ArogyaClinicalText', 'ArogyaFinishVisit', 'ArogyaModelClient', 'ArogyaCloudApi', 'ArogyaRegistry', 'ArogyaGuided', 'ArogyaAiExtras', 'ArogyaFormSpecs', 'ArogyaVisitQueue', 'ArogyaFlags', 'SseStreamParser', 'GenericKbPhase14Engine', 'GenericKbRetrievalPlan', 'GenericKbCorpusProfile', 'GenericKbIdentifierLane', 'GenericKbQueryPlan', 'GenericKbAliasIndex', 'GenericKbKeyFacts', 'AnswerProof', 'KnowledgeSourcePresentation', 'CoreHealthFacts', 'KbReplyText', 'ArogyaQuestionType', 'ArogyaMarkdown', 'ArogyaDoseCheck', 'ArogyaHindiTerms', 'ArogyaGuideDb', 'ArogyaLang', 'ArogyaDemoScript', 'MetaVisionMath', 'ArogyaAddPerson', 'ArogyaAnswerFormat', 'ArogyaOfficialSources', 'ArogyaOfficialAnswer', 'ArogyaOnlinePolicy', 'ArogyaClinicalGrounding', 'ArogyaEvidenceSafety', 'ArogyaDocRender', 'ArogyaVoiceStock', 'ArogyaChangeSummary', 'ArogyaOrigin') { $FILES += "$SRC\$f.kt" }
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
New-Item -ItemType Directory out | Out-Null
foreach ($t in 'FormSpecsTest', 'FinishVisitTest', 'RegistryTest', 'ApplyTest', 'FormsTest', 'SampleTest', 'FlowTest', 'CloudTest', 'GuidedTest', 'SaveTest', 'SpeedTest', 'Batch2Test', 'Batch3Test', 'Batch4Test', 'Batch5Test', 'Batch6Test', 'ReviewTest', 'RegisterTablesTest', 'StockRegisterTest', 'GuideLibraryTest', 'MedScanTest', 'DemoTourTest', 'AiTableTest', 'CountDotsTest', 'PracticeTest', 'AddPersonTest', 'AnswerFormatTest', 'TierTest', 'DocRenderTest', 'LinkedSaveTest', 'VoiceStockTest', 'StockMapTest', 'ChangeSummaryTest', 'OriginTest', 'RefillTest', 'MonthlyTest') {
  Write-Output "== $t"
  & kotlinc.bat @FILES "$t.kt" -cp $QCP -d "out\$t"
  if ($LASTEXITCODE -ne 0) { throw "$t compile failed" }
  & kotlin.bat -cp ('"out\' + $t + ';' + $CP + '"') "${t}Kt"
  if ($LASTEXITCODE -ne 0) { throw "$t run failed" }
}
