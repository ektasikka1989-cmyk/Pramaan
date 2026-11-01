# M1 benchmark questions (19)

Committed 1 Nov 2026. Owner page-check 1 Nov 2026: Q1, Q2, Q3, Q4, Q5, Q6,
Q7, Q9, Q10, Q11, Q12, Q13, Q14, Q16, Q17, Q18 confirmed (Q4/Q7/Q11/Q14/Q16/Q18
corrected as marked); Q8 and Q19 to confirm in M1.
Run each question offline (airplane mode, Local first) and online (Cloud first),
via text input so runs are repeatable. Record per question: sources shown,
`POCKET_RAG_LOCAL_GATE reason=`, accepted / extractive / insufficient,
`KB_PHASE14_TRACE` best score, elapsed ms, answer chars. Human scores
completeness and correctness, 0–2 each. Results: `baseline.md`, `final.md`.

Expected-doc key (titles shortened; source_name in DB):
- incentives = ASHA Incentives under NHM (2025), `incentives.pdf`
- jsy_anc = JSY Incentive Clarification for ANC (2015), `jsy_anc.pdf`
- hbyc = Handbook for ASHA on Home Based Care for Young Child, `hbyc_en.pdf`
- idcf = Intensified Diarrhoea Control Fortnight Guidelines, `idcf.pdf`
- int_a/b/c = Integrated ASHA Training Modules A/B/C, `int_a.pdf` etc.
- asha_fac = Handbook for ASHA Facilitators, `asha_fac_en.pdf`
- imm = Immunization Handbook for Health Workers (2018), `immunization.pdf`
- amb = Anemia Mukt Bharat Guidelines, `amb.pdf`
- highrisk = Home Visits for High-Risk Babies, `highrisk_en.pdf`
- ncd_hi = ASHA NCD Training Module (Hindi), `47c15fa0edba7559a0773830a712894a.pdf`

## English (12)

1. FACT+LIST. "How many home visits should an ASHA make for a home-delivered
   newborn, and on which days?" Expected: incentives p2 (days 1,3,7,14,21,28,42;
   Rs 250) and/or asha_fac p7 (six/seven visits).
2. FACT. "What JSY incentive does an ASHA get on completing a pregnant woman's
   ANC check-ups in a rural area?" Expected: jsy_anc p1 (Rs 300 rural, Rs 200 urban).
3. DOSE. "What is the zinc dose for a 4-month-old baby with diarrhoea, and for
   how many days?" Expected: hbyc p72 (half tablet / 10 mg under 6 months) and
   idcf p48 (2–6 months 10 mg, 6 months–5 years 20 mg, 14 days).
4. PROCEDURE. "How should a mother prepare ORS at home for a child with
   diarrhoea?" Expected: hbyc p68–69 (ORS packet method p68, home-made
   ORS p69). Owner-confirmed.
5. FACT. "What should be applied to the umbilical cord stump of a newborn?"
   Expected: int_b p18 (nothing is to be applied).
6. PROCEDURE. "What should the ASHA check about breastfeeding during a home
   visit?" Expected: hbyc p37 (§4.1 Exclusive breastfeeding).
7. DANGER. "What are the danger signs in a newborn that need immediate
   referral?" Expected: int_b p18 (breathing danger signs) and asha_fac
   p58–61 (Annexure 9 sepsis signs). Owner-confirmed (int_b p56 has no list).
8. DANGER+PROCEDURE (demo question). "A 7-day-old baby has fever and is feeding
   less. What should the ASHA do, and what before reaching the hospital?"
   Expected: *to confirm in M1* (owner candidates: int_b p41 and p55).
9. LIST. "Which spacing contraceptive methods are available in the family
   planning programme?" Expected: hbyc p55–56 (§5.1.1).
10. FACT+TABLE. "Which vaccines are due at birth, and what is the BCG dose?"
    Expected: imm p33 (BCG/OPV/HepB table; BCG 0.05 ml until 1 month).
11. DOSE. "What is the weekly IFA dose for a school-going adolescent girl under
    Anaemia Mukt Bharat?" Expected: BOTH values with sources — amb p27
    (blue tablet, 100 mg iron + 0.5 mg folic acid, weekly) and amb p48
    (60 mg iron + 500 mcg folic acid). Owner-confirmed.
12. THIN COVERAGE. "A baby weighs 2,200 grams. How often should the ASHA visit?"
    Expected: highrisk p1 (daily for a week, then every 3 days till day 28,
    then day 42) or a graceful not-in-guides reply (single-chunk document).

## Hindi, Devanagari (5)

13. FACT (Hindi doc). "इस मॉड्यूल में कौन-कौन से गैर-संचारी रोग शामिल हैं?"
    (Which non-communicable diseases does this module cover?) Expected: ncd_hi
    p5 (hypertension, diabetes, three cancers).
14. DANGER (cross-lingual). "नवजात शिशु में खतरे के लक्षण क्या हैं?"
    (What are the danger signs in a newborn?) Expected: int_b p18 and
    asha_fac p58–61 (English passages). Owner-confirmed.
15. DOSE (cross-lingual). "दस्त में 4 महीने के बच्चे को जिंक की खुराक क्या है?"
    (What is the zinc dose for a 4-month-old with diarrhoea?) Expected: hbyc
    p72 / idcf p48.
16. PROCEDURE (cross-lingual). "घर पर ORS कैसे बनाएं?" (How to make ORS at
    home?) Expected: hbyc p68–69. Owner-confirmed.
17. FACT (cross-lingual). "गर्भवती महिला की ANC जांच कितनी बार होनी चाहिए?"
    (How many ANC check-ups should a pregnant woman have?) Expected:
    induction_en p68 / int_b p198. *Page to confirm in M1.*

## Hinglish (1)

18. PROCEDURE. "Baby ko diarrhoea ho to ORS kaise dena chahiye?"
    Expected: hbyc p68–69, answered in simple English (existing safety
    policy: Hinglish understood, answered in English). Owner-confirmed.

## Out of scope (1)

19. "How do I fix a leaking tap?" Expected after R8: "This is not in your
    official guides — ask your ANM" plus closest guide topics. Before R8:
    labelled general answer (record which).
