# Gap round — audit notes (3–5 lines per item)

## Item 1 — Official forms and cards, row for row
- Specs live in `ArogyaOfficialForms` (`ArogyaFormSpecs.kt`: ANNEX9/HBYC/MCP_ANC/SICK/FP + `table()`); registers in `ArogyaForms` (`ArogyaRegistry.kt`: riForm3/riForm4/drugKitCard/homeVisitForm); provenance plumbing exists (`FormTable.source` → screen grid + `table.html` docsrc).
- Official text verified from guide DB passages (not memory): `asha_fac_en.pdf` (Annexure 9 pp. 59–61, Annexure 5 p. 53, drug list p. 10), `hbyc_en.pdf` (HBYC card p. 90, sick §5.4 pp. 63–69, drug doses pp. 91–94), `hbnc_hbyc_fac_en.pdf` (HBYC pp. 28–29), `immunization.pdf` (RI Forms 3/4/5 pp. 56–61), `drugkit.pdf` (Annexure 5 card pp. 5–6).
- `_claude_work/sources/` does not exist — MCP_Card_2018.pdf missing. Per the rules, MCP-ANC row rebuild + MCP immunization/growth sections + MCP PDF relayout are STOPPED and reported, not guessed. MCP_ANC rows and MCP card doc stay as-is, marked blocked in `docs/forms_verification.md`.
- Fixes: ANNEX9 cold-season wording + D header; source page numbers on all specs/registers; SICK retitle + diarrhoea hint; FP retitle; `(app addition)` tags (HBYC albendazole/cf, SICK zinc, Next-visit row); RI Form 3 col C label; RI Form 4 +TT/ANC-due columns; drugKitCard +Symbol column. New `FormsVerifyTest` guards the official rows.

## Item 2 — Instant offline fallback everywhere
- Cloud funnel is `ArogyaCloud.withDeadline` (`ArogyaCloudUi.kt:97`, detached async + `withTimeoutOrNull`, no net-loss awareness); callers: `route()` cloud 25 s (`ArogyaVisitAi.kt:75`), fill cloud 25 s (`:652`), Muse Voice 30 s (`ArogyaCloudUi.kt:120`); SAM count has NO deadline (`ArogyaCloudUi.kt:306`); Ask SOP cloud fails into the phone retry already (`ArogyaVisitAi.kt:232-240`) but rethrows plain cancels.
- No connectivity watcher exists (`registerDefaultNetworkCallback` absent); `MetaOnline.hasInternet` polls per call. `speak()` (`ArogyaVisitScreen.kt:228`) has no utterance listener/retry. Scrub gap: `route()` callers anm/family/card/person/medicine/voice-stock send unscrubbed cloud prompts; organise/finish/fill/Ask SOP already scrub.
- Changes: new `ArogyaNetWatch` (watcher + generation + `justLost()`); deadline polls the generation and logs `AROGYA_NET_LOST`; `cancelCloudGeneration()` (3-line runtime addition) on lost; Ask SOP converts net-loss cancels to phone fallback; SAM failure → `countTaught`/teach/manual; TTS onError offline retry; Muse Voice 15 s; `scrubGeneric` + card/person local-only `route()`; `ScrubTest`.

## Item 3 — Evaluator-proof first run
- No setup wizard, no first-run flag, no `semantic.zip` anywhere in repo/assets/code (only `assets/semantic/` = the E5 embedding model, in use — nothing to remove). Reuse: `ModelFileManager.installFromUri` (llama+progress), MediaStore Downloads lookup (`MainActivity.findDownloadDbUri`), guide install `onImportKnowledge` → `installKnowledgeDatabaseDetailed`, `loadSample()` (`ArogyaVisitScreen.kt:188`), More menu (`:497-504`).
- Build: `ArogyaSetupMatch` (pure name classifier + `SetupMatchTest`), `ArogyaSetup` (prefs flag, scan, Downloads find, whisper copy, asset→Downloads→install), `ArogyaSetupUi` wizard (steps + progress + fixes + Ready), auto-open on first run + More entry; bundle v2 DB in `assets/guide/`; Meta-key line in `ArogyaCloudSettingsDialog`. APK before: 767 MB.

