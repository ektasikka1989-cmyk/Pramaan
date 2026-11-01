# Demo media (owner to provide)

The Batch 5 demo reads these files when they exist, and labels the fallback
honestly when they don't. Nothing here is needed to compile.

- `note_en.wav` — ~20 s English recording of the ASHA-style visit note
  (same facts as `ArogyaDemoScript.NOTE_EN`). WAV: 16-bit PCM or float32,
  mono or stereo, any sample rate, up to 60 s. The demo decodes it to 16 kHz
  and feeds it straight into the real speech-to-text (Whisper offline,
  Muse Voice Transcribe online, in Settings order).
- `note_hi.wav` — same note in Hindi (facts as `NOTE_HI`).
- `ors.jpg` — sample photo of ORS sachets laid out separately on a plain
  cloth, for the SAM 3.1 drug-kit scene (online + Meta key only).

Missing files: the voice scene uses the typed note labelled
"typed note (no recording)"; the SAM scene is skipped with an honest card.
