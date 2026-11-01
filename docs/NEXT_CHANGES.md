# ArogyaDoot — Proposed changes for review (31 Oct 2026, 21:30 IST)

This is everything proposed in the last review sessions, in one list. **Nothing here is implemented yet.**
Each item needs a view from Muse Code, and approval from the owner, before any code changes.
Deadline: **5 Nov 2026, 6:30 AM IST**.

**Priority: offline first.** The app must give complete, correct, sourced answers and do the whole visit
with no internet. The cloud is an improvement on top of that.

---

## 0. Measure before changing anything

**M1. Answer-quality benchmark.**
- 15–20 real ASHA questions, English and Hindi mixed. Each has the correct document and page from the guide
  database (`ASHA_Health_Guide.db`, tables `procurement_chunks` and `knowledge_sources`).
- Mix of question types: facts, procedures/steps, lists, danger signs, doses, incentives.
- Run every question **offline (Local first, airplane mode)** and **online (Cloud first)**.
- Record from the logs:
  - right document and page found;
  - checker decision (`POCKET_RAG_LOCAL_GATE reason=…`);
  - accepted or recovered, or fell back to copied text (`POCKET_RAG_EXTRACTIVE_ANSWER`) or "couldn't answer";
  - time taken;
  - answer length.
- A human scores completeness (all key points from the page present?) and correctness.
- Keep the question list and results in the repo, so every change below can be compared before and after.

**M2. Device logs for one normal day of use.** Ask SOP ×5, visit ×2, MCP card read, voice ×3. This confirms
real latencies and failure points offline.

---

## 1. Knowledge-base answers (RAG + phone model + cloud)

The audit findings these are based on (all from the code, still to confirm with M1):
- **The phone model reads only the top 4–5 passages, each cut to about 900 characters around one sentence.**
  This comes from `GenericKbKeyFacts.WINDOW_PASSAGES=4`, `WINDOW_CHARS=900` and
  `GenericKnowledgeContextProvider.KEY_FACT_PASSAGES=5`. Procedures and conditions on the rest of the page are lost.
- **Cloud first sends Muse Spark the same phone-sized excerpt.** Only "Check online" uses the wide set
  (`ONLINE_PASSAGES=12`, `ONLINE_WINDOW_CHARS=1600`).
- **The prompt contradicts itself.** `PocketRagPromptBuilder.LENGTH` says up to ~250 words for procedures, but
  the last line, `COMPLETE_ANSWER`, says "three to six sentences or a short list". The model follows the last line.
- **Cloud knowledge-base calls run with minimal reasoning.** They use `reasoning_effort` "none" (mapped to
  "minimal") and `max_tokens` 512–1,280 (`GroqChatProvider`).
- **The checker (`PocketRagQualityGate`) was tuned for small models.** It may reject good synthesised or
  paraphrased answers, which then become trimmed text or about 700 characters of copied sentences
  (`PocketRagEngine.extractiveAnswer`).
- **Some guide documents dilute the search.** These include modules for medical officers (NPCDCS) and staff
  nurses, an untied-funds draft and adolescent peer-educator books (about 440 passages). About 20 documents
  have no URL, and about 7% of passages start mid-sentence.
- **Outside the guides, answers currently leave the guides:**
  - Cloud first runs a labelled live web answer (`kbLiveWebAnswer`).
  - Either mode gives a labelled general answer (`kbGeneralAnswer`).
  - "Check online" may add newer information.

### Offline (highest priority)

**R1. Wider evidence for the phone model.**
- Read 6–8 passages instead of 4–5, with windows of about 1,200–1,500 characters that keep whole
  paragraphs, numbered steps and table rows together.
- Stay inside the 6,144-token context of Llama 3.2 3B on this phone (`LlamaCppCore.contextTokensFor`).
- Measure the extra prompt-processing time on the phone's CPU; reading more takes longer.

**R2. Fix the length instruction.**
- Remove the "three to six sentences" contradiction.
- Make length depend on the question type:
  - **procedure:** numbered steps, all of them;
  - **list:** every item;
  - **fact:** short, with its conditions;
  - **danger or urgent:** action first.

**R3. A fairer checker and better fallback.**
- Accept answers that combine several passages when each fact is backed by its cited passage.
- Prefer removing only the unsupported sentence over discarding the whole answer.
- Copied-sentence answers only as a last resort, and shown as "Key points from the guide" with the page,
  not as an answer.

