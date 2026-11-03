# Forms verification — official rows vs app rows

Sources (all verified from `ASHA_Health_Guide.db` passages; chunk source + printed page):
- F = Handbook for ASHA Facilitators (`asha_fac_en.pdf`): Annexure 9 (spec pp. 60–63; extracted text pp. 59–61), Annexure 5 Drug Kit Stock Card (p. 53), drug kit list (p. 10).
- H = Handbook for ASHA on Home Based Care for Young Child (`hbyc_en.pdf`): HBYC card Annexure 1 (p. 90), sick child §5.4 (pp. 63–69), drug doses Annexure 2 (pp. 91–94).
- HF = Handbook for ASHA Facilitators and MPWs on HBNC and HBYC (`hbnc_hbyc_fac_en.pdf`): HBYC card (pp. 28–29), kit checklist (p. 26).
- I = Immunization Handbook for Health Workers (2018) (`immunization.pdf`): RI Form 3 (pp. 56–57), RI Form 4 (pp. 57–59), RI Form 5 (pp. 59–61).
- D = ASHA drug kit refill letter (`drugkit.pdf`): stock card format (pp. 5–6), monthly quantities (pp. 2–3).

Legend: match | differs (fixed) | app addition (tagged) | blocked (source missing).

## 1. ANNEX9 — Home Visit Form (Examination of Mother and Newborn)

Official columns: Day 1, 3, 7, 14, 21, 28, 42 (F, Annexure 9). Ours: same. Match.

| # | Official row (F) | Our row | Verdict |
|---|---|---|---|
| A1 | No. of times mother takes full meal in 24 hrs (<4 → advise) | m_meals, same + hint | match |
| A2 | Bleeding: How many pads are changed in a day (>5 → refer) | m_pads, same + hint | match |
| A3 | During the cold season is the baby being kept warm (near mother, clothed and wrapped properly) | was "Baby kept warm (…)" — cold season missing | differs → fixed: "During the cold season: baby kept warm (near mother, clothed, wrapped)" |
| A4 | Is the baby being fed properly (whenever hungry or at least 7–8 times in 24 hrs) | fed, same | match |
| A5 | Is baby crying incessantly or passing urine less than 6 times a day | cry_urine, same | match |
| B1 | Temperature: Measure and record (≤102 °F paracetamol, above → refer) | m_temp + hint | match |
| B2 | Foul-smelling discharge and fever more than 100 °F (37.8 °C) → refer | m_discharge, same | match |
| B3 | Is mother speaking abnormally or having fits? → refer | m_fits, same | match |
| B4 | Mother has no milk since delivery or if perceives breast milk to be less | m_nomilk, same | match |
| B5 | Cracked nipples/painful and/or engorged breast | m_breast, same | match |
| C1 | Are the eyes swollen or with pus | eyes, same | match |
| C2 | Weight (on day 7, 14, 21, 28 and 42) | weight, same, asked from Day 7 | match |
| C3 | Temperature: Measure and Record | temp, same | match |
| C4 | Skin: Pus filled pustules | pustules, same | match |
| C5 | Cracks or redness on the skin fold (thigh/Axilla/Buttock) | skin_folds, same | match |
| C6 | Yellowness in eyes or skin: Jaundice | jaundice, same | match |
| D1–D7 | Check now for signs of sepsis: All limbs limp; Feeding less/Stopped; Cry weak/stopped; Distended abdomen or "baby vomits often"; "cold to touch" or temp >99 °F (37.2 °C); Chest indrawing; Pus on umbilicus | s_limp, s_feed, s_cry, s_abdomen, s_cold, s_chest, s_umbilicus, same | match (section header fixed to "D. Check now for signs of sepsis") |
| — | Date of ASHA's visit (first row of the printed grid) | "Date of ASHA's visit" row in `table()` | match |
| — | (no next-visit row on the printed form) | "Next visit due" row in `table()` | app addition → tagged "(app addition)" |

Source line now: "Handbook for ASHA Facilitators, Annexure 9, pp. 60–63".

## 2. HBYC card — Home Based Care for Young Child

Official: H Annexure 1 p. 90 (the card; canonical), HF pp. 28–29 (facilitator copy with extra referral follow-ups).
Official columns: 3, 6, 9, 12, 15 Months. Ours: same. Match.
Official header fields (Village/Block/District/Sub-centre/PHC, child, parents, DOB, RCH id, submission date, signatures, incentive): covered by the document renderer's common header + signature lines. Match.

