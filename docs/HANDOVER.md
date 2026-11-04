# Pramaan — Project Handover

Status as of **31 October 2026, 20:30 IST**. Written for whoever continues the work, human or Muse Code.
Read `AGENTS.md` (repo root) first for the working rules; this file explains the product and the code.

---

## 1. What Pramaan is

Pramaan is an Android app that makes an **ASHA worker's** daily work easier with AI. ASHAs are India's
community health workers. Every home visit means filling paper forms, the family's MCP card and several
registers, and looking things up in hundreds of SOPs.

Pramaan does three things:

1. **Ask SOP.** She asks a question by voice or text and gets an answer drawn from the official ASHA guides.
   Each answer names its source document and page, links to it, and shows the passages it used.
   Works offline.
2. **Voice note → official forms.** For each person (newborn, child, pregnant woman, sick child, eligible
   couple) the app shows exactly what the official form for *today's visit* asks. She speaks one note. AI
   fills that form's column, she checks it, and saving updates the form, the MCP card, the drug kit
   register, the activity diary and the next visit date in one go. The form prints as PDF in the same
   rows × visit-columns layout as the paper version, building up across visits.
3. **Registers without paperwork.** Patient list by household, MCP card per person, visit schedules,
   official RI Forms 3 and 4, drug kit stock card, vital events, monthly report and claim, and an
   encrypted Google Drive backup.

**Constraints the product was designed around:**
- Offline first.
- No government backend; data lives only on the phone and the user's Google Drive.
- 1000+ patients.
- Clinical decisions stay with the human; danger signs are fixed rules, not AI.
- Synthetic data only in the demo.

**Context:** it is entered in the **Meta Global AI Developer Hackathon**. Submissions are due
**5 Nov 2026, 6:30 AM IST**, and judging runs 5–9 Nov. Eligible tools named by the hackathon include the
Meta Model API, Muse Image, SAM 3.1 and Muse Code.

Pramaan is built as a **wrapper on an existing base platform**, which provides the
on-device LLM, the retrieval engine (RAG), speech, OCR and Meta API plumbing. Pramaan's own code is the
`Arogya*.kt` files (the file and class names keep the old "Arogya" prefix on purpose; see AGENTS.md rule 7). User-visible branding is Pramaan everywhere.

---

## 2. Feature list (what the user sees)

| Area | Feature | Offline | Online (when Cloud first and signal) |
|---|---|---|---|
| Home | Today card (due visits), big tiles: New visit, People, Ask SOP, Registers; Visits, Backup, ✨ AI, Practice | ✅ | — |
| Ask SOP | Question by text or voice, answer with source document, page, link and supporting passages; photo/PDF attachments | ✅ Llama + guide search | ✅ Muse Spark |
| Visit | Person picker, then **3 steps**: (1) what to record today, taken from the official form; 🔊 reads it aloud; 🎤 one voice note or typed note. (2) AI fills today's column; each value is marked ✨ with the words it came from; tap to edit; missing rows flagged, read aloud, "add more" by voice; danger banner from the form's rules; weight trend. (3) Paper-style form with earlier visits, today highlighted and "Next visit due"; Save; Share PDF | ✅ | ✅ |
| Forms | Annexure 9 Home Visit Form (Day 1–42), HBYC card (3–15 mo), MCP card ANC 1–4, sick child (HBYC §5.4), family planning | ✅ | — |
| People | Patients grouped by household; add/edit; MCP card per person (official sections); read a paper MCP card by camera; ANC details; every visit form filled so far | ✅ | card reading ✅ |
| Registers | Official forms (RI Form 3, RI Form 4, Drug Kit Stock Card, every filled visit form) shown and shared as PDF; monthly report & incentive claim; due lists; activity diary; drug kit (stock, what was given, **📷 count from photo**); births/deaths/cases; village health register; RCH export (CSV) | ✅ | SAM 3.1 photo count |
| Backup | Password-encrypted backup to Google Drive (or any folder), automatic when due; restore | ✅ file | Drive syncs it |
| AI transparency | ✨ AI badge on every AI feature; tap shows the model behind it. ✨ AI page lists all AI features and models. "☁ Cloud or phone AI" window: Cloud first / Local first, cloud model choice, connection test | — | — |
| Practice | Daily questions generated from guide passages (answer must be in the passage) | ✅ | ✅ |
| Escalation | Call 108, call or WhatsApp the ANM, drafted ANM message | ✅ | ✅ |
| Sample data | "Load / reset sample village": 50 fictional people; 20 with earlier visits filled through the real save path, so every register can be cross-checked | ✅ | — |
| 3-minute demo | Home → "▶ Watch the 3-minute demo": 11 narrated scenes that run the real features on the sample village (due list → Annexure 9 Day 7 → voice-note text → AI fills form → danger rules → Ask SOP with source → AI ANM message + PHC referral → sample MCP card photo found in Pictures/Pramaan and read by AI → save everywhere → registers/claim → models used). Nothing is sent | ✅ | ✅ |
| Profile | ASHA name and village on Home ("Namaste, …"), stored on the phone; tile counts (due today, referrals) | ✅ | — |