**R4. Hindi offline.**
- Llama 3B is weak in Hindi. Check its Hindi answer quality in M1.
- Options: answer in the question's language from English passages (current), make sure Hindi passages
  are retrieved for Hindi questions, or answer in simple Hindi with the English guide sentence quoted.
- Muse Code should recommend which.

**R5. Offline speed.**
- Keep the model warm (warm-up already runs at app open).
- Check that answers stream on screen.
- Check reuse of the KV cache between follow-up questions.
- Target: first words in under 10 s on this phone.

**R6. Open the exact page offline.**
- Store the guide PDFs on the phone (download once with the guide database, or include them).
- Open them inside the app with Android `PdfRenderer` at the cited page, with the sentence highlighted.
- When online, the official URL works too (page jumps are not reliable in every viewer).

**R7. Clear source line.**
- Every answer ends with "Source: [document title], page N", shows the supporting sentence highlighted,
  and has a "Open document" button.

**R8. Guideline-only mode for ArogyaDoot Ask SOP, on by default.**
- No web answers and no general answers.
- If the guides don't cover a question: "This is not in your official guides — ask your ANM", plus the
  closest guide topics and an "Ask ANM" button.
- "Check online" may only correct an answer against the same passages.
- Prefer doing this in `ArogyaVisitAi.askPramaan` (recognise those answer types) rather than changing
  Pramaan's engine. Muse Code should advise.

**R9. Clean the guide database.**
- Remove or down-rank documents that aren't for ASHAs.
- Add the missing official URLs.
- Re-split mid-sentence passages at paragraph boundaries.
- Check that the Hindi documents are indexed properly.
- Rebuild the embeddings if passages change, and re-run M1.

### Online (cloud first)

**R10. Cloud first gets wide evidence:** use the "Check online" evidence size (12 × 1,600 characters) or more.
Muse Spark can read up to about 1 million tokens.

**R11. Deeper Muse Spark answers:** `reasoning_effort` "low" or "medium" for knowledge-base answers, a higher
`max_tokens` for procedures, and the same length rules as R2.

**R12. Same checks for cloud answers:** grounded in the passages, with phone fallback on timeout or rejection
(already in place). Make sure the R3 fairness changes apply to cloud answers too.

---

## 2. Speech, voice and reading aloud

**S1. Offline speech-to-text (Whisper).**
- Check which Whisper model size is installed, and its Hindi accuracy on real ASHA-style notes.
- If it's weak, consider a larger multilingual Whisper model (weigh size against speed on this phone).
- Add a **Hindi / English choice** for the voice input language, so Whisper isn't left guessing.

**S2. Online speech-to-text (Muse Voice Transcribe).**
- Confirm Hindi is supported. The docs list 25 languages, and Hindi isn't confirmed.
- Pass the chosen language. Test with real Hindi and Hinglish.

