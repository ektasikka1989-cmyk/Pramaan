# Models and assets

Pramaan runs its AI **on the phone** (offline) and, optionally, **in the cloud** through the Meta Model API. Model
files are large and have their own licences, so **they are not stored in git**. This page lists every model and data
file the app uses, how each one reaches the phone, and where it comes from.

**For judges / testers:** install the APK from [Releases](https://github.com/ektasikka1989-cmyk/Pramaan/releases/latest).
It already contains the search models, the guide database and the app data. You add **two files** (Llama and Whisper)
to the phone's Downloads folder and the setup wizard installs them.

## 1. Inside the APK (nothing to do)
| Asset (in `app/src/main/assets/`) | What it does | In git? | Source / licence |
|---|---|---|---|
| `guide/ASHA_Health_Guide_v2.db` (~24 MB) | ASHA guide database: passages, page numbers, official links and embeddings, used for cited answers offline | ✅ yes | Built from official Government of India documents (MoHFW / NHM / NHSRC ASHA modules, MCP card, HBNC / HBYC guidelines); each passage keeps its source and page |
| `verified_answers.json` | 100 verified question–answer pairs (doctor-reviewed sheet in [`docs/verified_qa/`](docs/verified_qa)) | ✅ yes | Pramaan |
| `doc_templates/` | Printable layouts: MCP card, official forms, answers | ✅ yes | Pramaan, following the official layouts ([`docs/forms/`](docs/forms)) |
| `growth/who_wfa.json` | WHO weight-for-age table for the growth chart | ✅ yes | WHO Child Growth Standards |
| `semantic/model.onnx`, `semantic/tokenizer.json` | multilingual-e5-small: meaning-based guide search (English, Hindi, Hinglish) | ❌ (too large) | [intfloat/multilingual-e5-small](https://huggingface.co/intfloat/multilingual-e5-small) (MIT), exported to ONNX |
| `reranker/model.onnx`, `reranker/parity.json` | mMiniLMv2 cross-encoder: re-ranks the top passages | ❌ (too large) | [cross-encoder/mmarco-mMiniLMv2-L12-H384-v1](https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1) (Apache-2.0), exported to ONNX |
| `voice/tts/*`, `voice/asr/*` (optional) | Sherpa-ONNX offline voices | ❌ (too large) | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) models (Apache-2.0) |
| `voice/stt/whisper/model.bin` (optional) | Bundled Whisper; if absent, the wizard installs it from Downloads | ❌ (too large) | see below |

## 2. Installed by the setup wizard (put in the phone's Downloads)
| File | Size | What it does | Where to get it | Licence |
|---|---|---|---|---|
| `Llama-3.2-3B-Instruct-Q4_K_M.gguf` | ~2.0 GB | **Meta Llama 3.2 3B Instruct**: the on-phone language model (answers, form filling, explanations) via llama.cpp | [bartowski/Llama-3.2-3B-Instruct-GGUF](https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF) | [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/) — Built with Llama |
| `ggml-small.bin` | ~470 MB | **Whisper small**: offline speech to text (Hindi / English) via whisper.cpp | [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp) | MIT |

The wizard (`ArogyaSetup.kt`, file matching in `ArogyaSetupMatch.kt`) finds these files by name in Downloads, copies
them into the app's private storage with progress, and checks them before use.

## 3. Optional: offline vision pack
A zip with DETR, CLIP, DINOv2 and MobileSAM (ONNX) enables offline object and kit detection (`MetaModelStore.kt`).
It is **not needed** for the demo: the drug-kit count uses **SAM 3.1** online, or a count by hand offline.

## 4. Cloud models (optional, Meta Model API, the user's own key)
| Model | Used for |
|---|---|
| Muse Spark 1.3 | Answers and form filling when **Cloud first** is on |
| Muse Spark 1.3 (vision) | Reading photos of slips, registers, MCP cards and medicine strips (Cloud first, or when the phone's reading is incomplete). Aadhaar is blacked out on the phone before sending |
| Muse Voice Transcribe | Voice notes |
| SAM 3.1 | Drug-kit counting from a photo |

The key is entered in **Settings** and stored encrypted (Android Keystore). No key is in this repository. Text sent to
the cloud has names, relatives, village, phone, RCH ID and household removed first.

## Why the models are not in git
- GitHub limits files to 100 MB, and the models together are about 3 GB.
- Each model keeps its own licence and official download page; linking to it keeps attribution clear.
- Planned ([`docs/LIGHT_APP_PLAN.md`](docs/LIGHT_APP_PLAN.md)): a smaller APK whose setup downloads these files automatically from one pinned Hugging Face repository.
