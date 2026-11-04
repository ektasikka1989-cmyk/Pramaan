---
license: llama3.2
language:
- en
- hi
tags:
- llama
- llama-3.2
- on-device
- android
- healthcare
pretty_name: Pramaan on-phone Meta models
---

# Pramaan — on-phone Meta models

**Built with Llama.**

This dataset holds one file, `Pramaan_Meta_Models.zip`, which the [Pramaan](https://github.com/ektasikka1989-cmyk/Pramaan)
Android app downloads in its first-run setup wizard. Pramaan is an offline-first assistant for India's ASHA community
health workers: one spoken visit note fills the official forms, the MCP card and the registers, and answers come from
the official ASHA guides with the source page — on the phone, with no internet.

## Contents
| Model | Use in Pramaan | Licence |
|---|---|---|
| Meta **Llama 3.2 3B Instruct** (GGUF, Q4_K_M) | Offline answers from the guides, voice note → official form, explanations (llama.cpp) | Llama 3.2 Community License |
| Offline vision models used by the app (ONNX) | Object and kit detection on the phone | Each model's own licence |

The app installs the zip with a streaming installer (`MetaModelStore.installZip`) and deletes it afterwards.

## Licence and attribution
- Llama 3.2 is licensed under the **Llama 3.2 Community License**, Copyright © Meta Platforms, Inc. All Rights Reserved.
  Full text: https://www.llama.com/llama3_2/license/ · Acceptable Use Policy: https://www.llama.com/llama3_2/use-policy/
- Use of Llama 3.2 must follow that licence and the Acceptable Use Policy.
- Other models in the zip keep their original licences.
- The Pramaan app's own code is MIT: https://github.com/ektasikka1989-cmyk/Pramaan