**S3. Natural reading aloud.**
- Meta's API has no text-to-speech model, so Cloud first can't fix the robotic voice.
- Options:
  1. Pick the best installed Android voices (Google's natural hi-IN and en-IN voices); check they're
     installed and guide the user to download them for offline use; slightly slower speech rate.
  2. For the demo only: pre-recorded human narration in Hindi and English.
- Muse Code should recommend which.

**S4. Demo voice note from a real human recording.**
- Two short real recordings (Hindi and English, about 20 s each) of an ASHA-style visit note.
- The demo plays the chosen one and runs it through the real speech-to-text (Whisper offline, Muse online),
  showing the transcript as it arrives.

---

## 3. Demo redesign (`ArogyaDemoTour.kt`)

**D1. Start screen:**
- Language choice (हिन्दी / English) for captions, narration and the voice note.
- A clear banner: "DEMO · sample village (fictional people) · every step is done live by the app".
- Shows the current mode (Cloud first / Local first, online / offline).
- A Start button.

**D2. Show that it's live:** a "LIVE" badge while the app works, then "✓ done by app in X s". Show the
**real app screens** (visit form, MCP card, registers), not summary cards.

**D3. Highlight the AI.**
- A persistent "✨ AI at work" panel that ticks off each AI feature as it runs, with the model and time:
  - speech-to-text
  - form filling
  - evidence check
  - guide search + reranking
  - grounded answer
  - ANM message
  - card text reading + extraction
  - SAM 3.1 count
  - reading aloud
- A separate "Rules, not AI" panel: danger signs, schedules, incentives.
- A closing summary of all of it.

**D4. SOP scene:**
- Show the question, then the passages found (document + page, matching sentence highlighted).
- Then the answer ending "Source: …, page N".
- Then the app opens the actual document at that page (R6).
- Use a question with a known answer page that M1 confirms.

**D5. Voice:** the human recording goes through real speech-to-text (S4), and narration as in S3.

**D6. Text on one line:** short titles (3–4 words), one-sentence captions, smaller labels; test on the
phone at normal font size.

**D7. Pacing:**
- About 3:00 with Cloud first, and a visible timer.
- Each scene waits for the real result, then holds 3–4 s.
- An offline run is allowed to be longer.

**D8. Optional SAM 3.1 drug-kit scene:** a bundled sample photo of ORS sachets, counted live when online.

**D9. Closing:** the problem solved, before and after (use only a time-saved figure you can back up), the
offline/online table and the Meta models used.

**D10. Offline demo run:** the demo must complete in airplane mode, using Whisper, Llama and ML Kit, with
honest timing.

---

## 4. App UI fine-tuning (not a rebuild)

**U1. Text wrapping:** fix text breaking onto two lines everywhere; use short labels and consistent label
styles, cut off long text on tiles and chips.

**U2. Home:**
- Keep the header (ArogyaDoot · ASHA Dashboard, name and village, status chip), the 4 tiles with counts and
  the demo button.
- Move Backup and Practice into a "More" menu.

**U3. Visit and form screens:**
- Bigger row text, fewer words per row, hints shown on tap.
- Roomier Yes/No chips, and a sticky bottom button.

**U4. Consistency:**
- One colour each for AI (✨), danger (red) and rules.
- One card style, spacing and icon set.

**U5. Complete Hindi:** cover every screen (registers, cloud window and demo are partly English only), and
consider Hindi as the default.

**U6. Fewer stacked pop-ups:** use full-screen pages with a back arrow for registers, MCP card and settings.

**U7. Earlier suggestions not done yet:**
- a red danger banner on Home for referrals not yet followed up;
- a "Next visits this week" strip;
- a first-time welcome screen (3 slides, then load the sample village).

**U8. Small one-line chips and badges.**

---

## 5. Pending verification (from HANDOVER §7–8)

- **V1. Live Meta test with the saved key:**
  - connection test;
  - form filling;
  - Voice Transcribe (English and Hindi);
  - Ask SOP;
  - SAM count (is a base64 data-URL image accepted, or is a Files API upload needed?);
  - airplane-mode fallback.
- **V2. Device test of the visit flow:**
  - Baby of Ritu, Day 14;
  - a pregnant woman's ANC column;
  - save and PDF;
  - MCP card, drug kit and diary updates.
- **V3. First full run of the current demo:** read `AROGYA_DEMO_*` logs, especially MCP card reading of the
  generated sample card.
- **V4. Hackathon eligibility:** does on-device Llama count? Ask on Discord. The Muse cloud models are the
  safe "Meta model" story.

---

## 6. Owner to prepare

- Two human voice-note recordings (Hindi and English), plus optional narration recordings.
- The benchmark questions with known answer pages (Muse Code can draft them from the database for the
  owner to check).
- The guide PDFs, if they're bundled or downloaded for offline opening.
- An optional photo of ORS sachets.

---

## 7. Submission

- README for judges: the problem, the features, an offline/online table, the Meta tools used (Muse Spark,
  Muse Voice Transcribe, SAM 3.1, Muse Glimmer, Muse Code), and the safety design.
- Screenshots and a demo video, recorded both online and offline.
- Delete build caches last.

---

## Rules that still apply

`AGENTS.md`:
- audit before implementing;
- don't break the RAG, the phone model or speech;
- offline always works, and one Local first / Cloud first setting controls everything;
- danger signs and schedules are fixed rules, never AI;
- every AI answer is grounded in the sources and approved by the ASHA;
- "ArogyaDoot" branding;
- be honest about what has been verified;
- no keys in code.
