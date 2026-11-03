# Pramaan gap analysis (4 Nov 2026, ~02:30 IST)

Scope: the whole repo at commit `2c4fc82` (215 Kotlin files, ~80k lines, 47 Arogya* files ~17k lines),
checked against the rules in AGENTS.md, plus build, data, privacy, clinical and submission readiness.
Method: reading the code and running all 47 host-test suites on Linux. Nothing was run on a phone;
items marked *phone* need the device to confirm.

Priority: **P0** = fix before submission (deadline 5 Nov, 06:30 IST). **P1** = fix if time allows,
or state it honestly in the deck/README. **P2** = after the hackathon.

---

## Summary

| # | Gap | Area | Priority | Effort |
|---|-----|------|----------|--------|
| 1 | DUI2 demo screens + DUI4 never compiled | Build | P0 | build time |
| 2 | No root README for judges (what it is, how to build, where models come from) | Submission | P0 | 30 min |
| 3 | `verified_answers.json` (the 100 verified answers) is gitignored, so a clean clone silently has no gold layer | Build / data | P0 | 5 min |
| 4 | Deck numbers on the phone and cloud are "to be measured" / "not measured" | Honesty | P0 | 1–2 h on phone |
| 5 | Demo SAM 3.1 count has no time limit (up to 90 s hang on weak signal) | Offline rule 3 | P0 | 5 min |
| 6 | Voice notes skip pregnancy danger signs (bleeding, headache/blurred vision, swelling, reduced fetal movement) | Clinical | P1 | 2–3 h |
| 7 | Shared PDFs and CSVs with patient names stay in public `Downloads/Pramaan`, unencrypted, and are never deleted | Privacy rule 4 | P1 | 1 h |
| 8 | Cloud voice sends raw audio (names included) to Meta; the "identifiers removed" rule only covers text | Privacy rule 9 | P1 (disclose) | 10 min text |
| 9 | 28 "To be checked manually" lists in the batch report are not ticked off | Honesty rule 8 | P1 | phone time |
| 10 | Source documents (`docs/forms/`, `docs/verified_qa/`) and the `MUSE_CODE_PROMPT_*` files are untracked on the owner's PC | Provenance | P1 | 5 min |
| 11 | No off-PC copy of the repo (no git remote) | Risk | P1 | 10 min |
| 12 | `allowBackup="true"` with the sample backup rules (commented out) | Privacy | P2 | 15 min |
| 13 | `MediaStore.Downloads` needs Android 10+, but minSdk is 24, so sharing quietly fails on Android 7–9 | Compat | P2 | 30 min |
| 14 | No danger-sign rules for children 2 months–5 years (pneumonia, dehydration) | Clinical scope | P2 | 1 day |
| 15 | Host tests aren't JUnit, so `gradlew test` doesn't run them; `run.sh` recompiles everything 47 times | Tooling | P2 | 2 h |

What is solid (checked, no gap found):
- Routing: every AI feature goes through `ArogyaVisitAi.route()`, with a 25 s cloud deadline, a cut-off
  when the network drops, and automatic fallback to the phone.
- Scrubbing: cloud prompts are scrubbed for notes, the ANM message, family explain, medicine scan,
  voice stock and finish visit. Card and person reading never go to the cloud (`cloud = false`).
- Keys: kept encrypted in AndroidKeyStore. No keys found in code, docs or logs.
- Branding: app name, debug label and package names follow the rules.
- Storage: visits and registry are encrypted (AES-GCM) in `noBackupFilesDir`.
- Crash scan: no `!!` in Arogya code, and the `.first()` / `.last()` calls checked are guarded.
- Host tests: 46 of 47 suites pass. GoldMatchTest fails only because of gap 3.

---

## Details

### 1. Uncompiled screen code (P0)
DUI2 (`ArogyaDemoTour.kt`, ~370 changed lines) and the DUI4 fixes have never gone through Gradle. I read
them against every function and class they use and found no errors, but Compose errors only show up in a
real build. **Do:** `_claude_work\run\build_install.bat`, check for `BUILD SUCCESSFUL` / `INSTALL=OK` /
`KB_PUSH=OK`, then run the whole demo once in English and once in Hindi.

### 2. No README (P0)
The repo root has only `AGENTS.md`, which is written for coding agents. A judge needs:
- what Pramaan does (3 lines) and which Meta models it uses;
- how to build it (`gradlew :app:assembleDebug`, arm64 only);
- which files are NOT in the repo and where they come from: `llama.gguf`, the Whisper `model.bin`, the
  E5/reranker ONNX files, the guide DB push, `verified_answers.json`;
- how to add a Meta key (Settings), and that everything works offline without one;
- the honest status of tested and not-yet-measured items (see gap 4).

### 3. `verified_answers.json` not in git (P0)
`.gitignore` excludes `app/src/main/assets/**`. Only `demo/`, `doc_templates/`, `guide/`, `growth/` and
`kb_parity.json` are allowed back in. `ArogyaVisitAi.warmUp` (line ~179) opens `verified_answers.json`
inside `runCatching`, so a clean build runs **without** the gold layer and nothing warns you, while the
deck talks about "100 verified questions". **Do:** add `!app/src/main/assets/verified_answers.json` to
`.gitignore` and commit the file. It is your own verified content, not a model weight.

### 4. Deck metrics not measured (P0)
In `docs/benchmark/final.md`, phone retrieval, phone answer time, phone citation accuracy, and cloud
accuracy, citation and latency all read "to be measured manually" or "not measured (no key)". Rule 8 says
don't claim what isn't tested. **Do:** run the 20-question M1 set on the phone in both modes, using the
log markers, and fill the table. If there's no time, the deck must show these as not measured.

