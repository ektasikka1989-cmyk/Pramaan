# Audit as an ASHA: slip upload → medicine distribution (4 Nov 2026)

**How it was checked:** `tools/host-tests/AshaJourneyTest.kt` runs Sunita's day through the app's own code, the same functions the demo screens call:
- the register row reader;
- the person mapping and register tables;
- form selection, the form fill parser with its rule backup, and the danger rules;
- the save (form + MCP card + linked p.7 + stock + diary + visit record);
- the MCP card rendered from the real `mcp.html` template;
- the stock register, low-stock and refill text;
- the medicine-scan reader.

Phone-only steps are fed realistic inputs: ML Kit text with line positions, and a typical model reply that still goes through the app's real checks. The voice note is tested in the **worst case, with no AI at all**.

**Result: 51/51 checks pass after this round's fixes.** Six problems were found on the way; all are fixed below. What still needs the phone is listed at the end.

## Workflow 1: new pregnancy (Sarita)
| Step | What Sunita expects | Result |
|---|---|---|
| Slip photo | One person; all 8 columns read (name, husband, age, mobile, house, village, category, LMP); footer and Aadhaar line ignored | ✓ |
| Aadhaar | Never stored | ✓ |
| Registers | In the village register; in the ANC register with EDD; on the due list | ✓ |
| Right form | MCP card ANC page, visit 1 | ✓ |
| Voice note (no AI) | weight 49, BP 110/70, Hb 10.2, 10 weeks, IFA 30, calcium 60, no bleeding/headache/fever | ✓ |
| Danger | None | ✓ |
| Save | ANC form entry; card ANC-1 ticked with weight and BP; card IFA total 30; stock IFA −30 and calcium −60 against Sarita's name; diary line; next due date; no referral | ✓ |
| MCP card (real template) | Header Uttar Pradesh / Sitapur / Mishrikh / Rampur SC / Sunita Devi; Sarita, Ramesh Yadav, 23, mobile, "H-051, Rampur", LMP, EDD; ANC grid 49 / 110/70 / 10.2 | ✓ |
| "Open official form" | Form table shows 49 and 110/70 | ✓ |

## Workflow 2: newborn day 7 (Baby of Neha)
| Step | What Sunita expects | Result |
|---|---|---|
| Card photo | Rows rebuilt ("Birth · OPV-0 · 31/10/2026" on one line); 3 birth doses read **with the ANM's dates** | ✓ (fixed) |
| Unreadable date | A vaccine is never recorded as "given today" | ✓ (fixed) |
| Apply | BCG / OPV-0 / Hep B on the card with the day-3 date; leaves the due list | ✓ |
| Right form | HBNC (Annexure 9), Day 7 | ✓ |
| Voice note (no AI) | weight 2.65, temp 38.4, feeding less, mother 37 °C, 3 pads | ✓ |
| Danger | ⚠ refer (fever, poor feeding) by fixed rules | ✓ |
| Save | HBNC Day 7 entry; MCP p.7 "7th Day" for the baby (2.65, 38.4, sucking Poor) and the mother (37, bleeding Normal); growth-chart weight; referral recorded; next due Day 14; mother's IFA/calcium out of stock, card IFA total +30 | ✓ |
| MCP card (real template) | 7th Day values, vaccine dates, the "recorded at the ASHA's HBNC home visit" label, header; mother's husband Rajesh Kumar | ✓ |

## Workflow 3: sick child (Rohan)
| Step | What Sunita expects | Result |
|---|---|---|
| Voice note (no AI) | ORS 4, zinc 14, diarrhoea Yes, temp 37.2 | ✓ |
| Danger | None → treat at home | ✓ |
| Save | Zinc 14 → 0, ORS −4; follow-up in 2 days; form table shows 14 | ✓ |
| Low stock / refill | Zinc OUT; refill text names zinc | ✓ |
| Scan new zinc strip | Name ZINC SULPHATE → kit item "Zinc tablets"; batch ZN2611A; expiry 07/2028; qty 30 | ✓ (fixed) |
| Other strips | IFA strip → "IFA tablets (adult)" (not zinc); paracetamol "B. No." batch read | ✓ (fixed) |
| Stock register | Zinc back to 30; row present | ✓ |

