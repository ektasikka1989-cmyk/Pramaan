package com.example.arfas

object AndroVoiceModels {
    const val ASR_ENCODER = "voice/asr/encoder.onnx"
    const val ASR_DECODER = "voice/asr/decoder.onnx"
    const val ASR_JOINER = "voice/asr/joiner.onnx"
    const val ASR_TOKENS = "voice/asr/tokens.txt"

    const val TTS_MODEL = "voice/tts/model.onnx"
    const val TTS_TOKENS = "voice/tts/tokens.txt"
    const val TTS_LEXICON = "voice/tts/lexicon.txt"
    const val TTS_DATA_DIR = "voice/tts/espeak-ng-data"

    const val SAMPLE_RATE = 16_000
    const val FEATURE_DIM = 80
}
