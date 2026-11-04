---
license: mit
language:
- en
- hi
tags:
- healthcare
- rag
- india
- asha
pretty_name: Pramaan ASHA health guide database
---

# Pramaan — ASHA health guide database

`ASHA_Health_Guide_v2.db` is the offline knowledge base of the [Pramaan](https://github.com/ektasikka1989-cmyk/Pramaan)
Android app. It is a SQLite database of passages from **official Government of India documents** (MoHFW / NHM / NHSRC
ASHA training modules, the Mother and Child Protection card, HBNC and HBYC guidelines), each with its source document,
page number, official link and search embeddings, so every answer can cite its page.

The same file is bundled inside the app; the setup wizard downloads this copy only if the bundled one fails to install.

- The source documents remain the property of the Government of India and are cited, not modified.
- The database build (structure, chunking, embeddings) is MIT-licensed, like the Pramaan app.
- Pramaan is a job aid for trained health workers, not medical advice.
