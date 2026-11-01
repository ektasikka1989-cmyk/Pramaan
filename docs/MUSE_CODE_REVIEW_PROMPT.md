# Prompt for Muse Code — review the proposed changes (views first, no code yet)

Paste everything below the line into Muse Code.

---

We have a list of proposed changes for ArogyaDoot in **`docs/NEXT_CHANGES.md`**. They came from an earlier
review: a code audit of the knowledge-base answer pipeline (RAG, the phone model and the cloud path), the
demo, speech and the UI. **I want your independent view before anything is implemented.**

Context: read `AGENTS.md` and `docs/HANDOVER.md` if you haven't already. The deadline is
**5 Nov 2026, 6:30 AM IST**. The top priority is **offline**: complete, correct, sourced answers from the
official ASHA guides, and the whole home visit, with no internet. The cloud (Meta Muse models) improves on
that through the single Local first / Cloud first setting.

**Please do this, and do not change any code yet:**

1. **Check the audit's findings yourself in the code**, quoting files and lines. Is each one correct?
   - Evidence size for the phone model: `GenericKbKeyFacts.WINDOW_PASSAGES/WINDOW_CHARS`, `KEY_FACT_PASSAGES`.
   - Cloud first using the same small evidence as the phone, unlike "Check online".
   - The contradiction between `PocketRagPromptBuilder.LENGTH` and `COMPLETE_ANSWER`.
   - `reasoning_effort` and `max_tokens` for cloud knowledge-base answers in `GroqChatProvider`.
   - How strict `PocketRagQualityGate` is, and the copied-sentence fallback.
   - The web and general answers outside the guides (`kbLiveWebAnswer`, `kbGeneralAnswer`).
   - Guide database quality: non-ASHA documents, missing URLs, mid-sentence passages.

   Tell me anything the audit missed that hurts answer completeness or quality, **especially offline**.
2. **Give your view on every item** in `NEXT_CHANGES.md`, using its ID (M1, R1…R12, S1…S4, D1…D10,
   U1…U8, V1…V4). For each one:
   - agree, disagree or change it;
   - a better way, if there is one;
   - risk to existing working features;
   - effort (hours);
   - whether it touches Pramaan core files.
3. **Propose the benchmark (M1).** Draft 15–20 questions from the guide database, each with the expected
   document and page, mixed English and Hindi, plus a simple way to run them offline and online and read the
   results from the logs. I will check the questions.
4. **Propose an order of work that fits the deadline.** Offline answer quality first, then the demo, then
   the UI. Mark what is must-have for the hackathon and what is nice-to-have. Say what you would drop if
   time runs short.
5. **Flag anything that would break** the RAG, the on-device Llama, Whisper, OCR, the one-setting routing,
   or saved user data, and how you'd avoid it.

**Reply format:**
- a short summary;
- a table of item, view, effort, risk and core files touched;
- the benchmark draft;
- the order of work;
- open questions for me.

Wait for my approval before implementing anything.
