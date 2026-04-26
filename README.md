# oir-demo — OirDemo Mission Control

Reference app that exercises 5 of OIR's 12 capabilities concurrently — text.complete, text.translate, text.embed, audio.transcribe, vision.detect. Named "Mission Control" because the UI is a tile-per-capability grid with a Gantt timeline showing overlapping work.

## What it demos

- **Fire All** — launches one submit per capability at once. Shows that the platform can actually serve 5 concurrent submits across 4 distinct loaded models (Qwen shared by complete + translate) without serialising them behind one lock.
- **Priority Race** — fires N background `text.complete` submits while tapping an `audio.*` tile, to observe cross-priority preemption.
- **Cancel All** — every in-flight submit cancels cleanly, including ones streaming tokens mid-response.

The Gantt timeline at the bottom makes the concurrency visible — viewers see bars overlap, audio admit ahead of queued text, and bars truncate on cancel.

## Installation

Built as a platform-signed privileged app. Installs to `/system/priv-app/OirDemo/OirDemo.apk`.

Wired into the cvd reference build via `PRODUCT_PACKAGES += OirDemo` in [`device_google_cuttlefish`](https://github.com/jibar-os/device_google_cuttlefish).

## Runtime prerequisites

The tiles you see "RUNNABLE" vs "MODEL_MISSING" depend on what's baked into `/product/etc/oir/`. The reference Cuttlefish build bundles 3 models (Qwen + MiniLM + whisper-tiny) covering 4 of the 5 demo tiles end-to-end. vision.detect needs an OEM-supplied detector (RT-DETR Apache 2.0 recommended) — the tile cleanly errors as MODEL_MISSING until that's baked.

## Source dependencies

- [`oir-sdk`](https://github.com/jibar-os/oir-sdk) — client SDK
- Android 16 / Baklava SDK via `platform_apis`

## See also

[`github.com/Jibar-OS/JibarOS`](https://github.com/Jibar-OS/JibarOS) for the capability model and what each tile does.

