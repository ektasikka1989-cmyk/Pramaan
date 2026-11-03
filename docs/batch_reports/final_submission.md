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
