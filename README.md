# oir-demo — OirDemo Mission Control

Reference app that exercises every OIR capability concurrently. Named "Mission Control" because the UI is a tile-per-capability grid with a Gantt timeline showing overlapping work.

## What it demos

- **Fire All** — launches one submit per capability at once. Shows that the platform can actually serve six concurrent loads without serialising them all behind one lock.
- **Priority Race** — fires N background `text.complete` submits while tapping an `audio.*` tile, to observe cross-priority preemption.
- **Cancel All** — every in-flight submit cancels cleanly, including ones streaming tokens mid-response.

The Gantt timeline at the bottom makes the concurrency visible — viewers see bars overlap, audio admit ahead of queued text, and bars truncate on cancel.

## Installation

Built as a platform-signed privileged app. Installs to `/system/priv-app/OirDemo/OirDemo.apk`.

Wired into the cvd reference build via `PRODUCT_PACKAGES += OirDemo` in [`device_google_cuttlefish`](https://github.com/jibar-os/device_google_cuttlefish).

## Runtime prerequisites

The tiles you see "RUNNABLE" vs "MODEL_MISSING" depend on what the device has baked into `/product/etc/oir/`. The reference Cuttlefish build bakes six runnable capabilities; the rest show clean `MODEL_MISSING` errors until an OEM supplies the model.

## Source dependencies

- [`oir-sdk`](https://github.com/jibar-os/oir-sdk) — client SDK
- Android 16 / Baklava SDK via `platform_apis`

## See also

[`github.com/Jibar-OS/JibarOS`](https://github.com/Jibar-OS/JibarOS) for the capability model and what each tile does.

