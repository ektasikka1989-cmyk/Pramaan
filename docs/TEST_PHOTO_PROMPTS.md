# Test photos for Pramaan (image-generation prompts)

## How to use
1. Paste the **Common instructions** first, then **one** prompt at a time into Gemini (image generation).
2. **Check every number and name in the image against the prompt.** Image models often get small text wrong. If any value is wrong or unreadable, ask Gemini: "Regenerate; the text must read exactly: …".
3. Save each image with the file name shown, copy it to the phone (`Pictures/Demo_samples`), and use it on the matching demo screen.
4. All people are fictional. Don't ask Gemini for Aadhaar cards, government emblems or real brand logos.

## Dates (they must match the day you test or record)
The sample village is rebuilt each time the demo starts, so the dates depend on that day.

| Value | Rule | If you test on **4 Nov 2026** | If you test on **5 Nov 2026** |
|---|---|---|---|
| Sarita's LMP | test day − 10 weeks | **26/08/2026** | **27/08/2026** |
| Baby of Neha, date of birth | test day − 6 days | **29/10/2026** | **30/10/2026** |
| Birth vaccines given by the ANM | birth + 2 days | **31/10/2026** | **01/11/2026** |
| ANM weight record | same day as the vaccines | **31/10/2026 – 2.85 kg** | **01/11/2026 – 2.85 kg** |

Below, replace `[LMP]`, `[DOB]` and `[VAX]` with the values for your test day.

---

## Common instructions (paste first, every time)
```
Create a photorealistic smartphone photo, taken from above at a slight angle, in natural indoor daylight, of a real paper
document or medicine pack lying on a plain surface in a village health worker's home in India. Requirements:
- All printed and handwritten text must be sharp, large and fully legible; spell every word and number EXACTLY as given.
- Realistic paper texture, slight shadows, a little perspective, no blur over the text, nothing covering the text.
- No watermarks, no logos of real brands, no government emblems, no faces, no hands covering text.
- Plain background (wooden table, cotton cloth or a steel tray). Image size about 2000 px on the long side.
```

---

## Workflow 1: new pregnancy (Sarita)

### W1-A `W1_sarita_registration_slip.jpg` (main demo photo)
```
A printed A5 pregnancy registration slip from a village sub-centre, filled in by hand with blue ballpoint pen.
Printed title at top: "PREGNANCY REGISTRATION — VILLAGE RAMPUR". Below it, printed: "Sub-centre: Rampur SC    ASHA: Sunita Devi".
Then a ruled table with ONE header row (printed, bold) and ONE row filled by hand. Columns, left to right:
Name | Husband | Age | Mobile | House No. | Village | Category | LMP
Handwritten values in that row (neat block letters):
Sarita Yadav | Ramesh Yadav | 23 | 9000000301 | H-051 | Rampur | Pregnant | [LMP]
Below the table, printed: "Aadhaar No.:" with handwritten "0000 1111 2222".
At the bottom, printed: "Family consent taken: Yes      Signature: ________".
Clean white paper, slightly curled corner, lying on a wooden table.
```

### W1-B `W1_sarita_slip_label_style.jpg` (robustness test: a different format)
```
A handwritten page from a small ruled notebook (not a table), in blue ink, neat handwriting, one detail per line:
Name: Sarita Yadav
Husband's name: Ramesh Yadav
Age: 23 yrs
Contact no.: 9000000301
House: H-051
Village: Rampur
Category: Pregnant
LMP: [LMP]
Blood group: B+
Aadhaar: 0000 1111 2222
Notebook lying on a cotton cloth.
```
Expected in the app: all details read; "Blood group: B+" shown as **not understood, kept as notes**; Aadhaar shown as `XXXX XXXX 2222`.

---

## Workflow 2: newborn day 7 (Baby of Neha)

### W2-A `W2_baby_of_neha_mcp_card.jpg` (main demo photo)
```
A photo of an open Indian Mother and Child Protection (MCP) card booklet, showing its child immunization page.
Light purple and pink printed booklet style (no emblem, no logo). Printed heading: "Immunization (Child)".
Printed fields at top, with handwritten blue-ink values:
Child's name: Baby of Neha      Mother's name: Neha
Date of birth: [DOB]            Birth weight: 2.8 kg      Place of birth: Home
Below, a printed table with columns: Age | Vaccine | Date given
Rows (printed age and vaccine; handwritten date only in the first three rows):
Birth | OPV-0 | [VAX]
Birth | Hep B | [VAX]
Birth | BCG | [VAX]
1½ months | OPV-1 | (empty)
1½ months | Penta-1 | (empty)
1½ months | Rota-1 | (empty)
1½ months | PCV-1 | (empty)
1½ months | IPV-1 | (empty)
At the bottom, printed "Weight record:" with handwritten "[VAX] – 2.85 kg".
Booklet lying open on a steel tray, page flat and fully visible.
```
Expected: 3 vaccines read **with the date [VAX]**; applying them adds them to the card.

