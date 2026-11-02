# Gap round — audit notes (3–5 lines per item)

## Item 1 — Official forms and cards, row for row
- Specs live in `ArogyaOfficialForms` (`ArogyaFormSpecs.kt`: ANNEX9/HBYC/MCP_ANC/SICK/FP + `table()`); registers in `ArogyaForms` (`ArogyaRegistry.kt`: riForm3/riForm4/drugKitCard/homeVisitForm); provenance plumbing exists (`FormTable.source` → screen grid + `table.html` docsrc).
- Official text verified from guide DB passages (not memory): `asha_fac_en.pdf` (Annexure 9 pp. 59–61, Annexure 5 p. 53, drug list p. 10), `hbyc_en.pdf` (HBYC card p. 90, sick §5.4 pp. 63–69, drug doses pp. 91–94), `hbnc_hbyc_fac_en.pdf` (HBYC pp. 28–29), `immunization.pdf` (RI Forms 3/4/5 pp. 56–61), `drugkit.pdf` (Annexure 5 card pp. 5–6).
- `_claude_work/sources/` does not exist — MCP_Card_2018.pdf missing. Per the rules, MCP-ANC row rebuild + MCP immunization/growth sections + MCP PDF relayout are STOPPED and reported, not guessed. MCP_ANC rows and MCP card doc stay as-is, marked blocked in `docs/forms_verification.md`.
- Fixes: ANNEX9 cold-season wording + D header; source page numbers on all specs/registers; SICK retitle + diarrhoea hint; FP retitle; `(app addition)` tags (HBYC albendazole/cf, SICK zinc, Next-visit row); RI Form 3 col C label; RI Form 4 +TT/ANC-due columns; drugKitCard +Symbol column. New `FormsVerifyTest` guards the official rows.
