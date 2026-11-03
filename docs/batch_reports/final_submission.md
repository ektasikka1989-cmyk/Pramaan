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
