# OirDemo — demo prep checklist

One-shot setup for recording the Loom on cvd. Follow top-to-bottom.

## 1. Assets the app expects

The app hard-codes five asset paths via `DemoPresets.kt`. All five
must be present on the device before launch, or the corresponding
tile will surface an error (which ruins the Loom's pacing, not the
narrative — the runtime's "MODEL_MISSING" error handling is
honest about missing assets).

| Asset | Path on device | Source |
|---|---|---|
| qwen2.5-0.5b (text.complete, text.translate) | `/product/etc/oir/qwen2.5-0.5b-instruct-q4_k_m.gguf` | built into system.img (already present) |
| all-MiniLM-L6-v2 (text.embed) | `/product/etc/oir/all-MiniLM-L6-v2.Q8_0.gguf` | built into system_ext.img (verify) |
| whisper-tiny-en (audio.transcribe) | `/product/etc/oir/whisper-tiny-en.Q5.bin` | built into system_ext.img (verify) |
| rtdetr-r50vd-coco (vision.detect) | `/product/etc/oir/rtdetr-r50vd-coco.onnx` + `.classes.json` sidecar | `~/oir-models/` on aaosp-builder |
| voice-sample.wav (transcribe input) | `/product/etc/oir/voice-sample.wav` | see §2 — must be created |
| bus.jpg (detect input) | `/product/etc/oir/bus.jpg` | `~/oir-models/` on aaosp-builder |

### What we do NOT have — tiles NOT included

These capabilities are declared in the registry but no model ships
on cvd today. OirDemo intentionally skips their tiles to keep the
recording clean:

- `audio.synthesize` (Piper voices)
- `audio.vad` (Silero VAD)
- `vision.embed` (SigLIP/CLIP)
- `vision.describe` (SmolVLM + mmproj pair — available in
  `~/oir-models/` but skipped for v1 unless it's rock solid)
- `text.classify`, `text.rerank`, `vision.ocr` (declared unbacked by
  design; OEM policy)

Adding any of the above is a matter of pushing the model(s) and
flipping a constant in `DemoPresets.kt` + registering a tile in
`CapabilityId`.

## 2. Creating the voice WAV

`oird.submitTranscribe` uses `readWav16` which hard-requires a
RIFF/WAVE file: **16-bit signed mono, 16 kHz**. Raw PCM with a
`.pcm` extension will fail the magic-bytes check.

From a Mac:

```bash
say "The runtime schedules requests across llama, whisper, and ORT \
backends with per-capability priority, cancellation, and concurrent \
execution." -o /tmp/voice-sample.aiff
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/voice-sample.aiff voice-sample.wav
```

From Linux with espeak-ng + ffmpeg:

```bash
espeak-ng -w /tmp/voice-sample.raw.wav \
  "The runtime schedules requests across llama, whisper, and ORT backends."
ffmpeg -i /tmp/voice-sample.raw.wav -ac 1 -ar 16000 -sample_fmt s16 voice-sample.wav
```

Keep the clip under ~10 seconds so transcribe completes inside the
Loom's pacing (whisper-tiny is fast, but not instant on cvd CPU).

## 3. Push everything

Assuming aaosp-builder is up and cvd is running:

```bash
ADB=~/aaosp/out/host/linux-x86/bin/adb

$ADB root
$ADB remount
$ADB shell mkdir -p /product/etc/oir

# Models — already baked into the system.img by the AOSP build, but
# if you're working from an older image push them explicitly:
PROD_OUT=~/aaosp/out/target/product/vsoc_x86_64/product/etc/oir
$ADB push $PROD_OUT/qwen2.5-0.5b-instruct-q4_k_m.gguf /product/etc/oir/
$ADB push $PROD_OUT/all-MiniLM-L6-v2.Q8_0.gguf        /product/etc/oir/   2>/dev/null || true
$ADB push $PROD_OUT/whisper-tiny-en.Q5.bin            /product/etc/oir/   2>/dev/null || true
$ADB push ~/oir-models/rtdetr-r50vd-coco.onnx         /product/etc/oir/
$ADB push ~/oir-models/bus.jpg                        /product/etc/oir/

# The WAV you created in §2:
$ADB push voice-sample.wav /product/etc/oir/

# Install the demo APK (built via `m OirDemo` — lands in system_ext
# priv-app because of privileged: true in Android.bp):
$ADB install -r ~/aaosp/out/target/product/vsoc_x86_64/system_ext/priv-app/OirDemo/OirDemo.apk
# (if `install` fails with INSTALL_FAILED_SHARED_USER_INCOMPATIBLE
# because of the platform cert — install via the system partition
# instead: adb push OirDemo.apk /system_ext/priv-app/OirDemo/ then
# reboot.)

# Grant the three OIR signature permissions. On a signed install
# these grant automatically; sideloaded APK may need:
$ADB shell pm grant com.oir.demo oir.permission.USE_TEXT
$ADB shell pm grant com.oir.demo oir.permission.USE_AUDIO
$ADB shell pm grant com.oir.demo oir.permission.USE_VISION
```

## 4. Pre-warm before recording

First-time model loads on cvd take 2-5 seconds each (disk → mmap →
llama_context_new / whisper_init / Ort::Session ctor). If the Loom
opens with a cold app, the first Fire All tile will sit on "QUEUED"
for five seconds while qwen loads, and it'll look broken.

Pre-warm before hitting record:

```bash
$ADB shell cmd oir warm text.complete
$ADB shell cmd oir warm text.embed
$ADB shell cmd oir warm audio.transcribe
$ADB shell cmd oir warm vision.detect
```

Then launch the app and record.

## 5. Launch + Loom flow

```bash
$ADB shell am start -n com.oir.demo/.MainActivity
```

Recommended Loom take:
1. App opens. Five tiles, idle. Point at the top bar + HUD.
2. Tap **Fire All**. Watch five bars appear on the timeline,
   overlapping — that's concurrency. Text tiles stream, detect
   lights up once, embed shows dim + norm.
3. Tap **Reset**. Tap **Priority Race**. Watch six race-text bars
   queue, then audio.transcribe slot in. Call out the HUD event log
   showing audio's earlier start time despite being queued last.
4. Tap **Fire All** again. While tiles are still streaming, tap
   **Cancel All**. Watch bars truncate mid-flight and flip amber.

That's the 60-90 second cut.
