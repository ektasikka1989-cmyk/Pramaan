# Models and assets

Pramaan runs its AI **on the phone** (offline) and, optionally, **in the cloud** through the Meta Model API. Model
files are large and have their own licences, so **they are not stored in git**. This page lists every model and data
file the app uses, how each one reaches the phone, and where it comes from.

**For judges / testers:** install the APK from [Releases](https://github.com/ektasikka1989-cmyk/Pramaan/releases/latest).
It already contains Whisper (offline speech), the search models, the guide database and the app data — that is why it is about 700 MB. On first launch the **setup wizard**
downloads the on-phone Meta models from Hugging Face and installs them; nothing has to be copied by hand.

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
| `voice/stt/whisper/model.bin` | **Whisper**: offline speech to text (Hindi / English), bundled in the released APK | ❌ (too large) | [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp) (MIT) |

## 2. Downloaded by the setup wizard (Hugging Face)
| Download | Size | What it contains | Licence |
|---|---|---|---|
| [`Pramaan_Meta_Models.zip`](https://huggingface.co/datasets/EktaSikka/Pramaan_Meta_Models) | 3.26 GB | The on-phone Meta models: **Llama 3.2 3B Instruct** (GGUF, Q4_K_M) for answers, form filling and explanations via llama.cpp, and the offline vision models | [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/) — Built with Llama; each other model keeps its own licence |
| [`ASHA_Health_Guide_v2.db`](https://huggingface.co/datasets/EktaSikka/Pramaan_Knowledge_Base) | 24.5 MB | The same guide database as in the APK — downloaded only if the bundled copy fails to install | Built from official Government of India documents |

How it works (`ArogyaSetupUi.kt`, `ArogyaSetupDownload.kt`, `ArogyaSetupPlan.kt`): Android's DownloadManager fetches the
zip (Wi-Fi only by default, resumes after a dropped signal, continues in the background), the free space is checked
first, and `MetaModelStore.installZip` streams each model straight to its final place (no second copy) and checks the
Llama file is complete. The zip is deleted afterwards. A zip already on the phone can be chosen instead.

## 3. What each on-phone model does
| Model | Used for |
|---|---|
| **Llama 3.2 3B Instruct** (Meta) | Guide answers, voice note → official form, family explanation, ANM message, daily practice — all offline |
| Whisper (whisper.cpp) — bundled in the APK | Offline speech to text (Hindi / English) |
| DETR / CLIP / DINOv2 / MobileSAM (ONNX), where included | Offline object and kit detection (`MetaModelStore.kt`); the drug-kit count also works online with SAM 3.1, or by hand |

## 4. Cloud models (optional, Meta Model API, the user's own key)
| Model | Used for |
|---|---|
| Muse Spark 1.3 | Answers and form filling when **Cloud first** is on |
| Muse Spark 1.3 (vision) | Reading photos of slips, registers, MCP cards and medicine strips (Cloud first, or when the phone's reading is incomplete). Aadhaar is blacked out on the phone before sending |
| Muse Voice Transcribe | Voice notes |
| SAM 3.1 | Drug-kit counting from a photo |

The key is created at [dev.meta.ai](https://dev.meta.ai/), entered in the **setup wizard** (or Settings), tested with one
short call and stored encrypted (Android Keystore). Only Meta's models are used online. No key is in this repository.
Text sent to the cloud has names, relatives, village, phone, RCH ID and household removed first; photos have Aadhaar
numbers blacked out on the phone before sending.

## Why the models are not in git
- GitHub limits files to 100 MB, and the models together are about 3 GB.
- Each model keeps its own licence and official download page; linking to it keeps attribution clear.
- The setup wizard downloads them from the two Hugging Face repositories above, so the APK stays self-contained for everything else.
