# Pramaan: ASHA workflow and new demo plan (4 Nov 2026)

Status: **PLAN FOR APPROVAL. Nothing is implemented yet.**
Order of this document: how an ASHA works → what the app does today → why the demo felt broken → the
new demo (3 complete workflows) → photos → changes to make.

---

## 1. How an ASHA works (the basis for everything below)

An ASHA (Accredited Social Health Activist) is a woman from the village, about one per 1,000 people. She
is the link between families and the health system. She works with:
- the **ANM** at the sub-centre, who examines, vaccinates, and fills the MCP card at the VHSND or the facility;
- the **AWW** at the Anganwadi, who handles nutrition and growth monitoring.

She does not diagnose. She **visits, checks fixed danger signs, counsels, gives drug-kit medicines,
refers, mobilises families to the ANM, and records**. She is paid by incentives per completed activity.

**Her working day:**

| When | What she does | Documents she uses |
|---|---|---|
| Morning | Works out who is due: newborns on HBNC days, pregnant women, children due vaccines, sick follow-ups. Puts high-risk first (LBW, SNCU discharge, high BP). | ASHA diary / village health register, due list from the ANM |
| Before leaving | Checks the drug kit | Drug-kit stock register |
| At each house | Greets, looks at the MCP card (the family keeps it), asks and observes, checks danger signs, counsels, gives medicines, refers if needed, sets the next date | MCP card, HBNC form (Annexure 9), HBYC form, guides (ASHA modules) |
| After the visit | Writes the form, updates registers, informs the ANM about danger signs | Forms, registers, phone call / WhatsApp to the ANM |
| Month end | Monthly summary, incentive claim, kit refill request, PHC meeting | Monthly report, claim form, stock register |

**Documents matrix**

| Document | Who fills it | Official source | In Pramaan | Updated by |
|---|---|---|---|---|
| Village health register (households, members) | ASHA | ASHA diary / line listing | Register → Village | Add person (photo, voice, CSV) |
| RCH lists: eligible couples, pregnant women, births, deaths | ASHA | RCH register | Register → ANC, Couples, Vitals | Add person, visit save, vital events |
| MCP card (identification, ANC, PNC, baby care, immunization, IFA, growth) | ANM (ASHA records home-visit findings) | MCP Card (MoHFW/MoWCD) | MCP card view / PDF | Visit save (linked), card photo read |
| HBNC home-visit form (days 1, 3, 7, 14, 21, 28, 42) | ASHA | Handbook for ASHA Facilitators, Annexure 9 | Form "Annexure 9" | Voice note → form |
| HBYC form (months 3, 6, 9, 12, 15) | ASHA | HBYC guidelines | Form "HBYC" | Voice note → form |
| Sick-child record (diarrhoea, fever, fast breathing) | ASHA | ASHA Module 7 | Form "Sick child" | Voice note → form |
| Drug-kit stock register | ASHA | Drug kit guidelines | Stock register | Visit save (medicines), give, receive (scan), count (SAM) |
| Due list / visit plan | ASHA + ANM | Schedules in the guides | Today's plan | Fixed rules, never AI |
| Monthly report + incentive claim | ASHA | State formats | Monthly report | Generated from the registers |

**Visit-type matrix** (when each visit is due comes from fixed rules)

| Visit type | When (rule) | She refers to | She records | Medicines she gives | App form | Registers updated |
|---|---|---|---|---|---|---|
| New pregnancy registration | Pregnancy test positive; register in the 1st trimester | Pregnancy test kit, MCP card issued by the ANM | Name, husband, age, LMP → EDD, phone | Pregnancy test kit; IFA/folic acid as per guide | Add person + MCP-ANC | Village, ANC list, stock, due list |
| ANC home visit | Monthly; ANC check-ups by the ANM at ≤12, 14–26, 28–36, >36 weeks | MCP card ANC page | Danger signs, IFA taken, birth preparedness, ANM's readings off the card | IFA 30, calcium 60 | MCP-ANC column | ANC list, MCP card p.5, stock, diary |
| HBNC newborn visit | Institutional birth: day 3, 7, 14, 21, 28, 42 (home birth also day 1) | MCP card, Annexure 9 | Baby: weight, temperature, feeding, cord, eyes, danger signs. Mother: meals, pads, temperature | Mother: IFA 180 / calcium (postpartum); ORS/paracetamol only if needed | Annexure 9 column | MCP card p.7, growth chart, vitals, stock, diary, HBNC incentive count |
| HBYC visit | 3, 6, 9, 12, 15 months | MCP card immunization + growth | Weight, feeding, vaccines due, development | IFA syrup, ORS (as per guide) | HBYC column | Immunization list, growth, stock |
| Sick-child visit | When called / found sick | Danger-sign rules | Diarrhoea / fever / breathing, danger signs | ORS + zinc 14 days; paracetamol; RDT/ACT for fever | Sick-child form | Stock (ORS, zinc), referral, diary |
| Family planning | Eligible-couple follow-up | Couple register | Method, satisfaction | Condoms, OCP, ECP | FP form | Couple register, stock |

