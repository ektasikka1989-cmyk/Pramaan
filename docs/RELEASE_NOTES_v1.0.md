**Pramaan v1.0 — offline AI assistant for India's ASHA health workers** (Meta Global AI Developer Hackathon 2026)

### Install
1. Download **Pramaan-v1.0-hackathon.apk** below (~700 MB — it carries offline speech recognition, the multilingual search models and the official guide database, so the app works without internet) and install it on an Android phone (Android 7.0+, arm64, 6 GB RAM recommended). Allow "install unknown apps" when asked.
2. Open Pramaan. The **setup wizard** has 3 steps (each can be skipped):
   - **Offline AI models** — downloads the Meta models (3.3 GB) from Hugging Face. Use Wi-Fi; about 7 GB must be free while installing.
   - **Health guide** — installed from inside the app.
   - **Meta AI key (optional)** — create one at https://dev.meta.ai/, paste it and tap Test and save.
3. Open **Demo** and pick a workflow: new pregnancy, newborn day-7 visit, or sick child.

### What it does
- Ask the official ASHA guides — answers with the exact page, offline.
- Speak one visit note — the official form, MCP card, registers and drug-kit stock update together; the ASHA confirms first.
- Photo reading of slips, MCP cards and medicine strips — on the phone, or with Meta Muse Spark online (Aadhaar blacked out first).
- One setting, Local first / Cloud first, for every AI action; each answer is tagged with the model that produced it.

### Meta models
Llama 3.2 3B Instruct on the phone (llama.cpp) · Muse Spark 1.3, Muse Voice Transcribe and SAM 3.1 through the Meta Model API.

All people in the sample village are fictional. Built with Llama.
