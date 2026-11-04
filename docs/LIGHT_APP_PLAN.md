# Pramaan: small app + download-on-setup + GitHub (plan, 4 Nov 2026)

Status: **PLAN FOR APPROVAL. No code changed.**

## 1. Where the size comes from today (what the code shows)

| Part | Where it lives now | Size (approx.) | Needed in the APK? |
|---|---|---|---|
| Llama 3.2 3B Q4_K_M (GGUF) + DETR/CLIP/DINOv2/MobileSAM (ONNX) | "Meta models zip", ~3 GB, picked from Downloads (`MetaModelStore.installZip`) | ~2.0 GB Llama + ~1 GB vision | No |
| Guide database (KB) | pushed by adb to `/sdcard/Download` (~100 MB) **and** a copy bundled in `assets/guide/ASHA_Health_Guide_v2.db` (24 MB) | 100 + 24 MB | No |
| Whisper speech model | `assets/voice/stt/whisper/model.bin` | 75–470 MB depending on base/small | No |
| Sherpa-ONNX ASR + TTS voices | `assets/voice/asr/*`, `assets/voice/tts/*` (+ espeak data) | 100–300 MB | No (TTS could stay if small) |
| Re-ranker | `assets/reranker/model.onnx` | ~100–120 MB | No |
| Native libraries: llama.cpp, whisper.cpp, ONNX Runtime, LiteRT-LM, Sherpa, ARCore/SceneView, ML Kit Devanagari | `jniLibs` from dependencies | 150–300 MB **in a debug build** (debug `.so` files keep their symbols) | Yes, but release-stripped |
| Kotlin code, UI, doc templates, growth table | APK | < 30 MB | Yes |

**First, measure.** Run this in PowerShell from the project folder to list the 25 biggest files inside your current APK:
```powershell
Add-Type -A System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::OpenRead("$PWD\app\build\outputs\apk\debug\app-debug.apk").Entries |
  Sort-Object Length -Descending | Select-Object -First 25 FullName, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}
```
Every number above is an estimate until that list confirms it.

## 2. Target shape

```
GitHub Release ─ Pramaan-v1.0.apk  (target 80–150 MB: code + native libs, stripped)
        │  first run: Setup wizard
        ▼
Hugging Face  ─ one public repo, "pramaan-assets", pinned to a commit
   manifest.json  ← the app reads this list (a copy is also bundled in the APK as a fallback)
   core/   Llama-3.2-3B-Instruct-Q4_K_M.gguf · e5 embedding · reranker · ASHA_Health_Guide_v3.db   (required, ~2.3 GB)
   voice/  whisper model · sherpa ASR/TTS                                                              (optional, Hindi voice notes offline)
   vision/ MobileSAM + DINOv2 (offline kit count) · DETR/CLIP                                          (optional; SAM 3.1 online works without)
```

### Design rules
1. **Separate files, no 3 GB zip.**
   - Every file downloads, resumes and checks on its own.
   - Nothing needs unzipping, which today needs the zip size again in free space.
   - A user who doesn't want offline vision never downloads it.
2. **A manifest drives everything.** `manifest.json` lists each file's `url`, `bytes`, `sha256`, `pack`, `required` and target path. URLs are pinned to a Hugging Face commit (`…/resolve/<commit>/file`), so the files can never change under the app. A copy of the manifest ships inside the APK, so setup still works if fetching it fails.
3. **Android's DownloadManager does the downloading.**
   - It's a system service, so a download carries on if Android kills Pramaan. That matters here: we already saw Android kill the app while it holds the model.
   - It also resumes, shows a notification, can wait for Wi-Fi, and follows Hugging Face's CDN redirects.
   - Files go to the app's own external folder (no storage permission), are checked with SHA-256, then are handed to the **existing installers**. This follows golden rule 1: `ModelFileManager.installFromUri` for Llama, `ArogyaSetup.installWhisper`, `MetaModelStore` for the vision ONNX files, and the guide import.
4. **Offline still works.** The current "files already in Downloads" path stays. For example, a PHC laptop or a judge copies the files over USB, and the wizard picks them up exactly as today. Rule 3 of AGENTS.md (offline must always work) holds.
5. **Before downloading:**
   - check free space (required pack + 10 %);
   - say "Wi-Fi recommended, about 2.3 GB";
   - show per-file progress and allow pause/retry;
   - log `AROGYA_SETUP_DL` lines for `logs.bat`.
6. **No tokens.** The Hugging Face repo is public and not gated, so the app carries no key (rule 9).
7. **Licences live in the Hugging Face repo too:**
   - **Llama 3.2:** the Llama 3.2 Community Licence allows redistribution with the licence file, "Built with Llama", and its acceptable-use policy.
   - **Whisper, e5 and sherpa:** MIT or Apache.
   - **Re-ranker:** check its model card.
   - **Guide DB:** official government documents, with sources listed.
   - Alternative: point the Llama URL at an existing public GGUF repo, pinned to a commit, instead of re-hosting it.

