# Demo v2: diagnosis of the reported issues + plan (4 Nov 2026)

Status: **PLAN FOR APPROVAL. No code changed.** Items refer to the owner's list (1–10).

## A. Diagnosis (from the code; the phone log will confirm items 3–6)

| # | What you saw | Cause found in the code | Fix |
|---|---|---|---|
| 1 | "Sunita is the ASHA of Rampur, about 1,000 people." is broken English | Script text | "Sunita is the ASHA for Rampur, a village of about 1,000 people." (Hindi: "सुनीता रामपुर गाँव की आशा हैं, जहाँ लगभग 1,000 लोग रहते हैं।") |
| 2 | "Dates come from fixed government schedules, never from AI" | Script text | "These visits follow the fixed schedules in the government guidelines." |
| 3 | Slip photo uses only a few details; nothing to open and check | The review card shows only name, husband, village, phone and category. Age, house, LMP and EDD are read and saved but **not shown**. The demo path doesn't log which reader ran (row reader vs AI). There are no buttons to open what was written. | Show every field read, with the source ("from row 1 of the slip"). After saving, show **Open village register**, **Open pregnancy register** and **Open MCP card p.3** buttons, each opening the real document viewer with the new person highlighted. Log `AROGYA_DEMO_PERSON engine=… fields=…`. |
| 4 | Screen 5: AI runs ~1 min, then nothing, no message | The ANC page at visit 1 has **63 fields**. The phone model gets a 3,700-character prompt and only 700 tokens to reply, so its JSON is most likely cut off or malformed and the parser keeps nothing (`filled=0/63`). Tested here: a correct reply is kept in full (weight 49, BP 110/70, Hb 10.2, IFA 30, calcium 60, bleeding No), so grounding is not the problem. The screen also **says nothing when 0 fields are filled**, and the backup rule only knows baby weights. | (a) Send the model **only the rows the note talks about**, picked by keywords (≈8–12 rows, not 63). (b) **Rescue partial replies**: read each `{"id","value","evidence"}` object on its own, so a cut-off reply still gives the fields before the cut. (c) **Rule-based backup** for numbers in the note: weight (adult too), BP, Hb, temperature, IFA, calcium, ORS, zinc, each with its evidence. (d) Never silent: "AI filled N of M rows — check them", or "AI could not fill this — values from the note by rules: …". |
| 5 | ANC card: only the visit date | Same as 4: the save wrote an entry with no values | Fixed by 4. The save screen also refuses to call itself "updated" when nothing but the date was written. |
| 6 | MCP card (screen 7) and screens 8–9 empty | Same as 4 | Fixed by 4. |
| 7 | Daily practice screen missing | It exists in the engine (`"practice"`) but isn't in any workflow | Added to every workflow (one screen). |
| 8 | Ask SOP missing | It's only in Workflow 2 | Added to every workflow with a question that fits it, using the chat layout, follow-up, cited page, "Open PDF" and the online check. |
| 9 | AI features not shown (explain, follow-up, danger, ANM WhatsApp) | Spread unevenly; Workflows 1 and 3 have none | Matrix below: each workflow shows the full set relevant to it, in 11–12 screens. |

**Found in the audit (not in your list):**
- **Danger signs depend on the AI (must fix).** The demo's danger screen uses only `ArogyaOfficialForms.dangers(form, AI-filled values)`. If the AI fills nothing, a baby with 38.4 °C shows "No danger signs", which breaks AGENTS rule 5. The fixed rules engine `ArogyaDangerSigns.evaluate(note)` exists but isn't called. Fix: run it on her words first (newborn/postnatal), then add the form's own action rules. The screen says "⚙ Fixed rules, not AI".
- Pregnancy and sick-child danger rules: the form rules (BP ≥140/90, Hb <7; sick-child general danger signs) work on whatever values were filled. With fix 4(c), BP, Hb and temperature come from rules even if the AI fails.
- **No "updated" proof anywhere.** Fix: one shared **"Updated records" panel** with an **Open** button per record, used after the person save, the visit save, the card photo apply, the medicine scan and the refill. Every button opens the real document (MCP card, the official form table, village/pregnancy/immunization register, stock register) through the existing viewer, with today's rows highlighted.
- **Explain to family runs a full guide search again.** Workflow 2's explain step repeats the cord question (1–2 extra minutes offline). Fix: explain the **visit's own advice** (referral/danger, or the Ask answer if it already ran) with no second search.
- **Ask SOP in the demo makes three guide calls** (question, follow-up, online check), about 3–5 minutes on the phone. That's fine with video editing. The online check is shown only when online; otherwise "needs internet — skipped" is labelled.
- **Save doesn't require "Sunita confirms".** Fix: the save screen asks for the tick first if it wasn't given (the AI-output approval rule).
- **The refill message is built by hand** in the demo. Fix: use the existing `ArogyaRegistryLogic.refillRequest` (exact out/low/expiring lines) inside the ANM template.

## B. Feature showcase matrix (✓ = shown in that workflow)

