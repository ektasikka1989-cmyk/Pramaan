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

### B6. Extraction never truncated
- Changed (Final B.6): new ArogyaExtractSplit (cut JSON / cut last-line detection, halves, register-page block splitter, card/person/voice/med merges). parseCard caps 12 -> 40 (vaccines + weights). Input takes removed (card 3500, person prompt 4000, med prompt 1200). Second-chance split-read with AROGYA_EXTRACT_SPLIT in readCard / extractPersonFields / scanMedicine / voiceStock. New extractPersonList (per-block grounded extract, max 10); photo add-person flow uses it when the page holds several people, ending on the same shared list review. AI catalog text corrected to the B4/B5 behavior (5-8 sentences; template + polish).
- Files: ArogyaExtractSplit.kt (new), ArogyaAiExtras.kt, ArogyaAddPerson.kt, ArogyaVisitAi.kt, ArogyaAddPersonUi.kt, ExtractSplitTest.kt (new), run.ps1, run.sh, run_some.ps1.
- Tests: full host gate 44 suites green, EXIT=0 (gateb6.log); ExtractSplitTest 18/18 (cut detection, halves, 8-person page -> 8 grounded names, full 28-antigen immunization page -> 28 rows, Hindi 4-item receipt -> 4 lines, merges, full prompts); BUILD SUCCESSFUL (1m6s after fixing MedScan orEmpty).
- Verified on host: split/merge logic, full-page extraction, Hindi voice rules.
- To be checked manually: split-read on a real long card photo; multi-person photo review; Hindi voice receipt end to end.

### B7. Readable layout
- Changed (Final B.7): SopAnswerText rewritten -- flowing paragraphs (17 sp, 25 sp line height = 1.45), numbered steps/bullets keep their own lines, inline superscript citations (tappable annotated string with baseline-shift spans, multi-digit safe) replacing the [n] buttons; 12 dp between sections. History collapsed view 3 -> 6 lines with explicit Show more / Show less; card padding 12 -> 16 dp; latest card gets 16 dp padding. Input box already sits below the weighted message list (fixed at bottom) -- confirmed, no change. Pure helpers supDigits/needsNewLine in ArogyaAnswerFormat.
- Files: ArogyaSopChat.kt, ArogyaAnswerFormat.kt, ArogyaVisitScreen.kt, AnswerFormatTest.kt.
- Tests: full host gate 44 suites green, EXIT=0 (gateb7.log); AnswerFormatTest 37/37 (new: superscript mapping, line-break rule); BUILD SUCCESSFUL (1m16s).
- Verified on host: superscript mapping, break rule, section parsing.
- To be checked manually: layout on phone (both languages, history expand, citation taps, fixed input).

### B8. Stream word by word into the real card
- Changed (Final B.8): new SopStreamingCard -- the draft streams into the real card (sections render as they arrive via SopAnswerText; citations appear with the checked answer), chip "Writing... / checking against the guide" (EN/HI), rejected draft struck through with "Rewriting from the guide..." (existing abort flow), "Read so far" early speak, Cancel kept. Before first words the spinner card stays. Auto-scroll pauses while the user has scrolled up. route() gains onDelta: phone via new LocalModelManager.generateDetachedStream (minimal Pramaan addition on generateEphemeral partials), cloud via new OpenAiCompatibleClient.completeStream (SSE, reusing SseStreamParser, blocking fallback); "" resets on engine switch. Family explanation and ANM polish stream with progress (polish detokenized live). Cloud limits: 120 s hard cap (was 60 s), new 15 s idle watcher (streaming turns only, clears the partial draft), 20 s first token kept; net-loss instant fallback already wired (ArogyaNetWatch -> cancelCloudCall).
- Limitation (honest): per-sentence live verdicts (fade/strike single sentences) are not implemented -- the checker judges whole drafts, so rejection shows at draft level with rewrite. B8 adds no new host suite: SseStreamParser is covered by SpeedTest, the draft reduce by ReviewTest (both green).
- Files: ArogyaSopChat.kt, ArogyaVisitScreen.kt, ArogyaVisitAi.kt, ArogyaModelClient.kt, LocalModelManager.kt, PocketRagEngine.kt, PocketRagCloudResolver.kt.
- Tests: full host gate 44 suites green, EXIT=0 (gateb8.log); BUILD SUCCESSFUL (2m23s).
- Verified on host: suites green; streaming paths compile.
- To be checked manually: live streaming on phone (offline + cloud + check-online + more-detail + family + polish); idle fallback; net-loss fallback; autoscroll pause.

## PART C -- The 100 official Q&A guide the AI (never canned)

### C1. Semantic match
- Changed (Final C.1): new ArogyaVerifiedAnswers.kt -- parses verified_answers.json (100 items, chunk_id sources); score = 0.7 x cosine (best of EN/HI) + 0.3 x keyword overlap with the Hinglish map (naal->cord, doodh->milk, tika->vaccine, bukhar->fever, dast->diarrhoea, garbhvati->pregnant, saans->breathing, peeliya->jaundice); strong >= 0.80, medium 0.65-0.80; embedding cache filesDir/verified_q_emb.bin (fingerprint-guarded, rebuild on asset change); keyword-only until vectors exist; query-vector reuse (passed-in, then last-query cache, then one embed); AROGYA_GOLD match log. warmUp takes the app context and kicks the background build with the KBs own semanticEngine.embed("query: ...") (384-dim); lazy retry on first match if the engine was not ready. No lengths changed.
- Files: ArogyaVerifiedAnswers.kt (new), ArogyaVisitAi.kt, ArogyaVisitScreen.kt, GoldMatchTest.kt (new), run.ps1, run.sh, run_some.ps1.
- Tests: full host gate 45 suites green, EXIT=0 (gatec1.log); GoldMatchTest 21/21 (parse, real-asset 100 + VA-001 fields, tokens, Hinglish, overlap, cosine, blend, codec round-trip/tamper/corrupt, keyword + semantic match incl. background warm); BUILD SUCCESSFUL (2m28s).
- Verified on host: matching math, cache codec, real-asset load.
- To be checked manually: cache build on phone; AROGYA_GOLD lines; semantic (non-keyword) matches.

