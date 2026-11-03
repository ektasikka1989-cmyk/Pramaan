# Final submission round — report

Rules for this round: APK only (`gradlew :app:assembleDebug`, left at
`app/build/outputs/apk/debug/`), no phone actions; one commit per item
(`Final N.x: <summary>`); assembleDebug + all host suites green after each
item. Anything phone-only goes on the manual checklist (D1), "to be checked
manually".

## PART A — Official MCP Card everywhere

### A1. MCP_ANC rebuild (official sections)
- Audit: old MCP_ANC was 16 hand-written rows (`ArogyaFormSpecs.kt:79`);
  schedule windows lived in `ArogyaSchedule` (28–34 for ANC3, citing Part B
  p.182); ANC tick loops `(1..4)` in UI/render/trend/card-text; tests pin
  column labels ("ANC 1"), nextDue dates, td/ifa/calcium ids.
- Changed (`Final A.1`): rebuilt MCP_ANC from `docs/forms/MCP_CARD_SPEC.md`
  §1/§3 (+§2 checkup rows, §4 danger wording): Pregnancy record,
  Obstetric A–J, Past history A–F, Examination, Antenatal visits
  (Date/POG/Weight/Pulse/BP/Pallor/Oedema/Jaundice/Complaints), Abdominal
  examination, Essential investigations (HIV Yes/No only), Blood Group & Rh,
  Optional investigations, Checkup (Td (TT on older cards), IFA count,
  calcium, albendazole), Danger signs (MCP p.6), Birth preparedness.
  Columns ANC 1–4 + ANC 5 (PMSMA); source line `MCP Card p.5 (MoHFW/MoWCD) ·
  Guidebook p.12`. Height < 145 cm → `obstetric risk (MCP guidebook p.13)`;
  fetal Reduced/Absent → danger; Td choices None/Td-1/Td-2/Booster
  (apply() accepts TT + Td prefixes); ANC windows corrected to
  ≤12/14–26/28–36/>36 (Part B p.197) in schedule + comments; ANC loops
  extended to 1..5; one-time fields added to CARRY; seedDemo updated.
- Files: `ArogyaFormSpecs.kt`, `ArogyaRegistry.kt` (schedule+comment+card
  text), `ArogyaDocRender.kt`, `ArogyaGrowth.kt`, `ArogyaRegistryUi.kt`.
- Tests: full host gate 41 suites green; BUILD SUCCESSFUL.
- Verified on host: spec rows/order/labels compile; nextDue/schedule/danger
  expectations hold; seeded village builds through the save path.
- To be checked manually: ANC visit flow on phone (5 columns, PMSMA),
  height flag, HIV Yes/No UI, ANC tick boxes 1–5.

### A2. Missing official sections (PNC/BABY/HBYC/IMM/IFA/FP)
- Audit: no MCP_PNC/BABY/HBYC/IMM/IFA specs; `FieldSpec` had only
  `onlyFromColumn` (no × cells); guided visit always routes via `formFor`;
  MCP dialog auto-lists any filled form from `ALL`.
- Changed (`Final A.2`): new specs from the card spec — MCP_PNC (delivery
  record + post partum care, MCP p.7), MCP_BABY (care of baby + <2.5 kg
  footnote, MCP p.7), MCP_HBYC (card's own HBYC table with × cells,
  MCP p.8), MCP_IMM (exact antigens per age column + Vitamin A 1–9 +
  SIA/Other + missed-dose tracking + next date, MCP p.36–37), MCP_IFA
  (bottles + week ticks + albendazole, MCP p.27), FP timing table data
  (Guidebook p.60) with a reference view on FP visits. Engine:
  `onlyColumns` + × rendering in `table()`, PNC/BABY nextDue from
  delivery/birth dates, PNC/BABY danger branches (p.8 wording, no invented
  thresholds), visit kinds, CARRY for one-time rows. Recording: guided
  visit takes an explicit `formId`, MCP dialog has Record buttons for
  PNC/BABY/IMM/HBYC/IFA threaded through hub → VisitScreen.
  Mismatches vs handbook HBYC recorded in `docs/forms_verification.md`
  §2b. Schedule logic (dues) untouched.