| Official row (H p. 90) | Our row | Verdict |
|---|---|---|
| Whether child is sick (Yes/No) | sick | match |
| Breastfeeding continued | bf | match |
| Complementary food: 4 column-specific guidance texts (2–3 tbsp; ½ katori; ½ katori 3–4×; ¾–1 katori) | cf "Complementary food as per age" (one summary row; guidance kept in the hint) | differs → tagged "(app addition)": summary of 4 column texts |
| Recording of weight-for-age by AWW (Yes/No) | wfa | match |
| Recording of weight-for-length/height by AWW (Yes/No) | wfh | match |
| Developmental delay checked | dev | match |
| Immunization status checked | imm | match |
| Measles vaccine given (as per MCP card) | measles | match |
| Vitamin A given | vita | match |
| ORS at home | ors_home | match |
| IFA syrup at home | ifa_home | match |
| Counsel for exclusive breastfeeding | c_ebf | match |
| Counsel for complementary feeding | c_cf | match |
| Counsel for hand washing | c_hw | match |
| Counsel on parenting | c_par | match |
| Family planning counselling | c_fp | match |
| ORS given (card: Yes/No) | ors (NUMBER packets — quantity needed for the stock register) | differs (type only; row is official) |
| IFA syrup given (card: Yes/No) | ifa_syrup (NUMBER bottles — same reason) | differs (type only; row is official) |
| (not on the card) | weight | app addition → already tagged |
| (not on the card) | albendazole (Medicines given, 12 m+) | app addition → tagged |

HF-only extras (referral follow-ups after sick/dev-delay, underweight Yellow/Red flags, NRC referral): in the facilitator copy only, not on the card — not added.
Source line now: "Handbook for ASHA on HBYC, Annexure 1 (HBYC card), p. 90".

## 2b. MCP_HBYC (MCP Card p.8) vs HBYC (HBYC Handbook, Annexure 1, p. 90)

MCP_HBYC is the card's own HBYC table (Guidebook p.19), kept as a separate spec so each keeps its own source. Row-by-row:

| MCP Card p.8 row | HBYC spec (handbook) | Verdict |
|---|---|---|
| Whether child sick | sick | match |
| Breastfeeding continued | bf | match |
| Complementary food × 3 age-specific rows (× at 3 m) | single cf row (app addition) | differs — card splits by age; MCP_HBYC has cf1/cf2/cf3 with × at 3 m |
| Weight recording by AWW (one row) | wfa + wfh (two rows) | differs — card has one row; MCP_HBYC has wfa only |
| Developmental delay checked | dev | match |
| Immunization status checked | imm | match |
| Measles vaccine given (× at 3, 6 m) | measles (no × cells) | differs — × cells added in MCP_HBYC |
| Vitamin A given (× at 3, 6, 12 m) | vita (no × cells) | differs — × cells added in MCP_HBYC |
| ORS at home | ors_home | match |
| IFA syrup at home | ifa_home | match |
| Counsel for exclusive breastfeeding (× at 9, 12, 15 m) | c_ebf (no × cells) | differs — × cells added in MCP_HBYC |
| Counsel for complementary feeding (× at 3 m) | c_cf (no × cells) | differs — × cells added in MCP_HBYC |
| Counsel for hand washing | c_hw | match |
| Counsel on parenting | c_par | match |
| Family planning counselling | c_fp | match |
| ORS given | ors | match |
| IFA syrup given (× at 3 m) | ifa_syrup (no × cells) | differs — × cells added in MCP_HBYC |
| (not on the card) | weight, albendazole (app additions, tagged) | not carried into MCP_HBYC |

Both specs stay: HBYC follows the handbook (the ASHA's visit form), MCP_HBYC follows the printed card (the ANM's record), each with its own source line.

## 3. SICK — Sick child checklist (HBYC Handbook §5.4, pp. 63–66)

Kept as a checklist-style form; title fixed to "Sick child checklist (HBYC Handbook §5.4, pp. 63–66)".

| Official (H §5.4) | Our row | Verdict |
|---|---|---|
| General danger signs p. 63: not able to drink/breastfeed; vomits everything; convulsions; lethargic/unconscious → refer immediately | g_drink, g_vomit, g_fits, g_lethargic | match |
| Fever: axillary ≥37.5 °C; >7 days everyday or danger signs/stiff neck → refer urgently (p. 64) | temp + hint | match |
| Fever in endemic area: Malaria RDT positive → first-dose antimalarial + smear, paracetamol if ≥38.5 °C (p. 64) | rdt | match |
| Pneumonia: fast breathing cut-offs by age; chest indrawing → severe (p. 65) | fast_breath "Cough with fast breathing or chest indrawing" | match (checklist adaptation of Table 9) |
| Diarrhoea: >3 watery stools/day; ≥14 days/dysentery/dehydration → refer (p. 67) | diarrhoea + added official definition to the hint | match |
| ORS for diarrhoea, Plans A/B (pp. 67–69) | ors (packets given) | match (official action; app records the quantity) |
| Paracetamol for high fever ≥38.5 °C; dose table Annexure 2 (pp. 64, 91–92) | parac | match |
| First-dose Cotrimoxazole for very severe febrile disease / severe pneumonia; 5-day course (pp. 64–66, 92–93) | ctz | match |
| RDT testing in endemic area (p. 64) | mal_rdt (kits used) | match (official action; app records the quantity) |
| First-dose antimalarial for RDT positive; ACT regimens (pp. 64, 93–94) | act | match (official action; app records the quantity) |
| Referral actions throughout §5.4 | referred | match (app recording row for official actions) |
| (zinc not in the §5.4 passages; standard diarrhoea care) | zinc | app addition → tagged |