### C2. Gold evidence feed (pin + reference block)
- Changed (Final C.2): the verified-answer match now steers live evidence in GenericKnowledgeContextProvider.phase14Attachment (local + cloud, before answering): one embedForGold ("query: ..." prefix, same KB call) + match; strong (>= 0.80) or medium (>= 0.65) pins the item's passages at the top of the packet as normal S1.. Source-ID blocks (same format, so citations/quote/assembler/checker all treat them as guide passages). New repository.goldPassages resolves each source: chunk_id first, then row id, then source_name + page_number + quote substring (min 24 chars); missing ids are skipped with AROGYA_GOLD skip, never invented; titles come from knowledge_sources with the JSON title as fallback. Pinned chunk ids are de-duplicated out of the normal and wide-extra blocks. Key-facts branch packs normals into a temp list, then ArogyaGoldPin.trimNormal drops trailing normals so gold + normal fit today's normal total (no gold = byte-identical packet); the sentence branch keeps its budget check with gold consuming first. Strong matches add a kb_gold_reference block (new PocketRagPacket.goldReference via the assembler, rendered in the prompt) with the exact "Reference notes ... Do not mention these notes" wording; the checker never sees it (not in evidenceBlocks), so every sentence must still match a real guide passage. Conflict S-ids follow the pinned layout and trimmed passages are filtered out (no dangling cites); wide-extra S-ids continue from packed.size. AROGYA_GOLD pin log carries tokens_before/after. No Part B length changed. Pure helpers in new ArogyaGoldPin.kt (referenceBlock, blockText, sourceRef, trimNormal, estimateTokens).
- Files: ArogyaGoldPin.kt (new), GenericKnowledgeContextProvider.kt, GenericKnowledgePackRepository.kt, PocketRagModels.kt, PocketRagEvidenceAssembler.kt, PocketRagPromptBuilder.kt, GoldPinTest.kt (new), run.ps1, run.sh, run_some.ps1.
- Tests (speed mode: affected suites only; full gate once in Part E): GoldPinTest 14/14 (ref wording, block/ref formats, trim keep/drop/keep-one/empty, token estimate, pin order); GoldMatchTest 21/21 still green; BUILD SUCCESSFUL (3m53s).
- Verified on host: pin/trim/reference pure logic; no-match path keeps the packet identical by construction (empty gold -> same code path as before).
- To be checked manually: pinned passages on phone (AROGYA_GOLD pin lines, tokens_before/after); chunk_id resolution against v2 DB; skip lines on older DB; medium vs strong behavior; citation taps on pinned blocks.
- Issue (not rewritten, listed): gold ids resolve via the row-id step, never the chunk_id column (benchmark: 293/293 via row id) — one extra indexed miss per source, negligible; resolver order left as is.

