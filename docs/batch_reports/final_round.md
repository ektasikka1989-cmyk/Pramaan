# Final round — audit notes (written before any code change)

## Item 1 — Home mode toggle + retry override
- Exists: `AnswerPolicyStore`/`AnswerPolicy` (AnswerPolicy.kt), `ArogyaCloud.cloudFirst/setCloudFirst` + `ArogyaCloudSettingsDialog` (ArogyaCloudUi.kt), Home header `AssistChip` showing mode (ArogyaVisitScreen.kt ~387, opens AI page).
- Retry bug: `ArogyaVisitAi.askPramaan` (ArogyaVisitAi.kt:229-230) does `policyStore.set(LOCAL_FIRST)` … `finally` restore CLOUD — a crash mid-retry leaves LOCAL saved.
- Runtime reads the store at 4 answer-path sites: AndroBaselineRuntime.kt:844, 5383, 7413, 7591 (`answerPolicyStore.get()`); `chat()` has no policy param.
- Fix shape: `@Volatile policyOverride` + `effectivePolicy()` at those 4 sites (minimal core edit); askPramaan sets/clears in-memory around `doChat()`.
- UI: segmented Offline|Online + 4-state status line under Home header; long-press opens settings; status text pure + host-tested.

## Item 2 — realistic sample data
- Exists: `ArogyaSampleVillage` builder (ArogyaRegistry.kt ~590-710, internal `demo-` id prefixes + displayed "(demo)"/"Demo Gaon"/"Demo Tola"), `ArogyaOfficialForms.seedDemo` (ArogyaFormSpecs.kt), script names (ArogyaDemoScript.kt: HERO_PREFIX "Baby of Neha", Demo Gaon narration), profile prefs `arogya_profile`.
- "(demo)"/"Demo Gaon/Tola" hits: ArogyaRegistry.kt (~20 lines), ArogyaRegistryUi.kt (276, 291, 922), ArogyaDemoScript/Tour (banner + narration + facts string).
- Plan: displayed names → "Neha"/"Sunita Devi"/"Ritu"/"Meena Devi" etc.; villages → Rampur/Sundarpur, SC Rampur, PHC Rampur; keep `demo-` id prefixes; default profile "Sunita Devi · ASHA · Rampur".
- Disclosure: slim Home strip while sample loaded (tap → clear); SAMPLE watermark needs item 7 renderer; demo banner + script/villages updated EN+HI.
- Tests asserting names/villages must be found and updated (SampleTest, DemoTourTest hero checks, others).

## Item 3 — add person 4 ways
- Exists: `Beneficiary` model + `ArogyaRegistryStore.update` (ArogyaRegistry.kt), `PatientForm` add/edit via `editPatient` (ArogyaRegistryUi.kt:394), `ArogyaVisitAi.attach`/`readCard`, `route()` extraction, grounding helpers (ArogyaAiExtras), `formFor`/due rules, CSV *export* (`rchCsv` etc. ArogyaRegistry.kt:263+), dictation dialog.
- Missing: no CSV *import*, no Apache POI in app/build.gradle.kts (→ CSV-only template/import, Excel opens CSV), no add-person bottom sheet, no PDF-text path reuse check needed (Phase1TurnAttachment), no duplicate-check helper, no consent tick.
- Plan: bottom sheet (manual/photo/CSV/PDF) → shared review screen reusing PatientForm fields; header synonyms EN+HI; dd/MM/yyyy + dd-MM-yyyy + yyyy-MM-dd dates; Aadhaar 12-digit mask; duplicate rule name+relative+village OR phone; consent required; AROGYA_ADD_PERSON log.
- Host tests (new): header matching, date parsing, duplicates, grounding rejection, Aadhaar masking — pure file, no Android deps.

## Item 4 — answer shown once, fuller, More detail
- Exists: `SopAnswerCard` (ArogyaSopChat.kt:122) shows quote card THEN answer (the duplication), `ArogyaQuestionType.lengthLine` (:76), `PocketRagPromptBuilder` (object), `PocketRagQualityGate.salvage*` (:207/:227/:299), `ArogyaFlags.r3Salvage/salvageBeforeRecovery`, `ArogyaMarkdown.strip`, source dialog + `ArogyaGuideLibrary.pageUrl`.
- Plan: answer-first layout + compact source line + buttons (Read aloud/Explain/Check online/More detail/Share); 70% token-overlap de-dup (pure, tested); longer length lines behind `ArogyaFlags.r2LengthPrompt`; AROGYA_ANSWER_LEN logging at gate+salvage; More detail = same-passage regeneration replacing in place.
- Keep: chat thread, follow-up context, New conversation (untouched paths).