### Moving models out of `assets/` (the actual code change)
| Model | Code that reads it today | Change |
|---|---|---|
| Whisper | `AndroWhisperRecognizer` already looks in `filesDir/whisper` before the asset | None (just stop bundling it) |
| Llama, vision | already in `filesDir/models/meta` | None |
| Guide DB | already imported from a file | Download instead of adb push; drop the 24 MB bundled copy |
| Re-ranker | `GenericKbReranker` reads `assets/reranker/model.onnx` | Small platform change: also look in `filesDir/models/reranker` |
| Sherpa ASR/TTS | `AndroVoiceModels` reads `assets/voice/...` | Small platform change: file path when present. Keep the small English TTS in the APK if it is under ~30 MB, so narration works before setup |
| e5 embedding | (confirm where `semanticEngine` loads it from) | Same pattern if it is an asset |

### Release build (biggest quick win, no feature change)
- **Build `assembleRelease`, not debug.** Native `.so` files get stripped, which often cuts hundreds of MB from llama/whisper/ONNX Runtime. It needs a signing key, created once with `keytool`; keep it out of git (`*.jks` is already ignored).
- **Leave R8 minification off for now.** The JNI bridge and reflection need keep rules, and there's no time to test them.
- **Later, separate audit:** LiteRT-LM and ARCore/SceneView may be unused by Pramaan's screens. Removing them saves size but touches the base platform, so not before submission.

## 3. GitHub
1. **Push from your PC.** It's the source of truth, and all the patches are applied there.
   ```bat
   gh repo create pramaan --public --source . --remote origin
   git push -u origin master
   ```
   Or create the repo on github.com and then `git remote add origin … && git push -u origin master`.
2. **Before the first push:**
   - `git status` should be clean;
   - confirm nothing secret is tracked: `git ls-files | findstr /i "key jks keystore local.properties .env"`, which should print nothing;
   - decide which untracked docs to commit. Recommended: `docs/forms/`, `docs/verified_qa/`. Keep out: the Muse prompts and the `out-some` folder (already ignored).
3. **Repo size is fine (~22 MB):**
   - models, APKs, `.db` files and logs are already ignored;
   - `third_party/whisper.cpp` (23 MB) is vendored, which is acceptable;
   - the 24 MB `assets/guide/*.db` is tracked. It goes once the guide downloads from Hugging Face, but can stay for now.
4. **Put the APK in a GitHub Release**, not in the repo (`v1.0-hackathon`; assets up to 2 GB each). The README links to it.
5. **Licence:** pick one for your code (Apache-2.0 suggested). The vendored whisper.cpp keeps its MIT licence.

## 4. README outline (to write after the decisions)
1. Pramaan in one paragraph: what it is, for whom, offline-first, the Meta models used.
2. Screenshots or GIF and the 3 demo workflows.
3. **Try it:**
   - download the APK from Releases;
   - on first run, Setup downloads ~2.3 GB over Wi-Fi;
   - or copy the files to Downloads for offline install.
4. Architecture diagram: phone (Llama 3.2 3B, Whisper, e5 + re-ranker, ML Kit, fixed rules) ↔ optional cloud (Muse Spark 1.3, Muse Voice Transcribe, SAM 3.1) through one Local/Cloud setting.
5. Privacy and safety: data stays on the phone (encrypted), identifiers are scrubbed before any cloud call, danger signs are fixed rules, AI values show their evidence and the ASHA approves them.
6. Build from source: Android Studio, NDK 26.1, `gradlew assembleRelease`, host tests.
7. Model and data sources with their licences (table linking the Hugging Face files).
8. Honest status: tested on device / not yet measured (from `docs/benchmark/final.md`).
9. Team, hackathon, licence.

## 5. Suggested order and timing
| Step | What | Risk | Time |
|---|---|---|---|
| A | Measure the APK (PowerShell above) | none | 5 min |
| B | Release build, stripped; remove from `assets/` whatever the code already finds in `filesDir` (Whisper) | low | 1 h incl. build |
| C | Push to GitHub, Release with the APK, README v1 | none | 1 h |
| D | Hugging Face repo: upload files + `manifest.json` + licences | none | 1–2 h (upload time) |
| E | Setup wizard downloads from the manifest (DownloadManager → existing installers) | medium (new code, needs a phone test) | 3–4 h |
| F | Re-ranker / sherpa / e5 load from files; drop them from the APK | medium (platform files) | 1–2 h |

**Before the 5 Nov 06:30 deadline:** A, B, C, and D if upload time allows. E and F change how the app gets its models, and a broken setup would stop judges completely. Do them only if there's time to test the full first-run flow on a phone that has never had the app. Otherwise ship the current "copy files to Downloads" setup, documented in the README with Hugging Face links, which already works.