### 5. Demo SAM call has no time limit (P0, 5 min)
`ArogyaDemoTour.kt` ~line 496 calls `MetaOnline.api(context).sam(...)` directly. `MetaModelApi` has a
90 s read timeout, so on weak signal the demo screen sits on "SAM 3.1 is counting…" for up to 90 s. The
kit screen in `ArogyaCloudUi.kt:324` already wraps the same call in
`ArogyaCloud.withDeadline(20_000, "sam_count")`. **Do:** use the same wrapper in the demo, and show
"SAM 3.1 did not answer — count by hand" when it returns null.

### 6. Pregnancy danger signs missing from voice notes (P1, clinical)
`ArogyaDangerSigns` covers the young infant (0–59 days) and the postnatal mother only. Pregnancy danger
signs are enforced only on the **structured** MCP-ANC form (`ArogyaFormSpecs.kt:158, 495–496`:
headache/blurred vision/fits/swelling, BP ≥140/90, Hb <7). A voice note such as "7 mahine ki pregnant,
bleeding ho rahi hai, sir dard aur dhundhla dikh raha" raises **no** alert in the visit flow. **Do (fixed
rule, not AI):** add `Subject.PREGNANT` and signs for bleeding in pregnancy, severe headache/blurred
vision, fits, swelling of face/hands, high fever, reduced or no fetal movement, and water breaking, with
the source page from the Integrated ASHA Module. Add host tests. If it isn't done before submission, say
in the README that voice danger alerts cover newborns and new mothers.

### 7. Patient PDFs left in public Downloads (P1)
`ArogyaRegistryStore.saveFile` writes every shared MCP card, register CSV and SOP PDF to
`Downloads/Pramaan` through MediaStore. Any app with media access, or anyone who opens the Files app,
can read them, and they are never cleaned up. That goes against "data lives on the phone (encrypted)".
**Do:** for *share*, write to `cacheDir/share/` and share through a FileProvider, deleting old files at
startup. Keep Downloads only for an explicit "Save to phone" action, with a one-line warning.

### 8. Cloud voice sends raw audio (P1, disclose)
`ArogyaCloud.transcribe` uploads the WAV file to Muse Voice Transcribe when the mode is Cloud first.
Audio can't be scrubbed, so names leave the phone. That is acceptable with consent, but it should be
stated: one line in Settings under Cloud first ("Voice notes are sent to Meta for transcription; text is
scrubbed, audio cannot be") and in the README. Also note the 15 s deadline: long notes usually fall back
to Whisper. Check on the phone whether cloud voice ever succeeds for a 40–60 s note.

### 9. Manual checks not ticked (P1)
`docs/batch_reports/final_submission.md` has 28 "To be checked manually" lists (UI2–UI6, DUI1–DUI4).
Do at least the demo path end to end and the Ask screen (Stop, Open PDF, citations), and record the
results in the report.

### 10. Untracked source documents (P1)
On the owner's PC these are untracked: `docs/forms/`, `docs/verified_qa/`, `CLAUDE.md`,
`docs/CLAUDE_CODE_HANDOFF.md`, `docs/MUSE_CODE_PROMPT_*.md`, `_claude_work/sources/`. HANDOVER §5 says the
official formats follow the source documents, so commit `docs/forms/` and `docs/verified_qa/` as
evidence (if the licence allows; government PDFs usually do). Don't commit `tools/host-tests/out-some/`
(build output); add it to `.gitignore`.

### 11. No off-PC copy (P1)
The only copy of the code is one Windows folder. A disk failure the night before the deadline would be
fatal. **Do:** create a private GitHub repo and push (`git remote add origin …`, `git push -u origin master`).

### 12. Backup rules (P2)
`allowBackup="true"` with the sample `backup_rules.xml` / `data_extraction_rules.xml` (everything is
commented out). The visit data is safe (`noBackupFilesDir`), and keys fail safely to "" after a restore.
Still, SharedPreferences (profile name and village, settings, demo prefs) go to Google backup. Either set
`allowBackup="false"` (Drive backup is handled by the app itself) or write real exclude rules.

### 13. Sharing on Android 7–9 (P2)
`MediaStore.Downloads` and `RELATIVE_PATH` need API 29+, while `minSdk = 24`. On older phones `saveFile`
returns null inside `runCatching`, so "Share" does nothing. Many ASHA phones are cheap and old. Fixing
gap 7 (FileProvider) fixes this too.

### 14. Child 2 months–5 years danger signs (P2)
Not covered by the rules (IMNCI general danger signs, fast breathing ≥50/40 per minute, dehydration).
Out of scope for the demo; mention it as future work.

### 15. Test tooling (P2)
`tools/host-tests/run.sh` recompiles all sources for each of 47 suites, which takes ~40 min. Compiling
the sources once into a jar takes ~5 min, but 5 tests use `internal` functions and must still be
compiled with the sources. Long term, move the tests to `app/src/test` so `gradlew test` runs them.

---

## Suggested order (about 28 h left)

1. Build and install (gap 1). Fix any compile errors first.
2. Gaps 3 and 5 (10 min of code), then rebuild.
3. Push to a private GitHub repo (gap 11), and commit docs/forms + verified_qa (gap 10).
4. Measure on the phone: M1 offline + online, demo run-through (gaps 4, 9).
5. README with honest status (gap 2) and the voice-audio disclosure (gap 8).
6. If time remains: pregnancy danger signs (gap 6), then the share-via-cache fix (gap 7).
