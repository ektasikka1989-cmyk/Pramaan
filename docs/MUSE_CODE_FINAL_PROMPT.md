# Final implementation prompt for Muse Code — ArogyaDoot (single pass)

Paste everything below the line into Muse Code.

---

You have my approval to **implement all of the following now, in one continuous pass**, not spread over
days. Work in the batches below, in order. After each batch, build and install, read the device logs and
host tests, and report the evidence briefly before continuing. Stop and ask only if something would break
working features, user data or the native Llama/Whisper/OCR, or if a decision isn't covered here.

**Read first:**
- `AGENTS.md`
- `docs/HANDOVER.md`
- `docs/NEXT_CHANGES.md` (item IDs used below)
- your two audit reports (the read-only audit, and the review of `NEXT_CHANGES.md`)

**Deadline:** 5 Nov 2026, 6:30 AM IST. **Priority: offline first.**

## Non-negotiable rules (from AGENTS.md)
- **Audit before you add.** Reuse the platform's code (`MetaModelApi`, `MetaOnline`, `PrivacyGate`,
  `GroqKeyStore`, `AnswerPolicy`, RAG, Whisper, OCR, llama.cpp). Don't duplicate it.
- **Keep the working parts working:** RAG, on-device Llama, Whisper, OCR, attachments and the one-setting
  routing.
- **Touch platform ("Pramaan") files as little as possible.** Keep edits pack-neutral. Guard them with
  `KbPhase14RegressionTest`, a Pramaan-chat smoke test and the M1 benchmark.
- **Offline must always work.** The single **Local first / Cloud first** setting controls every AI action,
  with time limits and automatic phone fallback. New AI actions go through `ArogyaVisitAi.route()`.
- **Danger signs, schedules and incentives** are fixed rules, never AI. Label them on screen and in the
  README as "from official MoHFW guidelines; clinical review pending".
- **AI output is grounded and approved by the ASHA:** evidence must be in her words or the document,
  and numbers must appear in that evidence.
- **No government backend.** Keep data formats compatible: don't change the prefs names, the registry or
  visit storage formats, the package name, or the native code.
- **"ArogyaDoot"** everywhere a user or judge sees, spelled exactly like that.
- **Honest status for everything:** say "compiled", "host-tested", "device-tested" or "not verified". The
  demo drives real functions; nothing is faked, and anything not live is labelled honestly.
- No API keys in code, logs or chat. Don't delete build caches. Keep the old guide database file as a
  fallback.

Owner decisions already made:
- live Meta calls are approved (the key is saved on the phone);
- the drop list below is approved;
- the demo is **English-led with a Hindi option**;
- the app language **follows the phone's language**, with a toggle;
- no clinical sign-off before the deadline, so use the honest label above.

---

## Batch 0 — Baseline (measure before changing)
1. **V1, live Meta test** (Cloud first, key on the phone):
   - connection test;
   - Muse Spark form filling;
   - Muse Voice Transcribe in English, Hindi and Hinglish;
   - an Ask SOP answer;
   - SAM 3.1 count. Confirm whether a base64 data URL is accepted; if not, switch to a Files API upload.
   - Glimmer only if a Together key exists;
   - airplane-mode fallback for each.

   Fix whatever the real API returns (model id, auth, request and response shape).
2. **M1, benchmark:**
   - Commit your 19 questions to `docs/benchmark/questions.md` (12 English, 5 Hindi, 1 Hinglish,
     1 out-of-scope), with the expected document and page. I will check the pages.
   - Add a simple runner, or written steps, that records per question:
     - sources shown;
     - `POCKET_RAG_LOCAL_GATE reason`;
     - accepted, extractive or insufficient;
     - best score;
     - time taken;
     - answer length.
   - Human scores: completeness and correctness, 0–2 each.
   - Run offline (airplane mode, Local first) and online (Cloud first). Save to `docs/benchmark/baseline.md`.
3. **V2, visit flow on the device:**
   - Baby of Ritu, Day 14; a pregnant woman's ANC column;
   - save, save again, reopen, PDF;
   - check the MCP card, drug kit, diary, visit list and schedule.

## Batch 1 — Bugs that break the app or the demo (fix first)
4. **The two-step save (D1+D2).** `store.update` succeeds, then `ArogyaVisitQueue.save` probably rejects
   the `ArogyaOfficialForms.visitRecord` visit, because the old checklist validation needs answers and
   has its own age windows.
   - Make the save consistent: validate first, then write once, or write both together, or a clean
     carve-out for official-form visits.
   - The visit list, schedule and registers must all update, or none of them.
   - Same fix in `ArogyaDemoTour` scene 9.