What is **AI** and what is **fixed rule** (unchanged rule: AGENTS.md rule 5):
- **AI:** turning her spoken note into form values (with the evidence shown), reading card/slip photos, answering guide questions with a citation, drafting the family explanation and the ANM message.
- **Fixed rules:** due dates, danger signs, medicine stock arithmetic, incentives.
- She approves every AI value.

---

## 2. Why the demo felt broken: four root causes found in the code

| What you saw | Root cause (verified in the code) | Fix |
|---|---|---|
| Adding people from the 10-person register photo failed | The on-phone text reader (ML Kit) returns the page as one list of lines (`MLKitProcessor.kt:111`, blocks joined with "\n"). Table columns come out as separate blocks, with no blank line between people. `ArogyaExtractSplit.splitBlocks` splits on blank lines, finds one block, and asks the small model to pull 10 people out of a scrambled page. | **Row-aware reading:** use ML Kit's line positions to rebuild each table row ("Name · Age · Husband · House"), one person per row, then read each row. Until then, the demo uses a single-person slip. |
| MCP card not updated after the visit | The visit saves to the **HBNC form (Annexure 9)**. The MCP card's "Care of baby" and "Post-natal care" tables read **separate MCP-card forms** (`mcp_baby`, `mcp_pnc`), which the visit save never writes (`ArogyaFormSpecs.apply`, line ~636). Only the weight on the growth chart changes. | **Linked save:** the HBNC day 1/3/7/42 findings also fill the matching MCP-card column (7th Day ↔ Day 7, 6th Week ↔ Day 42), marked "recorded at ASHA home visit". |
| A new MCP card photo added nothing | The card reader only reads child vaccines and weights (`ArogyaAiExtras.cardPrompt`). The photo I made had BCG/OPV-0/Hep B, which the sample village already holds (seeded at day 3), so nothing was new. Its dates were also fixed to 4 Nov. | New card photo carrying entries the app does **not** have yet (the ANM's day-7 weight and the birth-dose vaccines), with the sample changed so those start empty. The photo is generated for the demo date. |
| State, district, date and other details blank on the final card | The header comes from the ASHA profile (`ArogyaDocPdf.header`), which loading the sample village never sets. Neha's sample record has no husband, age, mobile, address, RCH ID or LMP. | The sample village sets a full ASHA profile (state, district, block, sub-centre, ASHA name). The three hero families get complete identification. |
| No medicines issued, no inventory impact | The demo voice note mentions no medicine. The HBNC form has no medicine rows. The paths that do reduce stock (ANC: IFA/calcium; sick child: ORS/zinc; "Give to person") are not used by the demo. | Each workflow issues real medicines through those paths, and the demo shows the stock register **before → after**. |

---

## 3. The new demo: a working day in 3 complete workflows

**Shape:** *Meet Sunita → Morning plan → Workflow 1 → Workflow 2 → Workflow 3 → Evening: everything updated → Impact.*
About 18 screens, about 8–9 minutes with narration. Back / Next / Replay stay.

**Every screen has the same 4-part panel:**
1. **This screen:** one line on what this screen of the app does.
2. **Sunita says / does:** her words, highlighted as they are read out (the real voice note or photo).
3. **Pramaan maps:** each piece of her words → the field it fills, shown as a highlighted chip:
   "2650 grams → Weight (Annexure 9, Day 7) · MCP card p.7 · Growth chart".
4. **Updated:** ticks that appear one by one: ✓ HBNC form Day 7 · ✓ MCP card p.7 · ✓ Stock: IFA −30.

A **"Records" drawer** (bottom sheet) on the evening screen opens the real updated documents: MCP card,
form, village/ANC register, stock register, due list, each with today's changes in yellow.

### 3.0 Opening (2 screens)
| # | Screen | This screen | Sunita says / does | Pramaan maps / shows |
|---|---|---|---|---|
| 0a | Meet Sunita | Who the ASHA is and what slows her down | (narration) "Sunita is the ASHA of Rampur, 1,000 people, 12 forms, 4 registers, a drug kit, 300+ pages of guides." | Her profile: state, district, block, sub-centre (now filled) |
| 0b | Today's plan | Due list built from official schedules (rules, not AI) | Opens the app in the morning | 3 visits, high-risk first: **Baby of Neha, HBNC Day 7 (home birth)** · **Kavita Yadav, new pregnancy** · **Child Rohan, diarrhoea follow-up**. Stock check: zinc low. |

### 3.1 Workflow 1: new pregnancy, registration and first ANC home visit (Kavita Yadav)
| # | Screen | This screen | Sunita says / does | Pramaan maps → updates |
|---|---|---|---|---|
| 1a | Add person from a photo | Copies a person off a slip or register photo on the phone; Aadhaar is masked and never stored | Photographs Kavita's registration slip | Name, husband, age, mobile, house no., LMP → Village register ✓, Pregnant-women list ✓, EDD (rule) ✓, Aadhaar masked |
| 1b | The right form opens | Picks the official form and visit from her stage (rule) | — | MCP card ANC page, "1st visit (≤12 weeks)" |
| 1c | Speak once | Her note fills the form; every value shows the words it came from | "Kavita, 10 weeks pregnant. No bleeding, no headache, no fever. ANM checked at VHSND: weight 49 kg, BP 110/70, Hb 10.2. Gave 30 IFA and 60 calcium tablets. Next check-up at 14 weeks." | Weight, BP, Hb, danger signs = No, IFA 30, calcium 60 → MCP card p.5 ANC-1 ✓ |
| 1d | Save → everything updates | One save writes the form, card, stock, diary, due list | Approves | ✓ MCP card p.3 (identification) + p.5 ✓ ANC register ✓ Stock: IFA 100→70, calcium 100→40 ✓ Next due: ANC-2 + Td-1 ✓ Diary ✓ Incentive line "ANC registration" |

### 3.2 Workflow 2: HBNC day-7 newborn visit with a danger sign (Neha and her baby)
| # | Screen | This screen | Sunita says / does | Pramaan maps → updates |
|---|---|---|---|---|
| 2a | MCP card photo | Reads what the ANM wrote on the family's card (vaccines, weights) on the phone | Photographs the baby's MCP card | BCG, OPV-0, Hep B (dated by the ANM) → Immunization ✓ (new rows highlighted) |
| 2b | Speak once | Note → Annexure 9 Day 7 | "Baby of Neha, day 7. Weight 2650 grams. Temperature 38.4. Feeding less since morning, cry weak. Cord clean, eyes clean. Mother ate 4 meals, 3 pads today, temperature 37. Gave mother 30 IFA and 60 calcium tablets." | Each value with its evidence → Annexure 9 Day 7 |
| 2c | Danger sign | Fixed rules flag danger signs before any AI; refer today | — | Fever ≥37.5 °C + poor feeding → **Refer now**. 108 / ANM buttons. Referral recorded |
| 2d | Message to the ANM | AI draft from her words; she edits before sending | — | ANM message (names stay on the phone) |
| 2e | Explain to the family | Plain-Hindi explanation, only from the guide | — | Family explanation with the guide citation |
| 2f | Ask the guide | Cited answer from official guides, offline | "What should be applied on the cord?" | Answer + page + "Open PDF" |
| 2g | Save → card + registers | Linked save | Approves | ✓ Annexure 9 Day 7 ✓ **MCP card p.7 "7th Day" column** (weight, temperature, feeding, cord) ✓ Growth chart dot ✓ Referral in the register ✓ Stock: IFA −30, calcium −60 ✓ HBNC visits 3 of 7 ✓ Diary |

### 3.3 Workflow 3: sick child, medicines and inventory (Child Rohan, 14 months)
| # | Screen | This screen | Sunita says / does | Pramaan maps → updates |
|---|---|---|---|---|
| 3a | Speak once | Note → sick-child form | "Rohan, 14 months, loose stools 4 times since yesterday, drinking well, no blood, not lethargic, temperature 37.2. Gave 4 ORS packets and 14 zinc tablets. Follow up in 2 days." | Diarrhoea Yes, danger signs No, ORS 4, zinc 14 → Sick-child form |
| 3b | Save → stock | Medicines leave the kit the moment the visit is saved | Approves | ✓ Stock: ORS 20→16, **zinc 6→0 → refill needed** ✓ Follow-up due in 2 days |
| 3c | Kit refill request | Fixed template to the ANM with exact counts | Taps "Request refill" | Refill message: zinc 30, ORS 10 |
| 3d | New stock arrives: scan | Reads name, batch and expiry off a strip on the phone | Photographs the new zinc strip | Zinc +30, batch, expiry → Stock register (receive) ✓ |
| 3e | Count the kit (online) | Meta SAM 3.1 counts ORS sachets in a photo; offline she counts by hand | Photographs the ORS on a cloth | "6 ORS sachets" → she confirms → stock check ✓ |

### 3.4 Closing (3 screens)
| # | Screen | Shows |
|---|---|---|
| 4a | Evening: everything updated | The Records drawer: MCP cards (Kavita, Baby of Neha) with today's changes in yellow and a full header; Annexure 9; sick-child form; village and ANC registers; stock register before → after; due list for tomorrow; monthly report preview |
| 4b | AI tools behind the day | Existing tools table (phone / cloud, Meta models in blue) |
| 4c | Impact | Existing impact table (measured times; by-hand times labelled as estimates) |

The "Phone | Cloud" switch stays on every AI screen.

---

## 4. New photo set (synthetic, every person fictional)

All photos come from one generator (`tools/demo_samples/make_samples.py <out_dir> <demo-date>`), so
dates always match the day of the demo. Size is phone-photo-like, on paper/cloth backgrounds.

| # | Photo | Used in | Contents |
|---|---|---|---|
| 1 | `W1_kavita_registration_slip.jpg` | 1a | One person: Kavita Yadav, husband Ramesh Yadav, 23 y, mobile 90000 00xxx, H-051 Rampur, LMP (demo-date − 10 weeks), masked-format dummy Aadhaar |
| 2 | `W2_baby_of_neha_mcp_immunization.jpg` | 2a | MCP p.36: BCG, OPV-0, Hep B dated by the ANM; weight row "Day 3: 2.85 kg" |
| 3 | `W3_zinc_strip_new_stock.jpg` | 3d | Zinc 20 mg dispersible, batch, Mfg, Exp, "10 tablets" |
| 4 | `W3_ors_6_sachets_on_cloth.jpg` | 3e | Six separated ORS sachets (SAM count) |
| 5 | `extra_register_page_5_people.jpg` | optional | Ruled register page, 5 rows, large clear print. Only used after the row-aware reader is built and tested |
| 6 | `extra_ifa_strip.jpg` | optional | IFA strip for a second scan |

---

## 5. Changes to make (after approval)

| # | Change | Files (all `Arogya*`, no platform core) | Effort | Risk |
|---|---|---|---|---|
| C1 | **Linked save** HBNC → MCP card p.7 (baby + mother, day 1/3/7/42), with host tests | `ArogyaFormSpecs.kt` | 1.5 h | Low: new rows only, same save path |
| C2 | **Sample village**: ASHA profile; full identification for Neha, her baby, Kavita (added via photo) and Rohan's family; birth vaccines left for the card photo; zinc opening 6 | `ArogyaRegistry.kt`, `ArogyaFormSpecs.seedDemo` | 1 h | Low (sample only) |
| C3 | **Medicines in workflows**: IFA/calcium on ANC, ORS/zinc on sick child, mother's IFA/calcium in HBNC via "Give"; stock before→after card | `ArogyaDemoTour.kt` + existing save paths | 1.5 h | Low |
| C4 | **Demo rewrite**: 3 workflows, 4-part panel, mapping chips, Records drawer | `ArogyaDemoScript.kt`, `ArogyaDemoTour.kt` | 5–6 h | Medium: big Compose change, needs a build |
| C5 | **New photos** generator (section 4) | `tools/demo_samples/make_samples.py` | 1 h | None |
| C6 | **Row-aware register reading** (ML Kit line positions → rows) | `MLKitProcessor.kt` (small, platform) + `ArogyaExtractSplit.kt` | 2 h | Medium: touches the shared OCR. Kept behind a register-mode flag |

**Order:** C2 → C1 → C3 (data and saving correct first, tested on the host) → C5 → C4 → build on your PC → C6 if time remains.
**If time runs short, drop:** C6 (the demo uses the slip), then the Records drawer (open the existing register screens instead).
About 12 hours of work, then your phone test. That fits before 5 Nov 06:30 if approved soon.

---

## 6. Decisions needed from you

1. **MCP card from ASHA visits.** The MCP card's PNC/baby columns are officially the ANM's visits (day 1/3/7, 6th week). Should the ASHA's HBNC findings fill the matching column, labelled "recorded at ASHA home visit"? *(Recommended: yes for 1st/3rd/7th Day and 6th Week, with the label.)*
2. **State and district on the card.** Use a real state/district with fictional village and people (e.g. Uttar Pradesh / Sitapur, sub-centre "Rampur SC"), or fully fictional names?
3. **Demo length.** About 18 screens / 8–9 minutes for 3 workflows. Is that OK for the judges' video, or should Workflow 3 be shortened (skip the scan/count)?
4. **Row-aware register reading (C6).** Build it now (2 h, touches shared OCR), or after submission?