- Files: `ArogyaFormSpecs.kt`, `ArogyaGuidedUi.kt`,
  `ArogyaRegistryUi.kt`, `ArogyaVisitScreen.kt`,
  `docs/forms_verification.md`.
- Tests: full host gate 41 suites green; BUILD SUCCESSFUL.
- Verified on host: specs compile; × cells render; nextDue/danger rules;
  guided override resolves spec + first empty column.
- To be checked manually: Record buttons → guided flow → save for each
  new section; FP timing table on an FP visit; × cells in the form viewer.

### A3. MCP card document page by page
- Audit: `mcp.html` was a single flow (band + boxes + ANC + schedule-based
  immunization + weights + growth); `mcpDoc` read only `Beneficiary.mcp`;
  all phone callers go through `ArogyaRegistryStore.mcpDoc`; fixed footer
  cannot vary per page, so per-page provenance goes in section lines.
- Changed (`Final A.3`): template laid out page by page with breaks —
  p.3 Identification, p.4 Checkup, p.5 ANC, p.6 Danger signs/Delivery prep
  (exact card wording), p.7 PNC, p.8 Newborn + HBYC, p.27 IFA, pp.28–31
  Growth chart, pp.36–37 Immunization. Pastel headers (pink ANC, yellow
  PNC, purple immunization); per-page `As per MCP Card (MoHFW/MoWCD) p.N`
  lines; title `Mother and Child Protection Card (digital record)`; no
  logos. Section tables built from form entries with card-tick fallbacks
  (`mcpDoc` takes `forms`, store passes them); immunization merges entry
  dates with the card's given-dates. Growth SVG restyled: dark green /
  yellow / orange bands, pink border for girls, blue for boys.
- Files: `doc_templates/mcp.html`, `doc_templates/doc.css`,
  `ArogyaDocRender.kt`, `ArogyaRegistryStore.kt`, `ArogyaGrowth.kt`,
  `DocRenderTest.kt`, `GrowthTest.kt`.
- Tests: full host gate 41 suites green (DocRenderTest 38, GrowthTest 28);
  BUILD SUCCESSFUL.
- Verified on host: page order, pastel classes, source lines, fallbacks
  (ANC ticks, BCG date), no unreplaced placeholders, portrait.
- To be checked manually: MCP PDF on phone for Neha + baby (all pages,
  page breaks, pastel print colors, growth chart, SAMPLE watermark).

### A4. Demo data + card reading
- Audit: `seedDemo` fills ANNEX9/HBYC/ANC/SICK/FP; Baby of Neha is 6 days
  old, home birth, day-1 + day-3 visits done; `cardPrompt` was generic.
- Changed (`Final A.4`): Neha's household now shows filled MCP_ANC (4
  columns at 12/22/32/38 weeks), MCP_PNC (day 1 + 3, Home–SBA), MCP_BABY
  (day 1 + 3, weight from day 3 to avoid duplicating birth weight) and
  MCP_IMM (birth antigens at the day-3 visit, card vaccines updated
  through the linked save). `cardPrompt` names the p.36 immunization
  layout with the exact antigen names. Test updates (official doc
  required): 8 seeded form types, 22 patients, diary invariant is now
  one line per person-day (same-day re-saves share a line).
- Files: `ArogyaFormSpecs.kt`, `ArogyaAiExtras.kt`, `FormSpecsTest.kt`.
- Tests: full host gate 41 suites green; BUILD SUCCESSFUL.
- Verified on host: seeded counts; baby's card carries BCG/OPV0/HEPB0;
  ORS/IFA balances unchanged; prompt contains the antigen list.
- To be checked manually: Neha + baby filled forms on phone; MCP card
  photo read of an immunization page.