## Item 4 -- Growth chart and ANC trend (fixed rules)
- Weights live in McpCard.weights + ancDetails (ArogyaRegistry.kt:31-35); child page lists weights (ArogyaRegistryUi.kt:831-836); pregnant page lists ANC rows (:810-817); MCP PDF has weight-record + ANC tables (ArogyaDocRender.mcpDoc, mcp.html); weightNote gives the fell/gained line (ArogyaFormSpecs.kt:318).
- Guide DB has zone names but no numeric cutoffs, so WHO weight-for-age z-score tables (boys+girls, 0-60 mo, median/-2SD/-3SD) are parsed from the official WHO simplified field-table PDFs into assets/growth/who_wfa.json (spot-checked: boys 12 mo = 9.6/7.7/6.9).
- New pure ArogyaGrowth (parse/zone/geometry/SVG/ANC-trend) + GrowthChart Canvas composable with tap-a-dot; PDF gets inline SVG via {{{growthSvg}}}; ANC trend line on her page + PDF; new GrowthTest.

## Item 5 -- Finish and verify the linked records
- All pieces present from the linked round: applyGive/apply keyed saves (ArogyaFormSpecs.kt:358-376), STOCK maps all 8 added items incl. calcium/parac/ctz/mal_rdt/act/ecp/ptk (:325-328), VoiceStockDialog with parseRules+ground+toTxn (ArogyaStockUi.kt), ChangeSummary.diff, ArogyaOrigin history, LowStockCard+refill, TodayPlan+BackupStatusRow (ArogyaRegistryUi.kt), incentiveEstimate on Incentives 2025 rates (ArogyaRegistry.kt).
- No code gaps found, so this item adds the cross-check host test only: DayCheckTest runs a scripted day (2 visits, 1 give, 1 voice receipt, 1 new person + re-save) and asserts forms, MCP card, stock balances, diary, due lists and the monthly report all agree.

## Item 6 -- Demo content gaps
- 14 scenes in ArogyaDemoScript.scenes (meet..beforeafter); live steps when(scene) at ArogyaDemoTour.kt:233, render at :656, n=scenes.size (auto count). Hero load is onLoadSample()+delay(1500) (:204). Reuse: familyExplainDetailed (ArogyaVisitAi), extractPersonFields+ArogyaPersonCsv.toBeneficiary+hasAadhaar (photo path), ArogyaVisitSave.save returns the What-changed summary, ArogyaDocPdf.Viewer for the card, GrowthChartCard (Item 4), guideLibrary()+totals() for runtime counts.
- Changes: suspend-until-hero + AROGYA_DEMO_HERO log + Annexure 9 Day 7 line; new addperson scene (idx 2) and explain scene (idx 6) with EN+HI narration; cord-stump QUESTION consts; scene-0 runtime guide counts; equal EN/HI sentence counts everywhere; saved-scene summary; card-scene Viewer+chart; benefit() rows for the 2 new scenes; DemoTourTest to 16 scenes.

## Item 7 -- Demo screen layout optimisation
- Scene screen is ArogyaDemoTour.kt:568+: full-width DEMO banner card + separate Scene row + 3 FilterChips, captions fixed at weight(0.32f) with no auto-scroll, cumulative AI-at-work Card above the live body, full-width Exit button. DemoSceneBody holds all 16 scene bodies; ticks list is cumulative (no per-scene split).
- Rework: one compact top bar (chip + Scene N/M + EN/HI + narrate + overflow Exit, progress under, <=72dp); title max 2 lines; single LazyColumn content (narration sentences as highlightable items with animateScrollToItem, then live-result Card with tap-to-expand Dialog); per-scene AI ticks via a scene-entry tickMark rendered as a slim expandable chip row (+ fixed-rule chip on due/form/danger); bottom Back/Replay/Next with Next widest; landscape splits narration/live into two columns. Screenshots need the phone (Item 8).

## Item 8 -- Full phone verification, benchmark and screenshots
- BLOCKED: adb device list empty across Items 4-7 (build_install.bat reports PHONE=NOT_CONNECTED each time); adb kill-server/start-server retry did not recover it. The phone is physically disconnected or USB debugging is off.
- Cannot run without the phone: install (INSTALL/KB_PUSH/KB_PUSH2), all foreground checks, airplane-mode-mid-call timings, all 7 PDF exports, both guided demo runs, M1 benchmark in both modes (-> docs/benchmark/final.md), and all screenshots to docs/screens/ incl. docs/screens/demo/. No results fabricated.
- Ready when the phone reconnects: latest debug APK built (Item 7, BUILD SUCCESSFUL); run build_install.bat, then work the Item 8 checklist top to bottom, then commit 'Gap round: verified on phone'.

## Note -- MCP source arrived mid-round
- _claude_work/sources/ now holds MCP_Card_2018.pdf and MCP_Card_Guidebook_MoHFW_MoWCD_2020.pdf (both 6117713 bytes, likely identical). This unblocks the Item 1 MCP follow-up (MCP-ANC rows, immunization/growth sections, MCP PDF relayout); left for a follow-up step, not smuggled into Items 4-8.
