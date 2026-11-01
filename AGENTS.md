# AGENTS.md — ArogyaDoot

ArogyaDoot is an offline-first Android app that helps India's ASHA health workers:
- **Ask SOP** gives answers from official guides, with the source.
- **Voice note → official visit forms** fills the forms, MCP card and registers from one note.
- **Registers** are kept on the phone, with an encrypted Google Drive backup.

It is entered in the Meta Global AI Developer Hackathon (deadline 5 Nov 2026, 6:30 AM IST).
**Full product and code handover: `docs/HANDOVER.md`. Read it before changing anything.**

## Golden rules
1. **Audit before you implement.** Search the codebase first. The underlying platform (internal name
   "Pramaan") already has a lot: `MetaModelApi` (Muse Spark, SAM 3.1, Muse Voice Transcribe, Muse Image),
   `MetaOnline`, `PrivacyGate`, `GroqKeyStore`/`MetaKeyStore`, `AnswerPolicy`, RAG, Whisper, OCR, llama.cpp.
   Reuse it; never duplicate it.
2. **Don't break what works:** RAG (`PocketRag*`, `GenericKb*`), the on-device LLM (`LocalModelManager`,
   `LlamaCppCore`, `LlamaNative` + `src/main/cpp`), speech, attachments. ArogyaDoot code lives in
   `Arogya*.kt`; keep changes to platform files minimal and explain them.
3. **Offline must always work.** One setting, **Local first / Cloud first** (`AnswerPolicyStore`), controls
   every AI action. Cloud calls need a time limit and an automatic fallback to the phone. New AI features
   go through `ArogyaVisitAi.route()`.
4. **No government backend.** Data lives on the phone (encrypted) and the user's Google Drive only.
5. **Clinical safety:**
   - Danger signs, schedules and incentives are fixed rules, never AI.
   - AI output is a proposal that must be grounded: evidence in the user's words or document text, and
     numbers must appear in that evidence. It is shown with the evidence and approved by the ASHA.
6. **Official formats:** form rows and layout follow the source documents (see HANDOVER §5); only the data
   may be synthetic. Demo people are fictional.
7. **Branding:** say "ArogyaDoot" in everything a user or judge sees. The launcher name comes from
   `@string/app_name`. `app/src/debug/AndroidManifest.xml` overrides the label for debug builds, so
   keep it pointing at `@string/app_name`. Don't rename the package
   `com.example.arfas`, because the native JNI bridge depends on it. Don't rename the SharedPreferences
   names either, because that wipes saved keys and settings.
8. **Honesty:** don't claim a feature works until it has been tested on the device. The ✨ AI badges must
   name the real model.
9. Never put API keys in code, logs or commits. Cloud text has identifiers removed (`ArogyaOfficialForms.scrub`,
   `ArogyaOnlinePolicy.scrub`).

## Build / run / test (Windows)
- Build, install on the phone and push the guide DB: `_claude_work\run\build_install.bat`. It writes
  `build_log.txt`; check for `BUILD SUCCESSFUL`, `INSTALL=OK` and `KB_PUSH=OK`.
- Equivalent command: `gradlew.bat :app:assembleDebug` then `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
- Device logs: `_claude_work\run\logs.bat` writes `app_log.txt`. Markers: `AROGYA_ROUTE`, `AROGYA_FORM_FILL`,
  `AROGYA_FORM_SAVED`, `AROGYA_VOICE_CLOUD`, `AROGYA_SAM_COUNT`, `AROGYA_CLOUD_TEST`.
- Pure-logic tests: `tools/host-tests/` (see its README). Run them after changing any `Arogya*` logic file.
- A build takes 5–10 minutes. Don't delete `build/`, `app/.cxx/` or `.kotlin/` before submission (native rebuild is slow).

## Where things are
- `app/src/main/java/com/example/arfas/`:
  - `ArogyaVisitScreen.kt` — main screen.
  - `ArogyaGuidedUi.kt` — visit flow.
  - `ArogyaFormSpecs.kt` — official forms and save logic.
  - `ArogyaRegistry*.kt` — data, registers, store, UI.
  - `ArogyaVisitAi.kt` — AI engine and routing.
  - `ArogyaCloud*.kt` — cloud settings, keys, SAM count.
  - `ArogyaModelClient.kt` — Muse Spark and Glimmer endpoints.
- Guide DB: `_claude_work/run/ASHA_Health_Guide.db`; on the phone it is `/sdcard/Download/ASHA_Health_Guide.db`.
