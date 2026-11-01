# M1 baseline (Batch 0, device-tested 1 Nov 2026)

Code: pre-Batch-1 (save fix NOT in; V1 cloud fixes IN: transcribe multipart,
connection-test 900 tokens). Runners: `M1BenchmarkTest` (`-e mode offline|online`).
Raw: `docs/benchmark/device/selftest/m1_offline|online.{json,md}`.
Auto-pins below are RESCORED by hand (the test's doc hints used file stems like
`int_b` but sources show clean titles). Human completeness/correctness scoring
still open — full answer texts are in the JSON.

## Pins (expected document + page found)

| Q | offline | online | note |
|---|---|---|---|
| 1 visits | NO (int_b p14,30; says 5 visits, missing day 42) | NO (hbyc p1; but answer lists 7 visits — likely right, human check) | retrieval miss; answer quality differs by mode |
| 2 JSY | NO (induction p71) | YES (jsy cited) | |
| 3 zinc | YES (hbyc p72) | YES | |
| 4 ORS | YES (hbyc p68) | YES (hbyc p68, numbered steps) | |
| 5 cord | YES (int_b p18, correct) | YES (p17–18) | auto-scorer missed it (hint bug) |
| 6 breastfeed | NO (int_b p14; mentions "short videos" — grounding suspect) | NO (int_b p14 + hbnc p37) | human check needed |
| 7 danger | NO (int_b p61, not p18/asha_fac) | NO (int_b p61,132) | answer lists signs — is p61 equivalent? human check |
| 8 demo fever | YES (int_b p41, owner candidate) but answer leads with heater/KMC, referral unclear | YES (int_b p41 + hbnc p23, good answer) | offline answer correctness suspect |
| 9 contraception | YES (hbyc p55) | YES (p55–56) | |
| 10 vaccines | YES (imm p33) | YES (imm p33, answered LOCALLY — cloud skipped?) | |
| 11 IFA | PARTIAL (amb p48 only; see dose warning) | YES (p27 + p48) | |
| 12 high-risk | YES (highrisk p1, correct schedule) | YES | auto-scorer missed it (hyphen) |
| 13 NCD Hindi | NO (retrieved ANM/MPW module, non-ASHA doc) | NO (same) | R9 evidence: dilution by non-ASHA docs |
| 14 danger Hindi | NO (Journey Hindi, wrong) | NO (Journey Hindi) | cross-lingual retrieval miss |
| 15 zinc Hindi | YES (hbyc p72) | YES | cross-lingual works here |
| 16 ORS Hindi | NO (int_c p22 only) | YES (hbyc p68, both methods, Hindi answer) | |
| 17 ANC Hindi | NO (ASHA NCD p5, wrong) | NO (no sources — insufficient) | |
| 18 Hinglish | YES (hbyc p69, answered in English per policy) | YES (hbyc p69, local fallback) | online run fell back to LOCAL (719 s) |
| 19 oos tap | YES (honest "could not produce grounded answer") | YES (graceful decline citing p90 tap mention) | pre-R8 behavior is already safe |

**Rescored: offline 10/19, online 13/19** (test auto-score said 8/10 — hint bugs).

## Dose warning (Q11, clinical)

Offline answer gave school-going adolescent girls the **Pink 45 mg + 400 mcg**
tablet from amb p48 — but that row is for **5–9 years**; adolescents get Blue
(100 mg + 0.5 mg per p27; 60 mg + 500 mcg per p48 for 10–19 out-of-school).
Wrong-row grounding with a wrong dose for the asked group. Batch 2 (R2/R3/R7)
must address; human review of all dose answers before submission.

## Timing (per question, phone SM-S938B)

- Offline: 326–858 s (median ~440 s). Far too slow for field use and for M1
  iteration — R5 must find where the minutes go (retrieval/rerank/recovery?).
- Online: 26–377 s (median ~130 s); two questions fell back to local.
- Ask SOP online single shot (V1): 127 s.

## Gaps in this baseline

- Gate reason / best score: unavailable — same-UID logcat read is blocked on
  this phone and the ring buffer rolled during the long rounds. run_all.ps1 now
  captures continuous logcat per class (Batch 1+ rounds will have it).
- Human completeness/correctness (0–2) not yet scored — needs the JSON texts.
- Q8 offline, Q6 offline, Q7 (p61 vs expected), Q1 online: flagged above.