### A5. Verification doc + FormsVerifyTest
- Audit: docs/forms_verification.md section 5 listed MCP rows as blocked; FormsVerifyTest had no MCP label/order checks.
- Changed (Final A.5): section 5 rewritten as verified items 5a-5f against the MCP Card Guidebook (MoHFW/MoWCD 2020) with page numbers; FP timing note corrected.
- Files: docs/forms_verification.md, tools/host-tests/FormsVerifyTest.
- Tests: FormsVerifyTest 48/48 PASS; full host gate 41 suites green (gatea5.log); BUILD SUCCESSFUL.
- Verified on host: official MCP row labels/order asserted; FP table source corrected.
- To be checked manually: MCP card PDF look vs printed card (phone).

## PART B -- Answer quality: nothing compressed

### B1. Truncation audit table
Audit method: searched Arogya*.kt, PocketRag*.kt, GenericKb*.kt, KnowledgeSourcePresentation.kt and the runtime answer paths for token ceilings, groundedSentences(max), .take(N), maxLines, "at most N / N sentences / short", salvage lengths, message caps. All findings below confirmed in code.

| file:line | limit | value | keep/change | reason |
|---|---|---|---|---|
| ArogyaQuestionType.kt:66 ceilingPerPart | output ceiling per part | FACT/URGENT 400, PROCEDURE/LIST 560; same for Hindi | change (B2/B3) | too small for 6-10 sentence answers; Hindi needs x2.5 |
| ArogyaQuestionType.kt:76 lengthLine | prompt length line | "two to five sentences (three to five for a fact)" | change (B3) | contradicts complete-answer goal; needs B3 structure |
| PocketRagEngine.kt MAX_ANSWER_TOKENS_PER_PART | per-part fallback cap | 400 | change (B2) | route through ArogyaBudget |
| PocketRagEngine.kt EXTRACTIVE_CHARS | extractive fallback length | 700 chars | change (B3) | can leave 1-2 sentences; fuller fallback |
| PocketRagEngine.kt tryStatementSalvage | salvage on reject | trims to supported sentences | change (B3/B8) | show salvaged + "From the guide (exact words)" if < 4 |
| ArogyaAiExtras.kt:218 familyPrompt | prompt instruction | "3 short sentences" | change (B4) | spec wants 5-8 sentences |
| ArogyaAiExtras.kt:221-222 familyPrompt | input truncation | quote.take(600), answer.take(1500) | change (B4) | pass full answer + quotes |
| ArogyaAiExtras.kt groundedSentences (family) | keeper cap | max=4 | change (B4) | raise to max=8 |
| ArogyaVisitAi.kt:488 family_explain route | generation tokens | 220 | change (B2) | budget base 450 phone / 900 cloud |
| ArogyaAiExtras.kt:230 anmPrompt | prompt instruction | "At most 6 short lines", max 8 | change (B5) | template + polish instead |
| ArogyaAiExtras.kt:233 anmPrompt | input truncation | facts.take(2000) | change (B5) | template carries all facts |
| ArogyaVisitAi.kt:504 anm_message route | generation tokens | 200 | change (B2) | budget base 450 / 900 |
| ArogyaShare.kt:61-62 anmMessage | evidence truncation | highlights.take(3) x .take(400) | change (B5) | template lists every value |
| ArogyaShare.kt:74,76,77 anmMessage | field truncation | concern.take(200), observed.take(300), action.take(200) | change (B5) | remove all truncation |
| ArogyaVisitScreen.kt:1078,1081 escalate dialog | editor truncation | message/notes.take(1500), actions.take(500) | change (B5) | full-screen editor, no caps |
| ArogyaVisitAi.kt:548 card_read route | generation tokens | 380, OCR text.take(3500) | change (B2/B6) | budget base 900/1500; split+merge |
| ArogyaVisitAi.kt:575 person_extract route | generation tokens | 300 | change (B2/B6) | budget base 900/1500 per page |
| ArogyaVisitAi.kt:590 medicine_scan route | generation tokens | 120 | change (B2/B6) | budget base 400/900 |
| ArogyaVisitAi.kt:616 voice_stock route | generation tokens | 200 | change (B2/B6) | budget base 400/900 |
| ArogyaVisitAi.kt:459 organise_notes route | generation tokens | 420 | change (B2) | budget base 700/1500 |
| ArogyaVisitAi.kt:637 finish_visit route | generation tokens | 600 | change (B2) | budget base 900/1500 |
| ArogyaVisitAi.kt:525 daily_practice route | generation tokens | 200 | keep | quiz parse needs one short item; unchanged scope |
| ArogyaSopChat.kt:120 history card | display lines | maxLines = 3 | change (B7) | 6 lines + "Show more" |
| GroqChatProvider.kt cloud calls | cloud max_tokens | 120/192/224/260/512/768, coerceIn(.,1280) | change (B2) | Ask SOP cloud path uses budget bases (1200-2400) |
| PocketRagEngine.kt CLOUD_TOTAL_MS | cloud hard cap | 60 s | change (B8) | 120 s hard cap + 15 s idle limit |
| PocketRagEngine.kt CLOUD_FIRST_TOKEN_MS | cloud first-token wait | 20 s | keep | matches B8 20 s target |
- Changed (Final B.1): audit only; no code changed.
- Files: docs/batch_reports/final_submission.md.
- Tests: code unchanged from Final A.5 gate (41 suites green); no re-run needed.
- Verified on host: every table row confirmed in code 2-3 Nov.

