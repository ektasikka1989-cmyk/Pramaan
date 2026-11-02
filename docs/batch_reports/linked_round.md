# Linked round — audit, results, report

## Results (2026-11-02)
- Commits (master): audit+exit-codes `e272c1f`, item 1 → `Linked round 1 …` … item 8 `Linked round 8: monthly report on ASHA Incentives 2025 rates`.
- Host gate: 36/36 suites green, 779 checks, 0 failures, exit 0. New suites: LinkedSave 20, VoiceStock 25, StockMap 19, ChangeSummary 15, Origin 16, Refill 11, Monthly 17.
- Contract updates in old suites (spec-mandated): StockRegisterTest 15→16 kit items (calcium added), FormSpecsTest FP missing 4→6 (ecp+ptk fields).
- Build/install: `BUILD SUCCESSFUL`, `GRADLE_EXIT=0`, `INSTALL=OK`, `KB_PUSH=OK`, `KB_PUSH2=OK` (build_install.bat).
- Phone foreground tests: BLOCKED — device disconnected from adb after install (`List of devices attached` empty, incl. after adb restart + 45 s wait). Nothing on-phone verified this round.
- 2025 rates verified against the guide DB (`incentives.pdf` chunks, NHSRC `ASHA-INCENTIVES 25.pdf`), not from memory.

## Not verified (needs the phone)
- ANC visit with IFA 30 + calcium 60 by Hindi voice note → form/MCP/stock/diary/summary.
- Give ORS 4 to a child from the Drug kit page → record/stock/diary.
- Voice receipt "received 100 ORS" → stock + history; Hindi number words over real STT.
- Low-stock Home card → refill WhatsApp/SMS text.
- Monthly report PDF export look; Today's plan Start-visit buttons; backup Back-up-now.
- Entry-history taps (stock/diary/MCP/form-cell origins) on device.

## Audit notes (5 lines per item)

## Item 1 — Give to person = linked save
- `StockGiveDialog` (`ArogyaStockUi.kt:175`) builds `StockTxn(kind="give")`; both callers only append it (`ArogyaRegistryUi.kt:767` person page, `:930` drug kit page) — no MCP/diary touch.
- Linked path exists: `ArogyaOfficialForms.apply` (`ArogyaFormSpecs.kt:334`) saves form + MCP card + drug kit + diary together.
- Keyed idempotency exists: `stockNote`/`stockKey` `[formId#col]` (`ArogyaFormSpecs.kt:324-331`); re-save replaces only that column's lines.
- `McpCard` has one medicine counter only: `ifaTablets` (`ArogyaRegistry.kt:34-35`); other items have nowhere cumulative on the card.
- Plan: new `applyGive()` in `ArogyaOfficialForms` reusing the same keyed pattern (`[give#<id>]`), updating stock + MCP (IFA total) + diary + record; both callers switch to it.

## Item 2 — Voice stock entry
- `ArogyaDictationDialog` (`ArogyaVisitScreen.kt:1296`, internal) already follows the Home switch via `cloudVoice=ArogyaCloud.transcribe` + `cloudFirst` flag; reused in `ArogyaAddPersonUi.kt:66`, `ArogyaRegistryUi.kt:531`.
- Parse via `ArogyaVisitAi.route()`; grounding helpers in `ArogyaAiExtras`; `scanMedicine` is the template for extract-then-confirm.
- Confirm-then-save dialogs already exist: `StockReceiveDialog`, `StockAdjustDialog`, `StockWriteoffDialog` (`ArogyaStockUi.kt:83-171`).
- Voice result must map to receive/write-off/correction txns with date/source/reason; confirmation list before saving is new.
- Plan: new `VoiceStockDialog` on the Drug kit page + `parseStockVoice()` host-tested parser with transcript grounding.

## Item 3 — All kit items linked to visits
- `STOCK` map (`ArogyaFormSpecs.kt:316`) has 7 items; official fields exist for ors/zinc (`SICK:106-107`), ifa (`MCP_ANC:86`), condoms/ocp/napkins (`FP:115-117`).
- `DRUG_KIT_DEFAULTS` (`ArogyaRegistry.kt:79-86`) has 14 kit items but NO calcium — item needs calcium added (phone test: IFA 30 + calcium 60).
- New official fields allowed only where the form has them (e.g. calcium beside ifa on MCP_ANC "Care during pregnancy"; paracetamol/RDT on SICK treatment).
- Items without an official row go in a new "Medicines given" visit section, stored in `FormEntry.values`, deducted by `apply()` via the extended map.
- `apply()` already deducts exactly once with keyed replace (`:340-351`); host tests must cover save + re-save for every mapped item.