## Problems found and fixed this round
1. **Medicine scan read the batch as "No".** "Batch No. ZN2611A" matched the word "No". Fix: "Batch No.", "B. No." and "Lot No." are understood, and a batch code must contain a digit.
2. **Medicine scan picked "DISPERSIBLE TABLETS IP" as the name**, and the item match used the generic word "tablets", so an IFA strip could be stocked as zinc. Fix: dosage-only lines are skipped as names; kit items are matched by the medicine's own words (iron / folic / IFA → IFA tablets, zinc, ORS, paracetamol, calcium, albendazole and others); dosage words never decide.
3. **Card photo could record vaccines as given today.** When a vaccine's date wasn't on its line (ML Kit lists tables column by column), `ArogyaCardApply` used today's date. Fix: a vaccine or weight without a date read from the card is shown as "date not readable" and **not applied**. The card reader now gets the card as rebuilt table rows (`ArogyaTableOcr.rowText`), so dates stay with their vaccine.
4. **"Open immunization register" on the card screen would not show the baby**, because that register lists only doses due this month. Replaced by "MCP card · immunization page".
5. (From DUI6, re-verified here) **The ANC form came back empty** with a 63-row prompt. The relevant-rows prompt, partial-reply rescue and rule backup fill it even with no AI.
6. (From DUI6, re-verified here) **Decimals were cut** in the rule backup (38.4 → 38). Fixed and tested.

## Still needs the phone (cannot be proven on a laptop)
- The real photos through ML Kit (the slip, the card, the zinc strip): check `AROGYA_REGISTER_ROWS`, `AROGYA_CARD_TEXT rows=… using=rows` and `AROGYA_MEDSCAN` in `app_log.txt`.
- The phone model's own answers: `AROGYA_FORM_FILL_SPLIT ai=… rules=…` shows how many rows the AI filled vs the rules.
- The screens: the Open buttons, the viewer, the yellow highlights, the confirm-before-save step, and Hindi.
- Ask SOP, Explain and Practice: these depend on the guide DB and the model on the phone.

## Round 2: real-life scenarios (`tools/host-tests/RealLifeTest.kt`, 26 checks, all pass)
| Scenario | Result |
|---|---|
| Same person registered twice | Duplicate found |
| ANC visit 2 five weeks later | Next column; "last time: 49 kg" shown; card ANC-2 ticked; IFA total 60 |
| Pregnancy danger said only in words, no AI (EN + HI): bleeding, severe headache / blurred vision | Rows filled by fixed rules → **refer**; "no bleeding but severe headache" handled |
| Home delivery → HBNC Day 1 → Day 3 missed → Day 7, saved twice | Right columns; Day 3 left blank; no duplicates; mother's IFA counted once; MCP p.7 linked; next due Day 14 |
| Sick child, fast breathing + chest indrawing + not drinking, no AI | Rows filled by fixed rules → **refer**; a well child is not flagged |
| Medicine with an empty kit | Warned before saving (form rows and medicines in the note) |
| Backup and restore | Forms, visit note, Aadhaar and stock identical |
| Monthly report and incentives | Count real visits only (linked MCP entries are not extra visits) |
| Hindi note | Same rows as English |

**Fixed in this round:**
- **Danger signs said in words now fill the danger rows by fixed rules,** in English and Hindi: fast breathing / chest indrawing, fits, lethargy, unable to drink, vomiting everything, bleeding, headache / blurred vision, fever, water broke, labour pain / reduced movements, limp, pus on the cord.
- Negation now applies per clause.
- An empty-kit warning appears in the guided visit and the demo.
- The visit note is kept with the visit; sentences not used by the form are shown and kept; medicines in the note go to stock; danger signs in the note refer.
- The demo's "Open this visit" button appears only when it opens the same form.
