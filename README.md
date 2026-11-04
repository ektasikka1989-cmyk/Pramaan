# Pramaan — offline AI assistant for India's ASHA health workers

**Pramaan** helps an ASHA (Accredited Social Health Activist) do her home visits and paperwork on an ordinary
Android phone, **with or without internet**:

- **Ask the official guides** — answers come only from the government ASHA modules on the phone, with the exact page and a one-tap PDF.
- **Speak once, the forms fill** — one spoken visit note fills the official home-visit forms, the Mother and Child Protection (MCP) card and the registers. Every value shows the words it came from, and the ASHA confirms before anything is saved.
- **Registers and drug kit on the phone** — village, pregnancy and immunization registers, stock register, refill requests and a monthly summary, encrypted on the phone with an encrypted backup to the user's own Google Drive.

Built for the **Meta Global AI Developer Hackathon 2026** (problem statement: **Build for Your World**). Author: **Ekta Sikka**.

| | |
|---|---|
| 🎬 **Demo video (3 min)** | **DEMO_VIDEO_LINK** |
| 📱 **Download the app** | [Latest release (APK)](https://github.com/ektasikka1989-cmyk/Pramaan/releases/latest) — install steps below |
| 🧠 **Models and assets** | [MODELS.md](MODELS.md) — every model, where it comes from, how it reaches the phone |
| 🛠 **What was built during the hackathon** | [see below](#what-was-built-during-the-hackathon-26-oct--4-nov-2026) |

> All people in the sample village and demo are fictional. Form layouts follow the official documents; only the data is synthetic.

---

## Scope — a job aid for trained health workers, not medical advice
Pramaan is used by **government ASHA workers**, not by patients. It does their **paperwork and workflow**: forms,
cards, registers, stock and reports. Its guide answers are **retrieved from official government ASHA training modules
and cited to the page**; it does not diagnose, prescribe or suggest doses. Danger signs and visit schedules are
**fixed rules from those guides**, and every AI-filled value is shown with its evidence and **approved by the ASHA**
before it is saved.

## The problem
An ASHA serves about 1,000 people. Each visit means a paper form, an MCP card, several registers and a drug-kit
record — often with no mobile signal — and the answers she needs are spread across 300+ pages of official guides.

## What Pramaan does (three demo workflows)
The app has a guided demo (Home → **Demo**) with three complete workflows. Every screen shows its **aim** first and
then **how it is done**, live.

| Workflow | Steps |
|---|---|
| **1. New pregnancy** (Sarita) | Registration slip photo → village + pregnancy registers → right form opens (MCP card ANC visit 1) → voice note fills it → danger check → ask the guides (IFA) → explain to the family → one save updates card, registers and stock (IFA, calcium) → official MCP card |
| **2. Newborn day-7 visit** (Baby of Neha) | MCP card photo (vaccines) → HBNC form Day 7 from a voice note → **danger sign: refer** → WhatsApp message to the ANM → ask the guides (cord care) → explain to the family → save: HBNC form, MCP card, growth chart, referral, mother's medicines |
| **3. Sick child** (Rohan) | Voice note → sick-child form → no danger sign, treat at home → ask the guides (ORS + zinc) → advice for the mother → ORS and zinc leave the kit → refill request → new strip scanned into stock |

Each save shows **"Open to check"** buttons that open the real MCP card, official form and registers, with today's changes highlighted.

## How AI is used
**One setting — Local first / Cloud first — controls every AI action.** Cloud calls have a time limit and fall back
to the phone automatically, so the whole visit works offline.

| Job | On the phone (offline) | In the cloud (online, optional) |
|---|---|---|
| Answers from the guides | **Llama 3.2 3B Instruct** (Q4_K_M, llama.cpp) + multilingual-e5-small search + mMiniLMv2 re-ranker | **Muse Spark 1.3** (Meta Model API) |
| Voice note → official form, family explanation, ANM message, daily practice | Llama 3.2 3B | Muse Spark 1.3 |
| Speech to text (Hindi / English) | Whisper (whisper.cpp) | **Muse Voice Transcribe** |
| Photo text (slips, registers, MCP cards, medicine strips) | Google ML Kit (Latin + Devanagari) | — |
| Drug-kit count from a photo | counted by hand | **SAM 3.1** |
| Danger signs, visit schedules, stock, incentives | **Fixed government rules — never AI** | — |

## Hackathon fit
- **Problem statement — Build for Your World:** the author knows the ASHA's day first-hand: paper forms, cards and registers, often with no signal.
- **Meta models, genuinely used** (each one is wired into a real step of the visit, not a demo-only call):

| Meta model | Where it is used | Code |
|---|---|---|
| **Muse Spark 1.3** (Meta Model API) | Guide answers, voice-note → official form, family explanation, ANM message, daily practice (Cloud first) | `ArogyaModelClient.kt`, `ArogyaVisitAi.route()` |
| **Muse Voice Transcribe** (Meta Model API) | Hindi / English voice notes | `ArogyaCloudUi.kt` (`ArogyaCloud.transcribe`) → `MetaModelApi.kt` |
| **SAM 3.1** (Meta Model API) | Counting drug-kit items from a photo | `ArogyaCloudUi.kt` (`SamCountDialog`), `ArogyaDemoTour.kt` |
| **Llama 3.2 3B Instruct** (Meta open weights, on the phone) | Everything above, offline, through llama.cpp | `LocalModelManager.kt`, `LlamaCppCore.kt`, `src/main/cpp/` |

- **Agentic, multi-step workflow** rather than a single call: one spoken note → official form → danger rules → referral + ANM message → MCP card, registers, stock and next visit, with grounding checks and the ASHA's approval at each step.

## What was built during the hackathon (26 Oct – 4 Nov 2026)
**Existed before the event** (the author's own earlier work): the on-device LLM runtime (llama.cpp bridge and model
manager) and the RAG retrieval engine (guide search over a local database).

**Built during the event** — the whole Pramaan app on top of them:
- the ASHA workflow: visit planning from official schedules, the guided visit, and the three demo workflows;
- voice note → official forms (MCP card ANC / PNC / baby care / immunization, HBNC Annexure 9, HBYC, sick child, family planning) with evidence and a fixed-rule backup;
- the official MCP card renderer and document/PDF output in the printed layouts;
- registers (village, pregnancy, immunization, couples, vital events), drug-kit stock register, refill requests, monthly report and incentive estimates;
- danger-sign rules, referral and the WhatsApp message to the ANM; family explanation; daily practice;
- photo features: add a person from a slip or register page (rows read on the phone), MCP card reading, medicine-strip scan, SAM 3.1 kit count;
- the single Local first / Cloud first routing with time limits and phone fallback; Meta Model API integration (Muse Spark, Muse Voice Transcribe, SAM 3.1); identifier scrubbing; encrypted storage and Drive backup;
- **RAG changes for Pramaan:** citations to document and page with one-tap PDF, follow-up questions, Hindi/Hinglish queries, the 100 verified answers layer, answer proof checks and online cross-check;
- the guide database built from official documents, the test suites in `tools/host-tests/`, and the demo photo generator.

The git history starts on 1 Nov 2026: the first commit is a snapshot of the work from 26–31 Oct, and every later step is
a separate commit. [`docs/batch_reports/`](docs/batch_reports) records each batch of work, with what changed and how it was tested.

## Safety and privacy
- **Grounded AI:** a value is kept only if its evidence is in the ASHA's own words (or the document's text) and its numbers appear in that evidence. Fixed rules fill numbers the model misses; nothing is invented.
- **The ASHA approves** every AI value before it is saved.
- **Danger signs** (newborn, mother, pregnancy, sick child) come from fixed rules taken from the official guides.
- **No government backend:** data stays on the phone, encrypted (AES-GCM, Android Keystore). Backup is an encrypted file in the user's own Google Drive.
- **Cloud text is scrubbed** of names, relatives, village, phone, RCH ID and household before it leaves the phone. Card and person reading never go to the cloud.
- API keys are entered by the user in Settings and stored encrypted; none are in this repository.

---

## Try it (Android phone)
**Phone:** Android 7.0+ (arm64), **6 GB RAM or more recommended**, about **3 GB free storage**.

1. Download the APK from **[Releases](https://github.com/ektasikka1989-cmyk/Pramaan/releases)** and install it (allow "install unknown apps").
2. Put these two public model files in the phone's **Downloads** folder (details in [MODELS.md](MODELS.md)):
   - `Llama-3.2-3B-Instruct-Q4_K_M.gguf` (about 2 GB) — from [bartowski/Llama-3.2-3B-Instruct-GGUF](https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF)
   - `ggml-small.bin` (Whisper, about 470 MB) — from [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp)
3. Open **Pramaan**. The setup wizard finds the files in Downloads and installs them with progress. The ASHA guide database is bundled with the app.
4. Load the **sample village** when the wizard offers it, then open **Demo** and pick a workflow.
5. Optional, online features: in **Settings**, add a Meta Model API key and choose **Cloud first**.

**Demo photos** (synthetic) can be generated with `python tools/demo_samples/make_samples.py <out_dir> <yyyy-mm-dd>` (Pillow) and copied to the phone; see the script header for which photo goes with which screen.

## Build from source
Requirements: a recent Android Studio (compileSdk 36), JDK 17+, Android SDK 36, **NDK 26.1.10909125**, CMake (via SDK Manager).

```bash
git clone https://github.com/ektasikka1989-cmyk/Pramaan.git
cd Pramaan
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Large model files are **not in git** (size and licences). The app runs without them but the matching feature stays
off until they are present. To bundle them in your own build, place them under `app/src/main/assets/`:

| Path | What | Source |
|---|---|---|
| `semantic/model.onnx`, `semantic/tokenizer.json` | multilingual-e5-small (ONNX) for guide search | Hugging Face, `intfloat/multilingual-e5-small` (ONNX export) |
| `reranker/model.onnx` | mMiniLMv2 cross-encoder re-ranker (ONNX) | Hugging Face, `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` (ONNX export) |
| `voice/stt/whisper/model.bin` | optional bundled Whisper (else installed from Downloads by the wizard) | `ggerganov/whisper.cpp` |
| `voice/asr/*`, `voice/tts/*` | optional Sherpa-ONNX voices | k2-fsa/sherpa-onnx releases |

The guide database (`assets/guide/ASHA_Health_Guide_v2.db`), document templates, growth tables and the 100 verified answers are in the repository.

**Tests** (pure Kotlin, no device): see [`tools/host-tests/README.md`](tools/host-tests/README.md). `AshaJourneyTest` runs all three workflows end to end — slip → registers → form → voice note → danger rules → save → MCP card → stock → refill → scan.

## Repository layout
```
app/src/main/java/com/example/arfas/
  Arogya*.kt            Pramaan's own code (visit flow, forms, registers, demo, AI routing)
                        (file names keep an earlier internal prefix on purpose)
  …                     the base on-device AI platform: llama.cpp bridge, RAG, Whisper, OCR, Meta Model API
app/src/main/cpp/       llama.cpp JNI bridge
whisper/, third_party/  whisper.cpp module (MIT)
app/src/main/assets/    guide database, document templates, WHO growth table, verified answers
docs/                   HANDOVER (full product + code guide), forms evidence, verified Q&A, benchmarks, reports
tools/                  host tests, device tests, demo photo generator, benchmark scripts
```
Developer guide: [`docs/HANDOVER.md`](docs/HANDOVER.md) and [`AGENTS.md`](AGENTS.md).

## Official sources
- MCP Card (MoHFW / MoWCD), Handbook for ASHA Facilitators (Annexure 9 home-visit form), HBYC guidelines, ASHA training modules — see [`docs/forms/`](docs/forms) and [`docs/forms_verification.md`](docs/forms_verification.md).
- The ASHA guide database is built from official government documents; each answer cites its document and page.
- 100 verified questions and answers with a doctor review sheet: [`docs/verified_qa/`](docs/verified_qa).

## Status (honest)
- Developed and tested on one arm64 Android phone during the hackathon. Logic is covered by host tests in `tools/host-tests`.
- Benchmarks and what is not yet measured on the phone: [`docs/benchmark/final.md`](docs/benchmark/final.md).
- Known gaps and planned work: [`docs/GAP_ANALYSIS.md`](docs/GAP_ANALYSIS.md), [`docs/LIGHT_APP_PLAN.md`](docs/LIGHT_APP_PLAN.md) (smaller APK + model download in setup).

## Licence and credits
- Pramaan's code: **MIT** — see [`LICENSE`](LICENSE).
- **Built with Llama.** Llama 3.2 is licensed under the [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/); the model is not distributed in this repository.
- whisper.cpp / Whisper: MIT. ONNX Runtime: MIT. Sherpa-ONNX: Apache-2.0. Google ML Kit: Google's terms.
- Meta Muse Spark, Muse Voice Transcribe and SAM 3.1 are used through the Meta Model API with the user's own key.
- Built and optimised with Muse Code.