## Item 4 — "What changed" summary
- `apply()` returns only `RegistryData` — no diff; diary note is just `"Form: …"` (`ArogyaFormSpecs.kt:369-370`).
- Save feedback today: generic save + `AROGYA_FORM_SAVED` marker; no old→new values shown anywhere.
- Same text must go into the diary entry, so the summary builder must run before the diary write inside each save path.
- Plan: new `ChangeSummary.describe(before, after, …)` pure function (form/MCP/stock/diary/next-visit old→new), shown in a dialog after visit/give/receive/add-person saves.
- Host test: summary lists exactly the records changed (and only those).

## Item 5 — Entry history
- `StockTxn` carries note/source/reason/batch/expiry but no origin/confirmation field (`ArogyaRegistry.kt:51-53`); `DiaryEntry` has note only (`:48-49`).
- `FormEntry.sources` map already records per-field origin; `FilledValue` comment documents `ai | rule | asha` (`ArogyaFormSpecs.kt:21-23`).
- `StockHistoryDialog` (`ArogyaStockUi.kt:217`) lists per-item entries — the tap target for stock origin.
- Plan: origin convention (`origin:visit|voice|scan|manual`, confirmed flag) encoded in existing fields, reader + tap-to-origin dialog for stock/MCP/form/diary.
- No model change needed if convention is strict; codec untouched.

## Item 6 — Low stock → refill request
- Status engine exists: `stockStatusText` OUT/LOW/EXPIRING/EXPIRED (`ArogyaRegistry.kt:116`), computed in `stockRegister` (`:129`); minimums in `DRUG_KIT_DEFAULTS`.
- Share path exists: `ArogyaShare.whatsApp` (`ArogyaShare.kt:99`) + `sms`, with ANM-number pattern in the escalate flow (`ArogyaVisitScreen.kt:1053-1054`).
- No Home card for low stock today; drug kit page shows per-row status only.
- Plan: Home card "Low stock: ORS 8, Zinc 0" from balances vs minimums + one-tap pre-filled refill message listing all low items.
- Host test: low-stock threshold rule (balance < min → listed).

## Item 7 — Today's plan + backup status on Home
- Today card exists: `ArogyaTodayCard` (`ArogyaRegistryUi.kt:266`) opens hub "due" tab; due list sorted overdue-first (`ArogyaRegistry.kt:380-386`).
- "Start visit" wiring exists: `onStartVisit` through hub (`ArogyaRegistryUi.kt:357`) to person page button (`:615`).
- Backup engine exists: `BackupTab` with `store.lastBackup` date display (`:1031`), `store.backupTo/backupUri/password/autoDaily`.
- Home (`ArogyaVisitScreen.kt:443`) shows Today card + grid; needs: per-visit rows with Start visit + backup status line with Back-up-now (needs store + password/location checks).
- Plan: extend Home with `TodayPlanCard` (due/overdue in order, Start visit each) and `BackupStatusRow`; reuse hub/visit launch paths.

## Item 8 — Monthly report + incentive claim
- `monthlyReport` (`ArogyaRegistry.kt:212`) + `incentiveEstimate` (`:266`, fixed rates 250/50/50/100/200/150/150) already built from linked records (visits/schedules/diary).
- Already exported via the doc renderer: `monthlyTable` → `FormTable` → viewer + `shareFormPdf` (`ArogyaRegistryUi.kt:958-963`), with SAMPLE watermark + sign flag.
- Gap: rates are not labelled as ASHA Incentives 2025 and the list may be incomplete (no ANC/immunization-session/PNC lines); verify against the 2025 list and rename.
- Diary kinds VHND/VHSNC_MEETING/PHC_MEETING already counted — new lines must come from records only, never AI.
- Plan: `INCENTIVE_2025` constant table + extend lines; host test: monthly totals equal the sum of the records.
