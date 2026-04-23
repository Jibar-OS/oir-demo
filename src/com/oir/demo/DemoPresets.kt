/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.oir.demo

/**
 * Canned inputs for the demo. Keeping them in one object so the Loom
 * recording never hits input friction (no typing, no file pickers).
 *
 * Asset paths assume you've pushed the demo assets to /product/etc/oir/
 * alongside the model files — simplest place on cvd that's readable by
 * every process and doesn't require MANAGE_EXTERNAL_STORAGE.
 */
internal object DemoPresets {
    // Models are already at /product/etc/oir/<model>.gguf etc — the
    // OIR runtime's default-model resolution handles that. We only
    // need to name the non-model assets the app passes as inputs.
    const val ASSET_IMAGE_BUS = "/product/etc/oir/bus.jpg"
    // WAV, not raw PCM: oird's submitTranscribe path uses readWav16
    // which hard-requires a RIFF/WAVE header (16-bit, mono, 16 kHz).
    // Demo prep pushes a ~5 s clip here alongside the models.
    const val ASSET_VOICE_WAV = "/product/etc/oir/voice-sample.wav"

    // Short, deterministic prompts. Bound maxTokens so the Loom stays
    // snappy — 128 tokens on a CPU qwen2.5-0.5b is ~8-15 s, enough to
    // see streaming but not so long it kills pacing.
    const val PROMPT_COMPLETE =
        "In three sentences, explain why on-device AI matters for privacy. " +
        "Be concrete."

    // text.translate takes a plain input + translation options (the
    // service prepends its own instruction template).
    const val PROMPT_TRANSLATE = "On-device intelligence keeps your data on your device."
    const val TRANSLATE_SRC_LANG = "en"
    const val TRANSLATE_TGT_LANG = "es"

    // text.embed: one paragraph → pooled vector.
    const val PROMPT_EMBED =
        "The runtime schedules requests across llama, whisper, and ORT " +
        "backends with per-capability priority."

    // Ceiling for text-shape max_tokens. The SDK reads this from the
    // Options bundle and forwards it.
    const val MAX_TOKENS = 128
    const val TEMPERATURE = 0.7f

    // vision.detect doesn't take a prompt — just the image path.

    // Priority-race mode tunables. "Audio-first scheduling" — the
    // cross-backend Scheduler in oird is a single priority queue with
    // `clamp(hardware_concurrency, 4, 16)` workers. On cvd that's 4.
    // To make priority visible, we need MORE queued work than
    // workers — otherwise every task admits immediately and nobody
    // waits. Six race texts saturates the 4 workers with 2 queued;
    // when audio.transcribe arrives at t+400 ms, it slots into the
    // queue at priority 0 (vs. text's 10) and dequeues ahead of
    // the two queued texts as soon as the first worker frees.
    //
    // Wording discipline: this is queue-admission priority, NOT
    // preemption. We do not kick a running llama_context off.
    const val PRIORITY_RACE_TEXT_COUNT = 6
    const val PRIORITY_RACE_AUDIO_DELAY_MS = 400L
}