---

## 3. AI and models

**One setting controls everything:** Settings → **Local first / Cloud first**. The same switch appears in ✨ AI →
☁ Cloud or phone AI. It is stored as `AnswerPolicyStore` (`AnswerPolicy.LOCAL_FIRST` or `CLOUD_FIRST`).
- **Cloud first:** the cloud model is tried first. The phone is used automatically with no signal, no key,
  no reply within 25 s (30 s for voice), or an unusable reply.
- **Local first:** the phone model first; the cloud only if the phone model is not ready or its reply is
  unusable.

Every AI reply, from either engine, is validated before it is offered:
- **Grounding:** the evidence must appear in the ASHA's own words, and numbers must appear in that evidence.
- **Card readings:** values must be found in the card's text.

| Job | Offline (on phone) | Online |
|---|---|---|
| Answers (Ask SOP) | Llama 3.2 3B Instruct Q4_K_M via llama.cpp; retrieval multilingual-e5-small + mMiniLMv2 cross-encoder | Muse Spark 1.3 (the platform's engine) |
| Form filling, Finish visit, family explanation, ANM message, daily practice, organise notes, card fields | Llama 3.2 3B | Muse Spark 1.3 (Meta Model API) or Muse Glimmer 30B (Together AI) |
| Speech to text | Whisper (on-device) | Muse Voice Transcribe 1.0 |
| Reading aloud | Android offline voice | Android online voice (Google, not Meta) |
| Card/attachment text | Google ML Kit OCR (Latin + Devanagari), PDF text | (text then goes to the model above) |
| Drug-kit count from photo | — (typed count) | SAM 3.1 |
| Danger signs, schedules, incentives | Fixed rules, no AI | — |

**Endpoints** (from Meta's docs, dev.meta.ai/docs, read 31 Oct 2026):

| Model | Call |
|---|---|
| Muse Spark | `POST https://api.meta.ai/v1/chat/completions`, model `muse-spark-1.3` |
| Muse Voice Transcribe | `POST https://api.meta.ai/v1/asr/transcribe`, multipart `request` (JSON) + `audio` (WAV, mono, 16-bit, 16 kHz), model `muse-voice-transcribe-1.0` |
| SAM 3.1 | `POST https://api.meta.ai/v1/responses`, model `sam-3.1`, input_text noun phrase + input_image; output is box/mask tokens parsed by `SamRecordParser` |
| Muse Glimmer 30B | **not on the Meta API**; Together AI `https://api.together.xyz/v1/chat/completions`, model `meta-models/Muse-Glimmer-30B` |
| Muse Image | not used (unavailable from India) |

**Keys:**
- The Meta key is entered in the app's Settings and stored by the platform's `GroqKeyStore` (`MetaKeyStore`
  underneath, AES-GCM in AndroidKeyStore).
- The Together key is entered in ✨ AI → ☁ window and stored by `ArogyaKeyVault`.
- Text sent to the cloud has the person's name, relative, village, phone, RCH id and household removed
  (`ArogyaOfficialForms.scrub`, `ArogyaOnlinePolicy.scrub`). the platform's `PrivacyGate` also redacts text and
  logs every egress to `meta_egress.log`.

---

## 4. Architecture and code map

- Package: `app/src/main/java/com/example/arfas/` (namespace `com.example.arfas`).
- applicationId: `com.example.pramaan`. App label: `Pramaan`.

### Pramaan code (`Arogya*.kt`, about 6,400 lines)

| File | What it does |
|---|---|
| `ArogyaVisitScreen.kt` | Main Pramaan screen: home, Ask SOP dialog (guide answers, sources, passages, attachments), older visit/Finish-visit flow, escalation, dictation dialog (Whisper/Muse routing), reading aloud, quiz, settings hooks, sample village load |
| `ArogyaDemoTour.kt` | The 3-minute auto-demo (`ArogyaDemoScript` scenes and note, `ArogyaDemoCard` sample MCP card rendered and saved to Pictures/Pramaan, then found via MediaStore) |
| `ArogyaGuidedUi.kt` | **Visit flow**: person picker, 3-step official-form visit, `OfficialFormGrid` (paper look) |
| `ArogyaFormSpecs.kt` | **Official forms**: `FieldSpec`/`FormSpec`/`FormEntry`; ANNEX9, HBYC, MCP_ANC, SICK, FP rows; `formFor` (which form and column for this person today, with early-visit handling), `fieldsFor`, `prompt`, `parse` (grounded), `dangers` (form rules), `nextDue`, `previous`/`carried`/`missing`/`weightNote`, `apply` (saves form + MCP card + drug kit + diary), `visitRecord`, `seedDemo` (20 sample histories), `table` (printable), `subjectFor` (mother → baby's Annex 9), `scrub` |
| `ArogyaRegistry.kt` | Data model (`Beneficiary`, `McpCard`, `DiaryEntry`, `StockTxn`, `VitalEvent`, `RegistryData` incl. `forms`), `ArogyaRegistryLogic` (high risk, EDD, lists, report, incentives, CSV), `ArogyaSchedule`, `ArogyaRegistryCodec` (JSON), `ArogyaBackupCrypto` (PBKDF2 + AES-GCM), `ArogyaSampleVillage`, `ArogyaForms` (RI Form 3/4, drug kit card, MCP card sections) |
| `ArogyaRegistryStore.kt` | Encrypted `registry.enc` store, single writer, backup/restore via SAF (Drive), PDF rendering, 1000-patient synthetic load |
| `ArogyaRegistryUi.kt` | Registry hub, patients, MCP card dialog, registers pages, stock/events dialogs, backup tab, `AiBadge`, ✨ AI page, Today card, Home grid |
| `ArogyaVisitAi.kt` | AI engine for Pramaan: `route()` (Cloud first / Local first for every AI action), `askPramaan` (RAG answers with sources), `fillForm`, `extractFields`, `familyExplain`, `anmMessage`, `refresher`, `readCard`, organise notes, warm-up |
| `ArogyaCloudApi.kt` | `MetaCloud` constants, `ArogyaCloudChoice`, `ArogyaRouting.order`, `ArogyaSamCount`, `ArogyaKitPhrases` |
| `ArogyaCloudUi.kt` | `ArogyaKeyVault`, `ArogyaCloud` (setting, keys, online check, `withDeadline`, Muse transcribe via the platform's `MetaOnline`), Cloud/phone settings dialog with connection test, `SamCountDialog` |
| `ArogyaModelClient.kt` | Endpoint presets (Muse Spark, Muse Glimmer) and a small OpenAI-compatible HTTP client |
| `ArogyaAiExtras.kt` | Grounding helpers, prompts and parsers (family, ANM, quiz, card), `ArogyaAiCatalog` (badge text), `ArogyaCardApply` |
| `ArogyaFinishVisit.kt` | Older Finish-visit extraction prompt/parser and the temperature/weight rule fallback (also used by forms) |
| `ArogyaGuided.kt` | Visit-type labels and `QType` |
| `ArogyaDangerSigns.kt`, `ArogyaClinicalText.kt`, `ArogyaClinicalGrounding.kt`, `ArogyaEvidenceSafety.kt`, `ArogyaUrgentTriage.kt` | Fixed danger-sign rules and safety checks |
| `ArogyaVisit*.kt`, `ArogyaCapture.kt`, `ArogyaShare.kt`, `ArogyaSources.kt`, `ArogyaOnlinePolicy.kt`, `ArogyaHindi.kt`, `ArogyaDesign.kt` | Visit queue/repository/media, note organiser, sharing (ANM, WhatsApp, PDF), guide sources, identifier removal, Hindi strings, design |

### Underlying base platform (internals; do not refactor without need)

| Files | Role |
|---|---|
| `MainActivity.kt` | Hosts Pramaan; constructs `ArogyaVisitAi`. Pramaan touches it only through hooks |
| `AndroBaselineRuntime.kt` | Chat/answer runtime, Local/Cloud policy for answers |
| `PocketRag*`, `GenericKb*`, `HybridKnowledgeRetriever`, `SemanticSearchEngine`, `KnowledgeSourcePresentation` | RAG, search, reranking, source display |
| `LocalModelManager`, `LlamaCppCore`, `LlamaNative` (+ `src/main/cpp/pramaan_llama.cpp`) | On-device Llama |
| `AndroWhisperRecognizer`, `AndroAudioRecorder`, `AndroVoiceLoop`, TTS classes | Speech |
| `Phase1TurnAttachment` | Photo/PDF/Office attachments, OCR |
| `MetaModelApi`, `MetaOnline`, `PrivacyGate`, `SamRecordParser`, `MetaVisionEngine` | Meta Model API client (Spark, SAM, Voice, Image), egress log |
| `GroqKeyStore`, `MetaKeyStore`, `AnswerPolicy` | Keys and the Local/Cloud setting |

**Data on the phone:**
- `registry.enc` holds beneficiaries, diary, stock, vitals and form entries, all encrypted.
- The visits queue is a separate encrypted repository.
- The guide database is `/sdcard/Download/ASHA_Health_Guide.db`.
- The models were installed through the app's setup.
- Laptop copies of the models are kept in a local folder on the developer's PC (not in git)
  (Llama-3.2-3B-Instruct-Q4_K_M.gguf, onnx, asha_kb).

---

## 5. Official sources behind the forms

| Form | Source |
|---|---|
| Annexure 9 Home Visit Form | Handbook for ASHA Facilitators (asha_fac_en.pdf, p.58–61); other annexures 5/8/10/11/12 |
| HBYC card | Handbook for ASHA on Home Based Care for Young Child, p.90; sick child §5.4 p.63–66 |
| MCP card | Mother and Child Protection Card, MoHFW/MWCD 2018 |
| RI Forms 3/4 | Immunization handbook p.56–58 |
| Incentives | ASHA incentives document |
| Integrated Module A | p.94–98 |

The guide database (`ASHA_Health_Guide.db`, table `procurement_chunks`) holds the passages with source
names and pages, and `knowledge_sources` holds the URLs. The sick-child and family-planning layouts and the
HBYC weight row are app summaries, labelled as such; the other forms are row-for-row copies.

---

## 6. Build, install, logs (Windows laptop)

- **Project:** `C:\Pramaan - Copy`. The build needs the Android SDK/NDK (NDK 26.1.10909125) and the
  Android Studio JDK.
- **Build + install + push guide:** run `_work\run\build_install.bat` (double-click). It runs
  `gradlew :app:assembleDebug`, installs over ADB and pushes `ASHA_Health_Guide.db` to the phone. It writes
  `build_log.txt`; look for `BUILD SUCCESSFUL`, `INSTALL=OK` and `KB_PUSH=OK`.
- **Logs:** run `_work\run\logs.bat`. It writes `app_log.txt` with the app's tags (ArogyaDemo,
  ArogyaRegistry, MetaModelApi, MetaOnline, PocketRag…).
- **Useful log markers:** `AROGYA_ROUTE`, `AROGYA_FORM_FILL`, `AROGYA_FORM_SAVED`, `AROGYA_VOICE_CLOUD`,
  `AROGYA_SAM_COUNT`, `AROGYA_CLOUD_TEST`, `AROGYA_CLOUD_TIMEOUT`, `AROGYA_FINISH_EXTRACT`.
- **Build time:** about 5–10 minutes on the 8 GB laptop. Keep `build/`, `app/build/`, `app/.cxx/` and
  `.kotlin/` until submission; deleting them forces a slow native llama.cpp rebuild.
- **Phone:** Samsung SM-S938B (Android 16), USB debugging on.
- **Host tests:** see `tools/host-tests/README.md`.

---

## 7. Verification status

| Item | Status |
|---|---|
| Latest build compiled and installed | ✅ 31 Oct 20:26 (rename, one-setting routing, cleanup, Home header, profile, tile counts, 3-minute demo) |
| 3-minute demo on device | ⚠️ installed, not yet run end to end |
| Host tests (pure logic) | ✅ 198 checks pass |
| Ask SOP offline on device | ✅ verified earlier via logs (answers accepted, sources, warm-up ~12 s) |
| New 3-step official-form visit flow on device | ⚠️ installed, **not yet exercised on the phone and read back from logs** |
| Meta cloud calls (Spark, Voice Transcribe, SAM, Glimmer) | ⚠️ **never called live yet**; the key was just added. First test: ✨ AI → ☁ → Save and test connection |
| SAM image as base64 data URL | ⚠️ unverified (Meta's examples use an image URL); fallback plan: Files API upload |
| Muse Voice Transcribe Hindi support | ⚠️ unverified (docs list 25 languages; Hindi not confirmed) |
| Hackathon eligibility of on-device Llama | ⚠️ unconfirmed; the Muse cloud models are the safe "Meta model" story |

---

## 8. Open work, in priority order

1. **Live Meta test** on the phone with the key, in Cloud first: connection test, form filling, voice
   (English and Hindi), Ask SOP, SAM drug count, then airplane-mode fallback. Read `app_log.txt`
   and fix whatever the real API returns (model id, auth, image format, response shape).
2. **Run the 3-minute demo on the phone** (Home → ▶ Watch the 3-minute demo), once in Cloud first and once
   offline. Read `app_log.txt` for `AROGYA_DEMO_START`, `AROGYA_DEMO_STEP_FAILED`, `AROGYA_DEMO_SAVED`,
   `AROGYA_ROUTE`, `AROGYA_FORM_FILL`. Fix any failed scene, especially MCP card reading of the generated
   sample card and the SOP answer time.
3. **Device test of the visit flow:** Baby of Ritu (Day 14), a pregnant woman (ANC column), save, PDF, the
   MCP card and drug kit updated, Registers → "Visit forms filled from home visits".
4. **Demo readiness:**
   - The auto-demo exists; rehearse it and record a screen video as a backup.
   - Remaining UI ideas, not done: Hindi as the default language, bigger text on the visit and form
     screens, a red danger banner on Home, a "next visits this week" strip, a first-time welcome screen.
5. **Submission:** README for judges, screenshots/video, clean build caches, list of Meta tools used
   (Muse Spark, Muse Voice Transcribe, SAM 3.1, Muse Glimmer, Muse Code), honest offline/online table.
6. **Nice to have:**
   - Convert host tests to JUnit.
   - Background two-model cross-check (phone and Muse disagree → ask the ASHA).
   - Month-end claim drafting by Muse Spark.
   - Glasses vision slide.
7. **Not planned before the deadline:**
   - Renaming internal identifiers. The package `com.example.arfas` is tied to the native Llama bridge,
     and the prefs names would wipe saved keys.
   - An offline photo counter.

---

## 9. Rules that must keep holding (also in AGENTS.md)

- No government backend; phone + Google Drive only.
- Offline must always work; cloud is an improvement with automatic fallback.
- Danger signs and schedules are fixed rules from the guidelines, never AI.
- AI output is a proposal: grounded in the ASHA's words or the document text, shown with evidence,
  approved by the ASHA.
- Official form rows and layout follow the source documents; only data is synthetic.
- Synthetic/fictional people only in demos.
- Don't break the RAG, the on-device LLM or speech. Audit existing code before adding anything; the base platform
  already has much of the plumbing (`MetaModelApi`, `MetaOnline`, keys, policy).
- Branding: "Pramaan" in everything a user or judge sees.
- Don't submit under another person's name; don't claim on-device Llama satisfies the hackathon rules
  without confirmation.

## 10. Rename (4 Nov 2026): ArogyaDoot → Pramaan
- Users and judges see "Pramaan" everywhere: launcher, screens, PDFs and their footers, share text,
  the demo, AI badges, notifications and the Downloads folder (`Downloads/Pramaan`).
- The Gradle project is `Pramaan`, and the app ID is `com.example.pramaan`. It installs as a **new app**
  beside the old ArogyaDoot one, with empty data: enter the Meta key again, set up the models and the
  guide, and load the sample village. Uninstall ArogyaDoot when the new app works.
- Kept on purpose (renaming breaks data, native code or scripts): the Java package `com.example.arfas`,
  the `Arogya*.kt` file and class names, the `AROGYA_*` log markers, the `arogya_*` prefs, the
  `arogyadoot_visits` folder and keystore alias, and the `ARGB` backup header. Restore still accepts
  backups whose app field says "ArogyaDoot".
- Removed as unused (no references anywhere): AndroGemmaCompat, AndroStreamingRecognizer, AndroVad,
  FixSightCapabilityRouter, KnowledgeConversationEngine, MetaVisionMathTest, ModelSetupDialog,
  ObservationSource, PocketHybridFusion, StreamingTTSManager, VisualFrameChangeDetector. Also removed:
  the old Muse Code prompts, NEXT_CHANGES.md and the earlier batch reports (git history keeps them).
