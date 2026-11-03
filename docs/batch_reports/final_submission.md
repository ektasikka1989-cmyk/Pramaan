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