Source line now: "Handbook for ASHA on HBYC, §5.4, pp. 63–66".

## 4. FP — Family planning entry

No single national form; kept as an entry aid titled "Family planning entry — based on the MCP card FP page; state formats vary".
Rows (method, counsel, condoms, ocp, napkins, ecp, ptk) are the app's entry format, not official rows — stated in the title/note rather than per-row tags. Not verifiable row-for-row until the MCP card PDF arrives.

## 5. MCP_ANC + MCP card document — BLOCKED

`_claude_work/sources/` is missing, so MCP_Card_2018.pdf is unavailable and the MCP card is not in the guide DB.
Stopped without guessing: MCP-ANC row rebuild, immunization section, growth-chart section, MCP PDF relayout.
Current rows stay as-is. Revisit when the PDF is placed.

## 6. RI Form 3 — House to House Survey Form (I pp. 56–57)

| Official column | Ours | Verdict |
|---|---|---|
| A House No. (continuous serial for the area) | A House No. | match |
| B Head of household | B Head of household | match |
| C Father's name | was "C Father's / husband's name" | differs → fixed to "C Father's name" |
| D Total members (all, incl. newborns) | D Total members | match |
| E Pregnant woman (Yes → Form 4) | E Pregnant woman | match |
| F Newborn/child <1 month (Yes → Form 5) | F Newborn (<1 month) | match |
| G Child 1 month–1 year (Yes → Form 5) | G Child 1 month–1 year | match |
| H Child 1–2 years (Yes → Form 5) | H Child 1–2 years | match |

Source line now: "Immunization Handbook for Health Workers (2018), Unit 4, RI Form 3, pp. 56–57".

## 7. RI Form 4 — Pregnant Women Survey Listing (I pp. 57–59)

| Official column | Ours | Verdict |
|---|---|---|
| House No. as in Form 3 (A) | House No. | match |
| Name of the pregnant woman (B) | same | match |
| Age in years (C) | Age | match |
| Husband's name (D) | same | match |
| Mobile / Telephone Number (E) | Mobile | match |
| Is MCP card available: Yes/No (F) | MCP card ("Yes" — app assumes card issued at registration) | differs (app default; ANM verifies) |
| EDD / LMP (G) | EDD / LMP | match |
| Tetanus Toxoid TT-1, TT-2, TT-Booster (H) | TT/Td-1, TT/Td-2, TT-Booster | match (Td = current UIP term for the same column) |
| 1st–4th ANC (I) | 1st ANC … 4th ANC | match |
| TT due Y/N, ANC due Y/N (J, for ANM) | added, computed from records: TT due = fewer than 2 TT/Td dates; ANC due = fewer than 4 ANC dates | differs → added as official columns filled from app records |

Source line now: "Immunization Handbook for Health Workers (2018), Unit 4, RI Form 4, pp. 57–59".

## 8. Drug Kit Stock Card — Annexure 5 (F p. 53; D pp. 5–6)

Official columns: Month & Date of Refill (1..N) × (Balance, Refill given) after S. No. | Name of Drug | Symbol*; Balance = left in kit at refill; Refill = put into the kit; card updated by the person giving the refill.
Ours: S. No. | Name of drug | [new] Symbol | per-refill Balance/Refill-given pairs | Balance now. Match after adding the Symbol column (pictorial marks are app presentation: one fixed emoji per drug).
Official drug list (F p. 10): Paracetamol, Albendazole, IFA, OCP, ECP, ORS, Chloroquine, Condoms, Eye ointment, Cotrimoxazole. Monthly quantities (D pp. 2–3): Iron 90, Folic acid 90, ORS 125 sachets, PCM 150, Chloroquine 20, OCP 50 cycles, EC 50, Condoms 100. Our catalogue covers these plus programme additions (zinc, IFA syrup, calcium, RDT, ACT, test kits, napkins) — catalogue breadth is intentional and unchanged.
Source line now: "Handbook for ASHA Facilitators, Annexure 5, p. 53".

## Test changes (official document required each one)
- `FormsTest` RI Form 3 header: "C Father's / husband's name" → "C Father's name" (I p. 57: father's name).
- `FormSpecsTest` last-row assertion: "Next visit due" → "Next visit due (app addition)" (row is ours, not the printed form's).
- New `FormsVerifyTest`: official row labels/order/columns/source lines + (app addition) tags for ANNEX9, HBYC, SICK, RI Forms 3/4, Annexure 5.