| Feature (AI or rule) | W1 New pregnancy | W2 Newborn day 7 | W3 Sick child |
|---|---|---|---|
| Plan the day (fixed schedules) | ✓ | ✓ | ✓ |
| Add person from photo (rows read on the phone, Aadhaar masked) + open register | ✓ | | |
| MCP card photo read + apply + open card | | ✓ | |
| Right form opens (rule) | ✓ | ✓ | ✓ |
| Voice note → form, each value with its evidence | ✓ | ✓ | ✓ |
| Danger assessment (⚙ fixed rules) | ✓ pregnancy | ✓ newborn + mother → REFER | ✓ sick child → treat at home |
| Escalation: ANM WhatsApp message (AI draft, she edits) | — (no danger) | ✓ urgent referral | ✓ kit refill |
| Ask SOP: question + follow-up + cited page + Open PDF (+ online check) | ✓ "How many IFA tablets in pregnancy, and how to take them?" | ✓ cord care (existing) | ✓ "How do I make ORS and give zinc for diarrhoea?" |
| Explain to the family (simple Hindi, read aloud offline) | ✓ IFA + diet | ✓ why the baby goes to the PHC | ✓ ORS + zinc at home |
| One save → updated records with Open buttons | ✓ | ✓ | ✓ |
| Medicines given → stock before → after | ✓ IFA, calcium | ✓ mother's IFA, calcium | ✓ ORS, zinc |
| Official MCP card, today's changes in yellow | ✓ p.3 + p.5 | ✓ p.7 + vaccines + growth | |
| Medicine scan (new stock) + SAM 3.1 count | | | ✓ |
| Daily practice: 3 questions from the guides | ✓ | ✓ | ✓ |
| AI tools table + impact (measured) | ✓ (one combined screen) | ✓ | ✓ |

## C. Screens per workflow (Aim → How → live step → Updated)

**W1 New pregnancy: Sarita (11 screens)**
1. Meet Sunita + today's plan (merged)
2. Register Sarita from the slip photo → all fields shown → save → Open: village register · pregnancy register
3. The right form opens (ANC visit 1, rule)
4. Speak once → ANC rows filled with evidence → Sunita confirms
5. Danger check (⚙ rules): none found, with the rules that were checked
6. Ask the guide: IFA question + follow-up (+ online check)
7. Explain to Sarita's family (IFA, diet, next check-up) + read aloud
8. Save → Updated: ANC page, MCP p.3 + p.5, pregnancy register, stock IFA/calcium before → after, next due, diary, each with an Open button
9. Sarita's official MCP card (yellow changes)
10. Daily practice (3 questions)
11. AI tools + time saved

**W2 Newborn day 7: Baby of Neha (12 screens)**
1. Meet + plan
2. Card photo → vaccines → apply → Open MCP card
3. The right form opens (HBNC Day 7, rule)
4. Speak once → HBNC rows with evidence → confirm
5. Danger (⚙ rules on her words + form rules) → REFER → 108 / ANM
6. Escalate to the ANM: WhatsApp message (AI draft, editable) → "Not sent in demo"
7. Explain to the family why the baby goes to the PHC + read aloud
8. Ask the guide: cord care + follow-up + Open PDF (+ online)
9. Save → Updated: HBNC Day 7, MCP p.7 (baby + mother), growth chart, referral register, stock (mother's IFA/calcium), next due, each with Open
10. Official MCP card (yellow changes)
11. Daily practice
12. AI tools + time saved

**W3 Sick child: Rohan (11 screens)**
1. Meet + plan
2. Speak once → sick-child form with evidence → confirm
3. Danger (⚙ rules): no general danger sign → treat at home, follow up in 2 days
4. Ask the guide: ORS preparation + zinc for 14 days (+ follow-up)
5. Explain to Rohan's mother (ORS + zinc) + read aloud
6. Save → Updated: sick-child form, follow-up date, stock ORS 46 → 42, zinc 14 → 0 (OUT), each with Open
7. Refill request to the ANM (fixed template, exact balances) → WhatsApp
8. New stock: scan the zinc strip → +30 → Open stock register
9. Count ORS sachets with SAM 3.1 (online; offline by hand)
10. Daily practice
11. AI tools + time saved

## D. Work items (all in `Arogya*` files unless marked)

| # | Change | Files | Risk | Time |
|---|---|---|---|---|
| 1 | Script v2 (texts 1–2, the screen lists above, Ask questions per workflow) | ArogyaDemoScript | low | 1 h |
| 2 | Form fill that never comes back empty: relevant-row prompt, partial-reply rescue, rule backup for adult weight/BP/Hb/IFA/calcium/ORS/zinc, explicit "N of M" message | ArogyaFormSpecs (prompt/parse), ArogyaVisitAi.fillForm (small) | medium; affects the real visit flow too, for the better. Host tests | 2 h |
| 3 | Danger screen on fixed rules (`ArogyaDangerSigns.evaluate` + form rules), per workflow | ArogyaDemoTour | low | 45 min |
| 4 | "Updated records" panel with Open buttons (MCP card, form table, registers, stock) through the existing viewer | ArogyaDemoTour (+ existing ArogyaRegistryStore.tableDoc / mcpDoc) | medium (Compose) | 2 h |
| 5 | Ask SOP + explain + practice in all workflows (existing steps, question per workflow; explain without a second search) | ArogyaDemoTour | low–medium | 1.5 h |
| 6 | Slip review shows all fields + engine log; save gated on "Sunita confirms" | ArogyaDemoTour | low | 30 min |
| 7 | Refill text from `refillRequest` | ArogyaDemoTour | low | 15 min |
| 8 | Tests: form-fill rescue + rule backup, demo script v2, danger evaluation on the three notes | tools/host-tests | — | 1 h |

About 9 hours plus your build and phone test. Order: 2 → 3 → 1 → 6 → 4 → 5 → 7 → 8 (data correctness first, screens after).

## E. What I need from the phone
Run `_work\run\logs.bat` right after trying Workflow 1 and send `app_log.txt`. The lines that settle items 3–6:
- `AROGYA_FORM_FILL form=mcp_anc col=0 … filled=X/63`: how many rows survived;
- `AROGYA_DEMO_SAVED wf=1 …`: what was saved and the stock change;
- `AROGYA_DEMO_STEP_FAILED`: any step that threw;
- `AROGYA_ROUTE` / `AROGYA_TIMING`: model and time.
