# Starting prompt for Muse Code

Paste this as the first message in Muse Code, after opening `C:\Pramaan - Copy` and trusting the workspace.

---

You are continuing development of **ArogyaDoot**, an offline-first Android app (Kotlin, Jetpack Compose)
that helps India's ASHA community health workers:
- **Ask SOP:** answers from the official guides, with the source document and page.
- **One voice note → official visit forms:** AI fills the official form, MCP card, registers and next visit date.
- **Registers on the phone:** encrypted Google Drive backup, and no government backend.

It is our entry for the **Meta Global AI Developer Hackathon**. Submissions are due **5 Nov 2026, 6:30 AM IST**.

**Before doing anything, read these and summarise them back to me in 10 lines:**
1. `AGENTS.md`: the rules for this repository.
2. `docs/HANDOVER.md`: what the app is, every feature, the models and endpoints, the code map, build
   steps, what is tested and not tested, and the open work in priority order.

**How I want you to work:**
- **Audit before you implement.** Search the code first; much already exists. The underlying platform
  ("Pramaan" internally) has `MetaModelApi`, `MetaOnline`, `PrivacyGate`, `GroqKeyStore`, `AnswerPolicy`,
  RAG, Whisper, OCR and llama.cpp. Tell me what exists and what you plan to change, and wait for my OK
  before large or risky changes.
- **Don't break the RAG, the on-device LLM, speech or attachments.** Keep ArogyaDoot changes in
  `Arogya*.kt` where possible.
- **Every AI feature must:**
  - work offline;
  - follow the single **Local first / Cloud first** setting through `ArogyaVisitAi.route()`;
  - fall back to the phone automatically.
- **Danger signs, schedules and incentives** stay fixed rules, never AI. AI output must be grounded and
  approved by the ASHA.
- **Branding:** "ArogyaDoot" everywhere a user or judge sees. Don't rename the package
  `com.example.arfas` or the SharedPreferences names.
- **Be honest about what is verified.** Say "compiled", "host-tested", "tested on the phone" or
  "not verified".
- **Build and install** with `_claude_work\run\build_install.bat`, then check `build_log.txt`.
  **Read phone logs** with `_claude_work\run\logs.bat`, which writes `app_log.txt`.
- Never put API keys in code, logs or chat.

**First tasks, in order** (HANDOVER §8):
1. **Live Meta test.** I have saved a Meta Model API key in the app's Settings. With Cloud first on:
   - the connection test (✨ AI → ☁ window);
   - form filling on a visit;
   - Muse Voice Transcribe, in English and Hindi;
   - an Ask SOP answer;
   - the SAM 3.1 drug-kit count;
   - then the airplane-mode fallback.

   Read `app_log.txt` and fix whatever the real API returns (model id, auth, image format, response shape).
2. **Run the 3-minute demo** (Home → ▶ Watch the 3-minute demo), in Cloud first and offline. Fix any scene
   that fails, using the `AROGYA_DEMO_*` log markers.
3. **Device-test the visit flow** (Baby of Ritu Day 14, a pregnant woman's ANC column): save, PDF, and
   check the MCP card, drug kit and diary updates.
4. **Prepare the submission:**
   - a README for judges, with the problem, features and an offline/online table of Meta tools used
     (Muse Spark, Muse Voice Transcribe, SAM 3.1, Muse Glimmer, Muse Code);
   - screenshots and a demo video;
   - clean the build caches last.

Start with step 0: read the two files and give me your summary and your plan for task 1.