---

## Workflow 3: sick child and drug kit

### W3-A `W3_zinc_new_stock.jpg` (medicine scan)
```
A close photo of one blister strip of dispersible zinc tablets lying on a white cotton cloth. Silver foil back side up,
10 tablet pockets visible, with clearly printed text on the foil (dark green ink):
"ZINC SULPHATE DISPERSIBLE TABLETS IP 20 mg"
"Batch No. ZN2611A"
"Mfg. 08/2026"
"Exp. 07/2028"
"10 Tablets"
"Govt. supply — Not for sale"
Next to the strip, a small white carton printed: "Zinc Sulphate Dispersible Tablets IP 20 mg — 3 x 10 Tablets — 30 Tablets".
```
Expected: name → kit item **Zinc tablets**, batch **ZN2611A**, expiry **07/2028**, quantity **30**.

### W3-B `W3_ors_6_sachets.jpg` (SAM 3.1 count, online)
```
Six separate ORS sachets laid out flat on a plain white cotton cloth, not touching each other, in two rows of three.
Each sachet is a white and green rectangle printed "ORS — Oral Rehydration Salts IP — WHO formula — 20.5 g — Mix in 1 litre
of clean water". Taken from directly above, even light, all six sachets fully visible.
```
Expected (online, Meta key set): SAM 3.1 counts **6**.

---

## Extra tests (other real-life cases)

### X-1 `X_register_page_8_people.jpg` (Registers → Add person → photo)
```
A page of a hand-ruled village household register in an exercise book, filled by hand in blue ink.
Printed or hand-written header row: S.No | Name | Husband/Father | Age | Sex | Category | House No. | Mobile
Eight filled rows:
1 | Pooja Kumari | Sanjay Kumar | 22 | F | Eligible couple | H-052 | 9000000302
2 | Aarav Kumar | Pooja Kumari | 2 | M | Child | H-052 | —
3 | Meena Singh | Raju Singh | 31 | F | Mother | H-053 | 9000000304
4 | Anjali Verma | Deepak Verma | 26 | F | Pregnant | H-054 | 9000000305
5 | Sushila Devi | Ram Prasad | 58 | F | Other | H-055 | 9000000306
6 | Geeta Rani | Ram Prasad | 16 | F | Other | H-055 | —
7 | Lata Kumari | Vijay Pal | 29 | F | Eligible couple | H-056 | 9000000308
8 | Rahul Pal | Lata Kumari | 4 | M | Child | H-056 | —
Exercise book lying flat on a wooden table.
```
Expected: **8 people** in the review list.

### X-2 `X_ifa_strip.jpg` (scan: must map to IFA, not zinc)
```
A blister strip of iron and folic acid tablets (small red tablets visible through clear pockets) on a white cloth.
Printed on the foil in red ink: "IRON & FOLIC ACID TABLETS IP (IFA)", "Batch No. IFA2610C", "Mfg. 04/2026", "Exp. 03/2028",
"10 Tablets", "Govt. supply — Not for sale".
```
Expected: kit item **IFA tablets (adult)**, batch **IFA2610C**, quantity **10**.

### X-3 `X_paracetamol_strip.jpg` (scan: "B. No." batch format)
```
A blister strip of white paracetamol tablets on a steel tray. Printed on the foil in blue ink: "PARACETAMOL TABLETS IP 500 mg",
"B. No. PCM2611B", "Exp. 05/2028", "10 Tablets".
```
Expected: kit item **Paracetamol**, batch **PCM2611B**.

### X-4 `X_blurry_slip.jpg` (negative test: the app must say what it could not read)
```
The same pregnancy registration slip as W1-A, but photographed quickly: slightly blurred, tilted about 20 degrees, with a
shadow over the right third of the page.
```
Expected: the fields it can read are filled; the rest are listed under **"Not read from the photo"**. Nothing is invented.

---

## What to send me after testing
For each photo: the screen it was used on, what the app showed (a screenshot), and `app_log.txt` from
`_work\run\logs.bat`. The lines `AROGYA_PERSON_READ`, `AROGYA_REGISTER_ROWS`, `AROGYA_CARD_TEXT`, `AROGYA_MEDSCAN` and `AROGYA_SAM_COUNT`
show exactly what the phone read.