### B2. ArogyaBudget helper + wired routes
- Correction to A5: the A5 gate (gatea5.log) did NOT finish green -- it stopped at CountDotsTest with an "unresolved reference ArogyaBudget" compile error because the B2 edit to ArogyaQuestionType.kt landed mid-run. Every A5 suite before that point passed (0 failures). The clean B2 full gate below re-covers all A5 files (unchanged since) plus B2, and is the verified record for both.
- Changed (Final B.2): new ArogyaBudget.kt -- per-feature phone/cloud bases (Ask SOP FACT 600/1200, PROCEDURE/LIST/URGENT 800/1600, detail 1000/2400, check-online 1600 cloud; family 450/900; ANM 450/900; card/person 900/1500; voice-stock/medicine 400/900; notes 700/1500; finish-visit 900/1500), language multipliers x2.5 Hindi/Devanagari, x1.3 Hinglish, x1 English, context-window cap, AROGYA_BUDGET log on every call. route() takes cloudTokens + lang and budgets each engine; all 8 route sites updated (daily_practice keeps 200). ceilingPerPart routes through the budget (new values 600/800; Batch3Test updated). PocketRagEngine.outputBudget takes cloud flag (cloud context 32000).
- Decision: medicine_scan uses the voice-stock base (400/900); it is the same extraction shape and the spec lists no base for it.
- Files: ArogyaBudget.kt (new), ArogyaQuestionType.kt, PocketRagEngine.kt, ArogyaVisitAi.kt, BudgetTest.kt (new), Batch3Test.kt, run.ps1, run.sh, run_some.ps1.
- Tests: full host gate 42 suites green, EXIT=0 (gateb2.log); BudgetTest 30/30; BUILD SUCCESSFUL (3m51s).
- Verified on host: multipliers, caps, all bases, lang detection, ceiling parity.
- To be checked manually: AROGYA_BUDGET lines on phone; Hindi answers visibly fuller.

### B3. Ask SOP answers -- complete in both languages
- Changed (Final B.3): lengthLine rewritten to the 5-part shape (Answer 2-3 / Why it matters 1-2 / What to do / When to tell the ANM / Common mistakes to avoid), Hindi labels (उत्तर / क्यों ज़रूरी / क्या करें / ANM को कब बताएँ / आम गलतियाँ) when the query is Devanagari, totals six to ten (phone) / eight to fourteen (cloud, new cloud flag set by PocketRagCloudResolver); PROCEDURE adds what-to-watch-for, LIST one-line explanations, URGENT steps + referral after the action. LENGTH tiers + COMPLETE_ANSWER rewritten to match (no more "three to five sentences / 80 words"). detailLine gains the mistakes part + Hindi labels. Card renderer (ArogyaAnswerFormat) parses the 2 new labels EN+HI. Salvage: < 4 sentences surviving appends "From the guide (exact words)" (गाइड के शब्दों में) unless overlap >= 0.7; EXTRACTIVE_CHARS 700 -> 1400, up to 10 facts. Check-online evidence up to 16 passages (CHECK_PASSAGES; cloud-first wide stays 12 x 1600). More detail reuses the cached packet (no new search).
- Decision: More detail keeps the cached packet passages (spec B3 says no new search); the 16-passage widening applies to Check online (contextForOnline).
- Files: ArogyaQuestionType.kt, PocketRagPromptBuilder.kt, PocketRagCloudResolver.kt, PocketRagEngine.kt, ArogyaAnswerFormat.kt, GenericKnowledgeContextProvider.kt, AnswerFormatTest.kt, Batch2Test.kt.
- Tests: full host gate 42 suites green, EXIT=0 (gateb3.log); AnswerFormatTest 26/26 (new: cloud totals, Hindi labels, new sections); BUILD SUCCESSFUL (5m37s).
- Verified on host: prompt lines per type/lang/engine, section parsing EN+HI, sentence counting.
- To be checked manually: answer completeness EN+HI on phone; salvage exact-words block; check-online depth.