### C3. Stored-copy fallback (model failure only)
- Changed (Final C.2+C.3): askPramaan failure paths (timeout; local-model failure with no cloud retry; retry-path failure) try a stored copy before throwing: ArogyaVerifiedAnswers.match on the same query (reuses C2's cached vector, no new embed), ArogyaGoldPin.storedCopyIfStrong returns text + guide sources for strong matches only, else null and the original error/cancel path is unchanged. Cancels and setup check() failures never trigger it. The stored text leads with "From official guidelines (stored copy) — AI unavailable." (HI: "आधिकारिक दिशानिर्देशों से (संग्रहीत प्रति) — AI उपलब्ध नहीं।"), then the verified answer (Hindi answer when asked in Hindi — new VerifiedItem.aHi parsed from the asset, English fallback). Sources carry title + "page N" + quote + url as guide sources; quote/quoteSource set for the card; provider reads "ArogyaDoot • stored copy", tier 1, new ArogyaAiResult.storedCopy flag. Card hides Explain-to-family and More detail for stored copies (both need the failed model); Check online, Read aloud and Share stay. AROGYA_GOLD fallback log carries id/score/reason.
- Files: ArogyaGoldPin.kt, ArogyaVerifiedAnswers.kt (aHi), ArogyaVisitAi.kt, ArogyaSopChat.kt, GoldPinTest.kt.
- Tests (speed mode): GoldPinTest 19/19 (new: aHi parsed, EN + HI stored text, source pages, medium→null); GoldMatchTest 21/21; BUILD SUCCESSFUL (11m39s, c3build.log).
- Verified on host: strong-only gate, both labels, source mapping.
- To be checked manually: stored copy on a real model failure/timeout (hard to force; airplane-mid-cloud-call in Cloud first exercises the retry path, not this one).

## PART D — Proof: manual phone checklist, laptop benchmark, demo

### D1. Manual phone test checklist
- Changed (Final D.1): new docs/manual_phone_test.md — numbered A–G checklist for the user on the phone (no phone runs by the agent): A. 3 core questions × offline/cloud × EN/HI (cord stump, ORS, newborn danger signs) + More detail; B. 3 unlike-worded live-proof questions × offline/cloud; C. Explain-to-family EN/HI + ANM escalation incl. Hindi + WhatsApp; D. MCP card views/PDF/photo reads/register photo/Hindi voice receipt; E. airplane-mode answers + guided demo EN/HI; F. printed expected results (Hindi completeness, ≥6 sentences, 5–8 family, full ANM template, nothing cut, cloud fuller, ≤3 s cloud / ≤15 s offline to first word); G. log-reading guide (AROGYA_BUDGET, AROGYA_GOLD, AROGYA_EXTRACT_SPLIT, AROGYA_ANSWER_LEN) for sending app_log.txt back. Every step has Pass/Fail boxes + time columns.
- Files: docs/manual_phone_test.md (new).
- Tests: docs only — no code touched; tree covered by the C2+C3 build + suites.
- Verified on host: checklist content matches Parts A–C acceptance lines.
- To be checked manually: everything in it — that is its purpose.

### D2. 100-question benchmark
- Changed (Final D.2): new tools/gold100_eval.py (laptop only, stdlib urllib, key from META_API_KEY env, never stored/printed): integrity of all 293 gold sources against v2 (resolution path mirror of goldPassages, page match, quote-in-chunk); keyword-path eval replicating ArogyaVerifiedAnswers.keywordOverlap exactly (unicode tokens, Hinglish map, |q∩pool|/|q|); BASELINE arm = plain Python keyword top-5 (labeled NOT the phone E5 pipeline); GOLD-ON arm = pinned passages (+ reference notes when strong); cloud generation via Muse Spark for 100 EN + 20 HI × both arms when a key exists, scored on fact coverage (numbers + key terms), citation page ∈ item pages, latency; failures recorded, no reruns. Writes docs/benchmark/gold100.md + final.md.
- Results (real, 2026-11-03): integrity 293/293 resolved (0 via chunk_id col, 293 via row id, 0 missing), pages 293/293, quotes 293/293. Keyword self-match: EN top-1 100/100, HI 99/100 (strong 100/100 both; one HI tie lost on id order). BASELINE keyword top-5 hits the right page 46/100; GOLD-ON pins it 100/100 once resolved. Cloud generation NOT MEASURED — no key in the environment (checked META_API_KEY, META_KEY, GROQ_API_KEY, TOGETHER_API_KEY, META_MODEL_API_KEY, all absent); the script is ready to rerun with a key. Phone E5 off-vs-on + offline timings stay "to be measured manually" (checklist A/B + AROGYA_GOLD logs).
- Files: tools/gold100_eval.py (new), docs/benchmark/gold100.md (new), docs/benchmark/final.md (new).
- Tests: script run EXIT=0; outputs contain measured numbers only.
- To be checked manually: phone retrieval off-vs-on; offline answer quality/timings; cloud numbers if a key is provided later (rerun the script).

### D3. Demo scenes (MCP card + gold layer)
- Changed (Final D.3): 16 → 18 scenes. New "gold" scene after Ask SOP (live EXPLAIN run of GOLD_EN "baby ko pehla gaadha doodh dena chahiye?" / GOLD_HI Devanagari paraphrase — designed for VA-005, Integrated ASHA Module Part B p.19 — shown in the live DemoSopAnswer layout with tap-to-open citations, plus an offline-voice Read-aloud button; records a "gold-layer answer" tick with its own benefit line). New "mcpcard" scene after the card-photo scene (Neha's official MCP card via the existing store.mcpDoc viewer + share/print, auto-opened by the scene's live step; no AI tick — it shows fixed content). Script: both scenes 4–5 sentences EN+HI with matching counts, hero kept (Neha), 100-questions narration as specified. Renumbered live runner (saved 11→13, practice 12→14) and bodies (card 9→10, medscan 10→12, saved 11→13, practice 12→14, setting 13→15, aitable 14→16, beforeafter stays the else); ruleScene (1,3,5) untouched; progress/end auto from scenes.size.
- Files: ArogyaDemoScript.kt, ArogyaDemoTour.kt, DemoTourTest.kt.
- Tests (speed mode): DemoTourTest 33/33 (new: 18 ids in order, gold constants, MCP title + hero, gold benefit row); BUILD SUCCESSFUL (5m45s, d3build.log).
- Verified on host: order, narration counts/blank rules, tick benefit, nav bounds.
- To be checked manually: both scenes on phone (checklist E3/E4) — gold answer cites Part B p.19 via the live pin (Hinglish match is semantic; falls back honestly to whatever the live answer cites); MCP viewer content; TTS read-aloud; manual Next through 18 scenes.

## PART E — Submission build (freeze)

### E. Gate, build, commit
- Changed (Final E): committed Muse's uncommitted demo-script fix from 22:20. The D3 gold scene's 3rd narration line was a question ending in "?" (EN) and the HI line also ended in "?", which broke Batch5's rule that every EN narration line ends in "." and every HI line in "।". Both were reworded as statements ("Sunita asks in her own words about the baby's first thick milk." / "सुनीता अपने शब्दों में बच्चे के पहले गाढ़े दूध के बारे में पूछती हैं।"). The live GOLD_EN/GOLD_HI question the scene runs is unchanged. Batch5Test scene count 16 → 18 to match D3. Also committed Muse's gap_round.md Item 8 note (phone was not connected then).
- Full host gate: 46/46 suites, 1030 checks, 0 failures. run.ps1 was stopped by the host for low memory after 21 suites (FormSpecsTest → MedScanTest, all green). The remaining 25 suites (DemoTourTest → GoldPinTest) were run on the same tree with run_some.ps1 in two batches, all green. Per suite: FormSpecs 64, FinishVisit 34, Registry 28, Apply 22, Forms 13, Sample 12, Flow 12, Cloud 14, Guided 4, Save 19, Speed 18, Batch2 49, Batch3 6, Batch4 15, Batch5 21, Batch6 3, Review 28, RegisterTables 26, StockRegister 22, GuideLibrary 21, MedScan 24, DemoTour 33, AiTable 33, CountDots 14, Practice 11, AddPerson 21, AnswerFormat 37, Tier 41, DocRender 38, LinkedSave 20, VoiceStock 25, StockMap 19, ChangeSummary 15, Origin 16, Refill 11, Monthly 17, FormsVerify 48, Scrub 9, SetupMatch 11, Growth 28, DayCheck 18, Budget 30, AnmTemplate 22, ExtractSplit 18, GoldMatch 21, GoldPin 19.
- Build: gradlew :app:assembleDebug BUILD SUCCESSFUL (JAVA_HOME = Android Studio JBR).
- APK: app/build/outputs/apk/debug/app-debug.apk, 804,299,278 bytes (~767 MiB; the models and guide DB are still inside — the slimming is step 5).
- Install (user runs it; the agent never touches the phone): adb install -r "C:\Pramaan - Copy\app\build\outputs\apk\debug\app-debug.apk"
- Verified on host: all 46 suites; the debug build compiles and packages.
- To be checked manually (docs/manual_phone_test.md): everything A–G, especially A/B answers offline + cloud in EN/HI and time to first word; C Explain-to-family + ANM escalation + WhatsApp; D MCP card views/PDF/photo reads; E airplane mode + guided demo EN/HI through all 18 scenes (E3 gold scene cites Part B p.19, E4 MCP card scene); C3 stored-copy fallback on a real model failure; G send app_log.txt. Cloud gold100 numbers are still not measured (no key).

## PART F — Post-freeze fixes

### F1. Gold-match false-positive fix (ArogyaVerifiedAnswers)
- Changed (Final F1):
  - Stop-words (EN, Hinglish, Hindi) and 1-letter tokens are dropped from the query before keyword overlap (new contentTokens/STOP_WORDS). Overlap = shared content words / query content words.
  - A keyword-only match (no vectors yet) needs at least 2 shared content words (MIN_SHARED_KW), otherwise it scores 0.
  - Cosine is rescaled: cosAdj = clamp((cos − 0.75)/0.20, 0, 1); score = 0.7·cosAdj + 0.3·kw.
  - Pin (strong/medium) only if top1 − top2 ≥ 0.03 (MARGIN, new classify()). Scored carries the runner-up score, and the AROGYA_GOLD match log now prints second= and pin=. The C3 stored-copy fallback uses the same strong flag, so it is gated by the margin too.
  - The tokenizer now keeps combining marks (\p{M}). Before this, Devanagari words were split at every matra/virama, so Hindi stop-words could never match. This also changes Hindi keyword overlap in general.
  - STRONG 0.80 / MEDIUM 0.65 are unchanged (not retuned for the rescaled cosine).
- Files: ArogyaVerifiedAnswers.kt, GoldMatchTest.kt.
- Tests (speed mode): GoldMatchTest 45/45 (was 21; new checks: EN/Hinglish/Hindi stop-words, Hindi tokens, 1-word vs 2-word keyword rule, cosAdj points, unrelated-vector score < MEDIUM, margin tie/strong/medium/none, the 10 unrelated questions — tea, cricket, capital of India, battery, rice, train ticket, weather, bank account, movie, wifi — none pin, self-pin floors). GoldPinTest 19/19. BUILD SUCCESSFUL (1m58s). APK rebuilt: app/build/outputs/apk/debug/app-debug.apk, 804,299,278 bytes.
- Measured on host (keyword-only mode, no vectors): the verified questions pin themselves EN 92/100, HI 96/100. Of the 8 EN misses, 3 have one content word (VA-073 SAANS, VA-085 deworming, VA-091 PPIUCD → blocked by the 2-word rule) and 5 tie at kw 1.0 with a sibling item (VA-016, VA-027, VA-048, VA-053, VA-062 → blocked by the margin). Expected by design; on the phone E5 cosine should separate them. The test floors are 90/90.
- Not verified on host: real E5 cosines. The host has no E5 model, so semantic behaviour was tested only through pure-function checks with controlled vectors.
- Known gap: tools/gold100_eval.py still mirrors the OLD keyword overlap. The D2 keyword numbers in docs/benchmark/gold100.md describe the pre-F1 matcher until the script is updated and rerun.
- To be checked manually: AROGYA_GOLD lines in app_log.txt (score/second/pin) for the checklist A/B questions and the demo gold scene (E3, Hinglish "baby ko pehla gaadha doodh…" should still pin VA-005 via E5). Ask 2–3 off-topic questions (e.g. "how to make tea") and confirm pin=false. Confirm medium pins still happen for reworded questions (thresholds not retuned).

### UI1. Home screen: low-stock card removed, Today's plan shows 2 visits
- Changed (Final UI1): home no longer shows the low-stock card (low-stock line, ANM phone field, "Request refill — WhatsApp" / SMS). ArogyaLowStockCard is kept but no longer called; the refill request is still in the stock register ("Request refill from ANM"). Today's plan now lists only the first 2 due/overdue visits (was 8), each with Start visit. "Full due list ›" opens the due list as before and shows "(N more)" when more than 2 are due.
- Files: ArogyaVisitScreen.kt, ArogyaRegistryUi.kt.
- Tests: Compose UI only, not in the host suites. BUILD SUCCESSFUL (1m50s). Installed on the phone with adb install -r (user asked); installed base.apk MD5 matches the build.
- To be checked manually: home has no low-stock/refill card; Today's plan shows 2 visits; "Full due list (N more) ›" opens the full list; the stock register refill still works.

### UI2. Ask the ASHA guide: speed pass + answer screen layout
- Diagnosis (phone logcat, one offline turn, "baby is having high fever and not taking breats feed"): total 104.7 s, first words 53.3 s. AI question rewrite 4.9 s; KbParityCheck debug self-test 2.9 s (first turn after start; GeM-tender fixture from another project); rerank 28 pairs 14.9 s; 100-question gold match 0.18 s (pin=false, so no extra prompt); Llama prefill of 2,135 tokens 29.2 s; generation of 283 tokens 38.2 s; after-answer proof check 13.0 s (10 single-pair rerank calls of ~1.15 s, close to its 12 s timeout). The 100 verified questions are not the cause.
- Changed (Final UI2):
  - Speed: removed the debug asset kb_parity.json, so KbParityCheck now does nothing (reranker/parity.json, which the reranker's own check needs, is untouched).
  - Speed: GenericKbReranker.scorePairs scores pairs with different questions in one batched run (same cache, same logits). AnswerProof.check takes an optional pairScorer and reads all statement–passage pairs in one call, falling back to the per-statement scorer if the batch fails.
  - Speed: offline phone turns cap evidence at 4,000 chars (GenericKnowledgeContextProvider.PHONE_EVIDENCE_CAP_CHARS; the user chose this trade-off). ArogyaVisitAi sets the cap only for offline turns (not online, not Cloud-first) and for the phone retry after a cloud failure. Check online stays wide. Logged as AROGYA_PHONE_EVIDENCE_CAP. The candidate pool is unchanged (8).
  - UI: the Ask window is now near full screen (6 dp margin, was 94% height + 12 dp). Header holds title, ↺ New, 📖 guides and ✕ close (the separate Guides row and bottom Close are gone). The question box (1–3 lines) and Ask button share one row. The composer is hidden while an answer is being written (Cancel is on the answer card). Suggestion chips show only before the first answer.
  - UI scroll fix: the old auto-scroll called animateScrollToItem(last) on every streamed word, which pinned the streaming card's top and pulled the user back. Now it jumps to the end of the last item (newest line) without animation, stops following as soon as the user drags the list, resumes when they reach the bottom, and resets on each new question.
- Files: ArogyaVisitScreen.kt, ArogyaVisitAi.kt, GenericKnowledgeContextProvider.kt, GenericKnowledgePackRepository.kt, GenericKbReranker.kt, AnswerProof.kt, AndroBaselineRuntime.kt, app/src/main/assets/kb_parity.json (deleted), tools/host-tests/ProofBatchTest.kt (new; added to run.ps1 and run.sh).
- Tests: ProofBatchTest 9/9 (batched vs per-statement: same verdicts, same scores, 1 call instead of 5, failed batch falls back, no-scorer path untouched). BUILD SUCCESSFUL (3m04s). Installed with adb install -r (user asked); base.apk MD5 matches the build.
- Expected (NOT yet measured): first words ~53 s → ~41 s (−9 s prefill, −3 s self-test on the first turn); total ~105 s → ~80 s (plus −8 s proof). Generation speed is unchanged (7.4 tok/s).
- To be checked manually: ask the same fever question offline and send logcat (AROGYA_ASK_BREAKDOWN firstWordsMs/totalMs, AROGYA_PHONE_EVIDENCE_CAP, RERANK_TIMING ... mixed=true); answer quality with 4 passages vs 6; scroll up while an answer streams (it must stay put), scroll back down (following resumes); the answer area is visibly larger; ✕ closes; Cloud answers still use full evidence (no AROGYA_PHONE_EVIDENCE_CAP line in Cloud mode).

### UI3. Home: two status lines and the Today's plan card removed (BUILD PENDING)
- Changed (Final UI3): home no longer shows "Sample data · fictional people" or "Saved on this phone • private by default". Live status ("Saving on this phone…", "Processing locally…") still shows while it applies. "Clear sample data" (that line used to be the only way in) is now a button next to "Reset sample village", with the same confirm dialog. The "Today's plan" card is removed from home; the Today card and "Next visits this week" strip stay unchanged. ArogyaTodayPlan is kept in ArogyaRegistryUi.kt but no longer called.
- Files: ArogyaVisitScreen.kt.
- Tests: Compose UI only (not in the host suites). Built later with UI4: BUILD SUCCESSFUL (1m09s), installed with adb install -r, base.apk MD5 matches.
- To be checked manually: neither line on home; Today card + Next visits strip present; no Today's plan card; Clear sample data asks for confirmation and keeps real people.

### UI4. Ask the ASHA guide: chat-style layout (BUILD PENDING)
- Changed (Final UI4): the Ask window now follows a modern AI-chat layout, using the app's existing theme colours (MaterialTheme primary / primaryContainer / surfaceVariant).
  - Top bar: ✕ close (left), title, Ask SOP badge, 📖 guides used, ✎ New chat; a divider below.
  - Thread: each question is a right-aligned bubble (primaryContainer); each answer sits below it on the left under a small "📚 ASHA guide" avatar row, as plain text (no card). Earlier answers now show in full (they used to collapse to 6 lines behind "Show more"). The answer while writing uses the same bubble + avatar layout.
  - Empty chat: centred greeting, one line on where answers come from, and tappable example cards (the guide's own topic suggestions when known, else 4 built-in ASHA questions).
  - Composer: one rounded bar at the bottom with ＋ (📷 Take photo / 📎 Photo-PDF), a borderless 1–5 line question box, 🎤 (when the box is empty) and a round ↑ send button that becomes ■ stop while an answer is being written. Sending clears the box (the question stays as a bubble). Attachment chip + Remove sit above the bar. A one-line disclaimer sits under it.
  - Answer actions in one compact row: 🔊 Listen, ⏹ Stop, 👪 Family (हिंदी), 👪 English, ☁ Check online, ➕ More detail, ↗ Share.
  - Every earlier feature is kept: sources line + passages + Open official document, inline citations, Offline/Online tabs, guide-says quote, family block, Ask ANM / Save to check later (r8), Escalate to ANM (now escalates the asked question, since the box is cleared after sending), saved-questions banner, auto-follow scroll (UI2).
- Files: ArogyaSopChat.kt (new SopUserBubble, SopAssistantHeader, SopEmptyState, SopComposer; history, streaming and action row restyled), ArogyaVisitScreen.kt (Ask top bar, thread items, composer).
- Tests: Compose UI only (not in the host suites); brace balance checked. BUILD SUCCESSFUL (1m09s, compileDebugKotlin ran, warnings only). Installed with adb install -r (user asked); base.apk MD5 matches.
- To be checked manually: empty state + tapping an example fills the box; send clears the box and shows the bubble; ■ stops a running answer; ＋ menu photo/PDF; 🎤 dictation; scrolling earlier full answers; each action in the row; the urgent warning's Escalate carries the right question; Hindi labels.

### UI5. Ask: official PDF link restored + after-answer check stops on time (BUILD PENDING)
- Diagnosis (phone logcat, "How do I make ORS at home?", 124 s): first words 36.9 s (was 53 s before UI2); generation 72 s (303 tokens at 6.7 tok/s); then KB_PROOF ran 41.7 s with every score unscored (no RERANK line). The proof's 12 s timeout was swallowed by runCatching (a CancellationException turned into a per-statement fallback), and ONNX kept reading all ~20 pairs. Missing link: this answer was gold-pinned (VA-067, pin=true), and gold source rows were built without the " | url" tail that normal passages carry, so the card had no "Open official document" (ArogyaSource.url empty).
- Changed (Final UI5):
  - Link: GoldPassage carries url (pack catalog sourceUrl first, else the verified answer's source_url). ArogyaGoldPin.sourceRef appends " | url" (http only), and the pinned Hit carries sourceUrl. pageUrl never adds a second #page.
  - Link: the answer's source line now has a "📄 Open PDF" button (🌐 Open for web sources) that opens the first linked source at its cited page in one tap. Tapping a citation still expands the passages, each with "Open official document".
  - Speed: AnswerProof rethrows CancellationException (batch and per-statement), so the 12 s proof timeout really stops the check. GenericKbReranker checks for cancellation between batches of 8. A batch of more than 12 pairs (MAX_PROOF_PAIRS) reads each statement with its single best passage plus any passage holding its numbers.
- Files: ArogyaGoldPin.kt, GenericKnowledgePackRepository.kt, GenericKnowledgeContextProvider.kt, ArogyaGuideDb.kt, ArogyaSopChat.kt, AnswerProof.kt, GenericKbReranker.kt, GoldPinTest.kt, ProofBatchTest.kt.
- Tests: ProofBatchTest 12/12 (new: cap at 12 pairs still scores every statement; a cancellation propagates with no fallback); GoldPinTest 24/24 (new: gold ref carries the url, openTarget finds it, non-http dropped, pageUrl adds #page once); GuideLibraryTest 21/21 (pageUrl unchanged for normal links). The build is held until the user asks. Built with UI6: BUILD SUCCESSFUL (1m35s), installed with adb install -r, base.apk MD5 matches.
- Expected (NOT measured): the after-answer wait drops from up to ~42 s to at most ~12 s (usually ~5–8 s with ≤12 pairs). The ORS turn would then total ~95 s instead of 124 s. First words are unchanged (~37 s); generation (~6–8 tok/s on the phone CPU) is the remaining floor offline. Cloud mode answers in seconds.
- Not changed, on purpose: the 4,000-char evidence cap, the rerank pair limit (the user kept it) and answer length (B-rule of at least 6 sentences). Prompt-prefix reuse was looked at and dropped: other model calls (rewrite, family, warm-up) replace llama.cpp's cache between questions.
- To be checked manually: a gold-pinned question (e.g. "How do I make ORS at home?") shows "📄 Open PDF" and opens the PDF at the page; a normal question too; total time and KB_PROOF ms in the log.

### UI6. Ask: instant Stop + clean citations (BUILD PENDING)
- Quality/length check (asked by the user): answer length is unchanged (600-token budget, the at-least-6-sentences rule untouched; turns since UI2 generated 236–358 tokens vs 283 before). Cloud answers keep full evidence. Offline answers read ~4 passages instead of ~6 (the UI2 trade-off), so broad questions may be slightly less complete. The batched proof gives identical scores. Because the proof now finishes inside its timeout, it can prune a statement found nowhere (only when at least half remain); before, it often timed out and pruned nothing.
- Diagnosis, Stop: the screen stayed busy until the job ended. A Stop during re-ranking (blocking ONNX batches of ~6 s) or the old 42 s proof only took effect once that step returned. Native llama cancel itself is fast (abort_callback).
- Diagnosis, symbols: the cloud model writes its own source tags ([S1], [1], [S1, S2]) into the text, and the card added its own tappable superscripts per source after every sentence ("[S1] ¹ ³", read as "13").
- Changed (Final UI6):
  - Stop: one stopAi() for ■ and every Cancel. It cancels as before, and if busy frees the screen at once ("Stopped. Ask again whenever you are ready."). Each Ask / More-detail run has a run number; a stopped job, still finishing a blocking step in the background, can no longer change progress, result, error or busy (so it cannot clobber the next question).
  - Symbols: ArogyaAnswerFormat.stripCitationMarkers removes [S1], [S1, S2], [1][3], (S2), [Source: S1], [W2] and 【1】 from the shown answer (Tier 1 and Tier 2, and the live draft), keeping markdown links, ordinary bracketed text and doses in parentheses. The card's own superscripts remain, as the tappable link to the exact guide passage (grounding). They now show once per run of sentences from the same source(s), joined "¹,³", instead of after every sentence.
- Files: ArogyaVisitScreen.kt, ArogyaSopChat.kt, ArogyaVisitAi.kt, ArogyaAnswerFormat.kt, AnswerFormatTest.kt.
- Tests: AnswerFormatTest 51/51 (new: 12 strip cases incl. Hindi danda, links, doses, line breaks; 2 citeAt cases); TierTest 41/41. Brace balance checked. The build is held until the user asks. BUILD SUCCESSFUL (1m35s, compileDebugKotlin ran). Installed with adb install -r (user asked); base.apk MD5 matches.
- To be checked manually: ■ during search, writing and checking frees the screen at once; asking again right after a Stop works (it may wait a few seconds for the old step); a cloud answer has no [S1]/[1] and shows grouped superscripts; tapping a superscript opens that passage; shared text/PDF has no tags.

### DUI1. Demo script: 16 scenes, short non-repeating narration, screen headings (BUILD PENDING)
- Changed (Final DUI1): the "100 questions" (gold) and "One setting" scenes are removed (user items 6 and 10), leaving 16 scenes. Every scene now has exactly three short sentences in English and Hindi, rewritten so nothing repeats scene to scene ("she confirms every result" is said once, in scene 1). Each scene has an app-screen heading (Scene.screen / screenHi, e.g. "Register · Add person", "Visit · Danger signs", "MCP card · Official", "AI summary", "Impact"). New pure helpers in ArogyaDemoTicks:
  - the AI tools catalogue: 12 features, each with its real offline and online tool, Meta tools flagged, as wired in code. Llama 3.2 3B on the phone; Muse Spark 1.3, Muse Voice Transcribe and SAM 3.1 online. Whisper, ML Kit, E5 and the reranker are named with their real owners and not flagged as Meta. Danger signs are fixed rules. Card and person reading stay on the phone.
  - toolRows / metaCounts;
  - the impact table: tasks with by-hand ESTIMATES (shown labelled as estimates, never measured), today's measured time, and the last measured online and offline times remembered across runs (timesEncode / timesDecode / timesRecord).
  - ArogyaDemoTour now switches on scene ids instead of numbers (no renumbering drift); the gold and setting bodies and runner are gone.
- Files: ArogyaDemoScript.kt, ArogyaDemoTour.kt, DemoTourTest.kt, Batch5Test.kt.
- Tests: DemoTourTest 51/51 (new: 16 ids, 3 sentences each, screen headings, "confirm" said once, tool rows, Meta flags, Whisper never Meta, phone-only reading, times per mode + round-trip, impact rows, hand total); Batch5Test 21/21. The build is held until the user asks.

### DUI2. Demo screens: chrome, Local | Cloud switch, chat SOP, MCP changes, tools + impact tables (BUILD PENDING)
- Changed (Final DUI2), per the user's items 1–4, 6–9, 11:
  - Item 1 (DEMO text, user chose "Minimal"): one "Sample data · fictional village" badge on the start screen only. Gone from every scene's top bar and the end screen. 108 / ANM / WhatsApp still say "Not sent in demo — real use …" only when tapped. The intro body says "Rampur · N people in the register".
  - Item 2 (user chose "Flip + re-run"): the start screen has "AI runs on: 📱 Phone (offline) | ☁ Cloud (Meta)". Every AI scene (add person, voice, danger, explain, sops, card, medscan, practice) has an "AI on: 📱 Phone | ☁ Cloud" row under the title. It writes the same setting as Home (ArogyaCloud.setCloudFirst) and re-runs the scene's AI step (replay). Photo scenes re-run when the photo is picked again. "offline → phone" shows when Cloud is picked with no internet.
  - Item 3: classier start screen (ArogyaDoot title, one-line subtitle, language + AI mode chips, a compact internet/voices line, full-width Start). The top bar shows the app-screen heading chip (Scene.screen) and "n/16". The title is titleMedium. Narration is 16 sp (was 18–21 sp) and only the sentence being read is highlighted (no grey box per line). The loading screen reads "Getting the demo ready".
  - Item 4: bottom buttons (◀ Back, ⟳ Replay, Next ▶) and 108 / ANM / WhatsApp are single-line (maxLines 1, softWrap off, equal weights, tight padding).
  - Item 6: the Ask SOP scene uses the new chat layout (question bubbles, guide avatar, answer with tappable citations, "📚 From the guide … ▾" folding the passages, "📄 Open PDF" at the cited page; the cloud answer labelled "☁ Checked online — N sentences added").
  - Item 7: every Start rebuilds the sample village (existing loadSample), so Neha's card returns to the same starting state each run. The card at Start is kept (cardBeforeHtml) to diff against. Medicine stock saved in the demo now gets a "demo-scan-" id, so the next Start clears it (before, it piled up in the stock register across runs).
  - Item 8 (user chose "Keep, label honestly"): "🔍 Medicine scan · works offline" (on-phone text reading + rules, the model only fills gaps); "☁ Drug-kit count · Meta SAM 3.1 (online only)" with "Offline, the kit is counted by hand — the app never guesses a count".
  - Item 9 (user chose "NA / Not recorded"): ArogyaDocRender.demoMark highlights in yellow each handwritten cell that differs from the card at Start, shows "NA" for not-applicable (×) cells and "Not recorded" (grey italic) for empty cells, and adds a legend with the changed count. Never writes Yes/No for a value never recorded. Demo view only; the real printout is unchanged. The scene says "N fields changed today — highlighted in yellow".
  - Item 11: the "AI tools behind this visit" table: 12 features × (📱 offline tool, ☁ online tool, Today ✓ where/time). Meta tools in blue (Llama 3.2 3B on the phone; Muse Spark 1.3, Muse Voice Transcribe, SAM 3.1 online); Whisper, ML Kit, E5, the reranker and Android voice named with their real owners; danger signs ⚙ fixed rules. A summary line counts the Meta features. "App built and optimised with Muse Code."
  - Item 11, last slide: "How AI eased Sunita's visit". Rows for measured tasks: AI did | By hand* (~min, estimate) | Today (measured) | ☁ Online | 📱 Offline (last measured in each mode, kept across runs in prefs "times"), plus a total row and "about N minutes of paperwork saved per visit". The footnote says hand times are rough estimates, not measured.
- Files: ArogyaDemoTour.kt, ArogyaDemoScript.kt (latest tick per feature), ArogyaDocRender.kt (demoMark), DocRenderTest.kt.
- Tests: DocRenderTest 46/46 (new: changed cell highlighted, × → NA, blank → Not recorded, unchanged kept, no Yes/No written, legend + count, no false highlight when the card shape differs, Hindi labels); DemoTourTest 51/51. Brace balance checked. The build is held until the user asks, so these Compose changes are NOT compiled yet.
- To be checked manually: the whole demo in EN and HI; the Phone | Cloud flip re-runs voice / explain / sops and the times differ; the MCP scene shows yellow changes + NA / Not recorded; a second demo run starts from the same card and stock; button labels on one line on the phone; both tables are readable at phone width.

### DUI3. Sample demo photos (item 5)
- Made (Final DUI3): tools/demo_samples/make_samples.py (Pillow; run `python make_samples.py <out_dir> <yyyy-mm-dd>`) draws six synthetic photos. Every person, phone and ID is fictional; the "Aadhaar" 0000 1111 2222 is deliberately invalid (real ones never start with 0/1) and only shows the masking.
  1. 01_new_family_slip.jpg: one new family (Kavita Yadav, pregnant, H-051) — the photo for the "Add person from a photo" step (the reader takes one person per photo).
  2. 02_register_10_people.jpg: household register page with 10 people (the user asked for 10). The add-person reader takes one person per photo, so the slip (1) is the one to use for the live step.
  3. 03_mcp_card_baby_of_neha.jpg: MCP immunization page for the demo hero (Baby of Neha, home birth, 2.8 kg, DOB today−6). BCG, OPV-0 and Hep B dated day 1, so applying it adds vaccines and changes the card (highlighted in the MCP scene).
  4. 04_medicine_paracetamol_on_cloth.jpg and 05_medicine_IFA_on_cloth.jpg: strips on white cloth with name, batch, Mfg, Exp and "10 Tablets", matching kit items Paracetamol and IFA.
  5. 06_drug_kit_6_ORS_sachets.jpg: six separated ORS sachets on cloth for the SAM 3.1 count ("ORS sachet").
- Copied to the phone (user asked) with adb push to /sdcard/Pictures/Demo_samples/ and registered with the media scanner, so the in-app photo picker shows them. Images are not in the repo (only the generator).
- Verified on host: all six images rendered and visually checked for legibility.
- To be checked manually: each photo through its demo step (person, card, medicine, kit count online). Dates on the card match the hero only on 4 Nov; on another day, regenerate with that date.

### DUI4. Review of DUI2 (not yet compiled) + two fixes
- Reviewed the DUI2 Compose code against the code it calls (SopUserBubble, SopAssistantHeader, SopAnswerText, ArogyaCloud.setCloudFirst, Scene.screen/screenHi, ToolRow/Impact, StockTxn.id, FilledDoc.copy, FilledTonalButton/HorizontalDivider/TextOverflow imports). Every name exists, is public and has matching parameters. No compile errors found by reading, but Compose still needs a real Gradle build.
- Fixed: Home's Offline | Online switch stayed stale after the demo flipped Phone | Cloud (it caches the setting until `modeTick` changes). Closing the demo (or a deep link out of it) now bumps `modeTick`. File: ArogyaVisitScreen.kt.
- Fixed: when the MCP card's cells could not be paired with the card at Start, `demoMark` returned 0, so the scene said "No field changed yet — run the voice note…", which was untrue. It now returns -1: the scene shows no change line, and the legend leaves out the "Yellow = changed today" part. File: ArogyaDocRender.kt; DocRenderTest updated.
- Tests (Linux host, kotlinc 2.0.21): all 47 suites pass except GoldMatchTest, which needs `app/src/main/assets/verified_answers.json`. That file is gitignored, so it is not in this checkout. It is not a code failure, but a clean clone will build without the gold answers.
- Still to do on Windows: `_claude_work\run\build_install.bat` (the DUI2 + DUI4 Compose code is not compiled yet), then the manual checks listed under DUI2.