## Item 5 — Check online, tiers, cited sources
- Exists: `CloudMode.VERIFY` + R10-lite wide packet (PocketRagEngine.kt:99-133), `ArogyaAiRequest.onlineCheck` (ArogyaVisitAi.kt:29,203), R8 block (ArogyaVisitAi.kt:245), `KnowledgeSourcePresentation`, `ArogyaOnlinePolicy.scrub`, small "Also check online" button (ArogyaSopChat.kt:218/ArogyaVisitScreen.kt:613).
- Missing: prominent Check-online + Offline|Online tabs, Tier 2 allowlist (`ArogyaOfficialSources.ALLOW`), Tier 3 card + saved-question queue + delivery, per-sentence [n] citations + numbered mixed Sources list, structured cloud JSON segments + checker, conflict/newer flag.
- Live-web route internals (`kbLiveWebAnswer`) need a read before reuse; R8 stays on for non-allowlisted.
- Host tests (new): citations, no-source-drop, allowlist incl. `nhm.gov.in.evil.com` reject, ordering, conflict flag, tier selection, scrub.
- Log: AROGYA_ANSWER_TIER tier/mode/sources.

## Item 6 — guide DB v2 install
- Exists: `installKnowledgeDatabaseDetailed` (Phase1FeatureBundle.kt:247) already disables KB around install, selects v2, re-enables, re-warms, restores on failure; `ArogyaGuideDb.describe` v1/v2 labels; v2 rejection log FIXSIGHT_KB_INSTALL_REJECT.
- Item is verify-first: confirm v2 installs from Downloads via "Add another Knowledge .db" with the app open; check status shows "Guide v2" and library links open.
- Fix only if still failing (busy/search-model/invalid-file reasons already surfaced?). Code likely complete; phone proof pending.

## Item 7 — real-looking documents
- Exists: `ArogyaShare` (share text/PDF), `ArogyaVisitMedia.exportPdf` (plain-text StaticLayout PDF), `ArogyaPrint.printPdf` + PdfAdapter (ArogyaTables.kt:199), `ArogyaOfficialForms.table`, `ArogyaForms.riForm3/4/drugKitCard`, `McpCard` data, on-screen `OfficialFormGrid`/register tables.
- Missing: no WebView/print-adapter HTML path, no assets/doc_templates, no bundled Noto fonts (WebView can use Android system Noto Sans/Devanagari offline — avoids binary font downloads).
- Plan: one HTML+CSS renderer (assets/doc_templates/*.html) → off-screen WebView → PrintManager/createPrintDocumentAdapter PDF; A4 portrait/landscape by width; common header/footer; SAMPLE watermark for `demo-` ids; templates for MCP card, Annexure 9/HBYC/ANC/sick/FP, registers, answer-with-citations; form viewer shows same HTML (pinch-zoom).
- Host tests (new): placeholder filling, landscape choice, watermark rule, header fields.

## Item 8 — demo + AI page follow-through
- Exists: new names pending from item 2 (ArogyaDemoScript narration EN+HI, hero, village facts in ArogyaDemoTour.kt:290 ANM facts), SopAnswerCard new layout pending items 4–5, `ArogyaAiTable` row builder + AiTableTest (ArogyaAiExtras.kt).
- Plan: script/narration rename EN+HI; SOP scene uses cited layout + Open official document; one Check-online moment (online only, skipped offline); Home toggle scene beat; AI page new row "Check online / official websites" (— / ☁ Muse Spark + allowlist) + real model names re-check.
- Mostly assembly once items 1/2/4/5 land.

## Item 9 — verification + suite exit codes
- Exists: run.ps1 runs all suites; exactly 8 suites lack `exitProcess(1)`: Apply, Cloud, FinishVisit, Flow, Forms, Guided, Registry, Sample — each ends with a summary print only.
- Plan: add failing-exit to those 8; full green run; build_install.bat (needs KB_PUSH2=OK for v2 — verify the .bat pushes both DBs); foreground phone tests per checklist with log timings; M1 benchmark both modes → docs/benchmark/final.md (new dir); final report + "verified on phone" commit.
- Phone is currently NOT on adb; user reconnects for item 9.