### B4. Explain to family (5-8 sentences)
- Changed (Final B.4): familyPrompt rewritten -- 5 to 8 short warm sentences spoken to "you" covering what to do / what not to do / warning signs / when to call the ASHA-ANM or go to the facility, simple spoken Hindi in Devanagari when asked; full answer + quote passed (quote.take(600) / answer.take(1500) removed). groundedSentences max 4 -> 8 on the family path. Card: 20 sp text (29 sp lines), "Read to the family" as the main full-width button, new "Share with family (WhatsApp)" via the share sheet.
- Files: ArogyaAiExtras.kt, ArogyaVisitAi.kt, ArogyaSopChat.kt, AnswerFormatTest.kt.
- Tests: full host gate 42 suites green, EXIT=0 (gateb4.log); AnswerFormatTest 31/31 (new: prompt ask, Hindi, full-input, max-8 keeper, new-number rejection); BUILD SUCCESSFUL (2m31s).
- Verified on host: prompt wording, keeper keeps 8 and drops new numbers.
- To be checked manually: family card text size/buttons/share on phone; Hindi explanation quality.

### B5. Message / escalation to the ANM -- complete and structured
- Changed (Final B.5): new ArogyaAnmTemplate.kt -- fixed EN/HI template from the records (header To/From/Date, PERSON with descriptor, Household/Mother-Husband/Family phone, WHY with fixed-rule sign names + concern, SEEN TODAY with every recorded value + weight change via the fixed weightNote rule, HISTORY with last visit/birth weight/LBW/preterm/SNCU/ANC/Td/IFA/vaccines given + due-overdue, WHAT I HAVE DONE with given meds + referral note, GUIDELINE quote + document/page, WHAT I NEED, Next visit). Lines omitted only when empty, never n/a, nothing truncated. anmPrompt is now polish-only; new polishAnmMessage verifies every number + danger sign survives (cloud sees [Pn] identifier tokens, restored after; scrub kept as a second net) and shows the template + reason when not. Escalate dialog is a full-screen editor with EN/HI toggle (rebuilds template), WhatsApp/SMS/Call ANM/Call 108/Copy, keeping "ANM contacted by ..." records. Same header powers the refill request (balance + minimum lines) and the visit handover share. Shared answer sources no longer cut at 3 x 400 chars.
- Files: ArogyaAnmTemplate.kt (new), ArogyaAiExtras.kt, ArogyaVisitAi.kt, ArogyaShare.kt, ArogyaVisitScreen.kt, ArogyaRegistryUi.kt, AnmTemplateTest.kt (new), run.ps1, run.sh, run_some.ps1.
- Tests: full host gate 43 suites green, EXIT=0 (gateb5.log); AnmTemplateTest 22/22 (sections EN/HI, omit-empty, verify pass/fail, tokenize round-trip, refill, polish prompt); BUILD SUCCESSFUL (3m29s).
- Verified on host: template content, verification logic, token privacy round-trip.
- To be checked manually: full-screen editor on phone; EN/HI toggle; polish accept/reject; WhatsApp/SMS/Copy sends.