5. **Re-save double counting (D3).** Saving the same column again must not:
   - add the IFA again (set the value, don't `+=`);
   - append the stock again (insert-or-replace per form column);
   - create a second visit (stable visit id per person, form and column).
6. **Rotation loss (D6):** use `rememberSaveable` for the guided visit state, the hub tabs and dialogs, and
   `showArogyaVisits`.
7. **Ask SOP time limit and fallback (D7):** in Cloud first, phone fallback if the cloud is slow. An
   offline answer that stalls gets a clear message, never an endless spinner.
8. **Branding:** fix "Arogyadoot" → "ArogyaDoot" in `MainActivity.kt` (about 10 places: chat header,
   toasts, PDF folder) and sweep every user-visible string, including notifications and PDFs.

Add host tests for the save and idempotency rules. Build, install, re-run V2.

## Batch 2 — Offline answer quality (M1 before and after each step)
9. **R2, length instruction.**
   - Rewrite `COMPLETE_ANSWER`, keeping it as the last line, so it matches the question type:
     - **procedure:** all numbered steps;
     - **list:** every item;
     - **fact:** short, with its conditions;
     - **urgent or danger:** action first.
   - Remove the "three to six sentences" contradiction.
   - Check `MAX_ANSWER_TOKENS_PER_PART=400` against procedure answers, and raise it carefully if M1 shows
     answers are cut off.
10. **R1-lite, wider evidence:** 6 passages × ~1,100 characters, with paragraph- and step-aware cuts,
    within the 6,144-token context. Measure the prompt time on the phone.
11. **R9, guide database.**
    - In a new database file (keep the old one), lower the priority of the non-ASHA documents:
      - NPCDCS medical-officer module;
      - staff-nurse module;
      - MPW material;
      - RKSK peer-educator books;
      - untied-funds draft.
    - Add the 23 missing official URLs where you can find them.
    - Don't switch on `arogya_chunk_review.exclude_guided`.
    - Don't re-split passages. Push the new database with `KB_PUSH` and re-run M1.
12. **R7, source line.** Every answer ends with "Source: [document title], page N". Show the supporting
    sentence highlighted (the quote and highlights already exist) and an "Open document" button:
    - online: the official URL;
    - offline: R6-lite, an in-app "read the page" sheet with the passage text from the database, the
      sentence highlighted, and the document name and page.
13. **R8, guideline-only mode for ArogyaDoot Ask SOP**, on by default, in Arogya files only.
    - Recognise these answers by the `kbnotfound:` and `freshness:` markers (not `verification:optional`):
      the live web answer, the general answer and the "Check online" escape to the web.
    - Replace them with: "This is not in your official guides — ask your ANM", the closest guide topics
      and an "Ask ANM" button.
    - "Check online" may only correct an answer against the same passages.
14. **R3-lite, only if the M1 reject rate is high:** remove just the unsupported sentence instead of
    dropping the answer. Show copied sentences as "Key points from the guide (page N)", not as an answer.
15. **R4/R5:** measure only. Keep the Hinglish → simple-English safety policy (`KbReplyText`). Confirm
    that warm-up, streaming and KV reuse work, and fix them only if they don't.

## Batch 3 — Cloud first
16. **R10-lite:** Cloud first uses the wider evidence, at least the "Check online" wide set
    (12 × 1,600). If the wide packet fails, fall back to the narrow one.
17. **R11:** Muse Spark `reasoning_effort` "low" for knowledge-base answers. Raise `max_tokens` only if
    M1 shows answers cut off.
18. **R12:** confirm the same checks apply to cloud answers, and that the phone takes over on rejection
    or timeout.

## Batch 4 — Languages: Hindi, English, Hinglish
19. **App language** follows the phone setting, with the हिन्दी/English toggle. Finish the Hindi strings
    for Home, Ask SOP, the visit flow, forms, registers labels, the cloud/phone window and the whole demo.
20. **Ask SOP answers:**
    - Hindi question (Devanagari) → Hindi answer;
    - English → English;
    - Hinglish (Roman Hindi) → understood, answered in simple English (existing safety policy);
    - the source line is always shown.
21. **Voice input (S1/S2):**
    - Add a **Hindi / English / Auto (Hinglish)** choice in the dictation dialog and the visit voice
      button, remembered per user.
    - Pass it to Whisper (language parameter) and to Muse Voice Transcribe (`languageBias`, an additive
      parameter in `MetaModelApi`).
    - Hinglish voice notes must still fill forms correctly; test with M1-style notes.
22. **Reading aloud (S3):**
    - Choose the best installed natural voice for hi-IN and en-IN, offline first, and the online voice
      with Cloud first and signal.
    - Rate 0.9.
    - If a language's voice isn't installed, show a one-tap prompt to download it.
    - Label it honestly as the Android voice (Meta has no text-to-speech).

## Batch 5 — Demo redesign (`ArogyaDemoTour.kt`)
23. **D1, start screen:**
    - language choice (English default, हिन्दी);
    - banner: "DEMO · sample village (fictional people) · every step is done live by the app";
    - the current mode (Cloud first / Local first, online / offline);
    - Start.
24. **D2-lite:** a "LIVE" badge while each step runs, then "✓ done by app in X s". Show the real result
    (the real form grid, MCP card, register numbers) with deep links into the real screens. No fragile
    auto-navigation.
25. **D3, AI panels.**
    - A persistent "✨ AI at work" panel that ticks each AI feature live, with the actual model and time:
      - speech-to-text
      - form filling
      - evidence check
      - guide search + reranking
      - grounded answer
      - ANM message
      - card text reading + extraction
      - SAM 3.1
      - reading aloud
    - A "Rules, not AI" panel: danger signs, schedules, incentives.
    - A closing summary.
26. **D4, SOP scene:**
    - use an M1-confirmed question;
    - show the passages found (document + page, sentence highlighted);
    - then the answer with "Source: …, page N";
    - then open the page (R6-lite sheet offline, URL online).
27. **D5/S4, voice note from a real recording.**
    - Play the owner's recording (Hindi or English, per the chosen language) from `assets/demo/` and feed
      its PCM straight into the real speech-to-text (Whisper offline, Muse online).
    - Show the transcript as it arrives.
    - If the recording files are missing, use the typed note, clearly labelled "typed note (no recording)".
28. **Honesty fixes from your first audit:**
    - scene 1 computes its numbers from the sample data;
    - scene 7 buttons are enabled but go to a "Demo: not sent" confirmation;
    - the referral is recorded only after the save;
    - the closing model list comes from the actual run.
29. **D6/D7:**
    - one-line titles (3–4 words) and one-sentence captions, tested on the phone at normal font size;
    - a visible timer; about 3:00 with Cloud first;
    - each scene waits for the real result, then holds 3–4 s;
    - Pause, Next and narration on/off;
    - narration uses S3 voices in the chosen language.
30. **D8:** an optional SAM 3.1 drug-kit scene using a bundled sample photo, only if V1 shows SAM works.
    Never fake it.
31. **D9, closing:** the problem solved, the offline/online table and the Meta models used. A time-saved
    figure only if it's measured.
32. **D10:** the demo must complete in airplane mode (Whisper, Llama, ML Kit), with honest, longer timing.

## Batch 6 — UI polish (no navigation restructure)
33. **U1/U8:** fix text breaking onto two lines everywhere; short labels, consistent label styles, long
    text cut off on tiles and chips, one-line chips.
34. **U2:** Home keeps the header, the 4 tiles with counts and the demo button. Backup and Practice move
    into a "More" menu; ✨ AI stays visible.
35. **U3:** visit and form screens get bigger row text, hints on tap, roomier Yes/No chips and a sticky
    bottom button.
36. **U4:** one colour each for AI (✨), danger (red) and rules; one card style and spacing (time-boxed).
37. **U7:** red danger banner on Home for referrals not followed up; "Next visits this week" strip;
    a 3-slide welcome screen if time allows.

## Batch 7 — Final verification and submission
38. Full M1 re-run in both modes → `docs/benchmark/final.md`, compared with the baseline.
39. V3 full demo run, online and offline. Re-run all host tests, plus the new ones.
40. Record two demo videos (online and offline) and take screenshots.
41. Write `README.md` for judges:
    - the problem;
    - features;
    - architecture;
    - an offline/online table;
    - Meta tools used (Muse Spark, Muse Voice Transcribe, SAM 3.1, Muse Glimmer, Muse Code);
    - safety design;
    - honest limitations.
42. Update `docs/HANDOVER.md` §7–8 with the true verification status. Delete build caches **last**, only
    after the final build and videos.

## Deferred, don't do now
- full PDF viewer (R6-full);
- full checker rework (R3-full) unless M1 demands it;
- navigation restructure (U6);
- Whisper model swap;
- re-splitting the guide database;
- a second demo language track beyond the Hindi option;
- evidence persistence on reopen (D5).

## Things I'll provide
- Benchmark page check (as soon as you commit the questions).
- Two voice-note recordings (Hindi and English, about 20 s each), in `assets/demo/`.
- An optional ORS sachet photo.
- The answer from Discord on whether on-device Llama is eligible.

**Report format after each batch:**
- what changed (files);
- build and install result;
- key log lines;
- host-test results;
- M1 numbers (where relevant);
- anything not verified.

Begin with Batch 0.
