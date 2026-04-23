/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.oir.demo

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.oir.OpenIntelligence
import com.oir.errors.OirCancelledException
import com.oir.models.CompletionOptions
import com.oir.models.TranslationOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OIR Mission Control — concurrency / priority / cancellation showcase.
 *
 * One screen, five capability tiles, three scenario buttons
 * (Fire All / Priority Race / Cancel All). The goal is to make the
 * runtime visible: what's queued, what's running, what's streaming,
 * what got cancelled — things a chatbot UI never surfaces.
 *
 * Wording discipline: we say "priority scheduling" and "audio-first
 * admission", NOT "preemption". The runtime's v0.6 ContextPool grants
 * leases in priority order, which means an audio request admitted
 * while text work is queued jumps ahead at lease time; it does NOT
 * kick currently-running text off a ctx.
 */
class MainActivity : Activity() {

    private lateinit var tileContainer: LinearLayout
    private lateinit var hudCounters: TextView
    private lateinit var hudEventLog: TextView
    private lateinit var timeline: TimelineView

    private val tiles = mutableMapOf<CapabilityId, TileHolder>()
    // Jobs keyed by a unique request id. Tile-bound requests use the
    // capability name (one-per-tile, idempotent). Priority-race
    // background requests use per-instance ids like "race-text-1"
    // so the race scenario can actually queue N of the same shape.
    private val jobs = mutableMapOf<String, Job>()

    // One scope for the whole demo. Child jobs get cancelled together
    // via Cancel All. SupervisorJob so a failure in one tile doesn't
    // take down the other four.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // HUD state. Updated on Main — cheap int arithmetic, no need for
    // atomics.
    private var cQueued = 0
    private var cRunning = 0
    private var cDone = 0
    private var cCancelled = 0
    private var cErrors = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tileContainer = findViewById(R.id.tile_container)
        hudCounters   = findViewById(R.id.hud_counters)
        hudEventLog   = findViewById(R.id.hud_event_log)
        timeline      = findViewById(R.id.hud_timeline)
        hudEventLog.movementMethod = ScrollingMovementMethod()

        for (cap in CapabilityId.values()) {
            tiles[cap] = inflateTile(cap)
        }
        renderCounters()

        findViewById<Button>(R.id.btn_fire_all).setOnClickListener      { fireAll() }
        findViewById<Button>(R.id.btn_priority_race).setOnClickListener { priorityRace() }
        findViewById<Button>(R.id.btn_cancel_all).setOnClickListener    { cancelAll() }
        findViewById<Button>(R.id.btn_reset).setOnClickListener         { reset() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Scenario triggers
    // ------------------------------------------------------------------

    private fun fireAll() {
        for (cap in CapabilityId.values()) launchCapability(cap)
    }

    private fun priorityRace() {
        // Flood the llama pool with N background text.complete jobs
        // (no tile — each is an independent request instance that
        // exists purely to saturate the pool). Then, after a short
        // delay, queue audio.transcribe on its tile. The runtime
        // scheduler ranks audio.* above text at lease admission
        // time, so the audio tile should flip RUNNING noticeably
        // sooner than queue order alone would predict.
        //
        // Wording discipline: this is "priority scheduling" at lease
        // admission — the runtime does NOT kick an already-running
        // llama_context off its lease. Audio's advantage is in the
        // queue, not via preemption.
        for (i in 1..DemoPresets.PRIORITY_RACE_TEXT_COUNT) {
            launchRaceText(i)
        }
        scope.launch {
            delay(DemoPresets.PRIORITY_RACE_AUDIO_DELAY_MS)
            logEvent("priority-race: queueing audio.transcribe (audio-first)")
            launchCapability(CapabilityId.TRANSCRIBE)
        }
    }

    private fun cancelAll() {
        logEvent("cancel-all: cancelling ${jobs.size} job(s)")
        for (job in jobs.values) job.cancel()
        // The onError/finally paths in run* flip tiles to CANCELLED
        // and update counters; anonymous race jobs just update the
        // HUD event log + counters.
    }

    private fun reset() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
        cQueued = 0; cRunning = 0; cDone = 0; cCancelled = 0; cErrors = 0
        renderCounters()
        for ((_, holder) in tiles) holder.setStatus(Status.IDLE, preview = "", elapsed = "")
        hudEventLog.text = ""
        timeline.clear()
    }

    // ------------------------------------------------------------------
    // One capability's full lifecycle (tile-bound)
    // ------------------------------------------------------------------

    private fun launchCapability(cap: CapabilityId) {
        val key = cap.name
        val existing = jobs[key]
        if (existing != null && existing.isActive) {
            // Second tap on the same tile no-ops — avoids confusing
            // double-starts from a stray button press. Priority-race
            // background jobs are tracked under race-text-N keys and
            // bypass this check.
            logEvent("skip: ${cap.label} already running")
            return
        }
        val job = scope.launch { runCapability(cap) }
        jobs[key] = job
    }

    // Background text.complete job for priority-race — no tile, just
    // event log + counter updates. Key "race-text-N" keeps each
    // instance distinct in the jobs map so the skip-if-active gate
    // doesn't collapse them all into one.
    private fun launchRaceText(instance: Int) {
        val key = "race-text-$instance"
        val job = scope.launch { runAnonymousText(key) }
        jobs[key] = job
    }

    private suspend fun runCapability(cap: CapabilityId) {
        val holder = tiles[cap] ?: return
        val barId = cap.name
        val t0 = System.currentTimeMillis()
        cQueued++; renderCounters()
        holder.setStatus(Status.QUEUED, preview = "", elapsed = "queued at t+0")
        timeline.startBar(barId, cap.label, statusColor(Status.QUEUED))
        logEvent("queued ${cap.label}")

        try {
            cQueued--; cRunning++; renderCounters()
            holder.setStatus(Status.RUNNING, preview = "…", elapsed = "running")
            timeline.updateBarColor(barId, statusColor(Status.RUNNING))
            logEvent("started ${cap.label}")

            when (cap) {
                CapabilityId.COMPLETE   -> runComplete(holder, barId, t0)
                CapabilityId.TRANSLATE  -> runTranslate(holder, barId, t0)
                CapabilityId.EMBED      -> runEmbed(holder, t0)
                CapabilityId.TRANSCRIBE -> runTranscribe(holder, barId, t0)
                CapabilityId.DETECT     -> runDetect(holder, t0)
            }

            cRunning--; cDone++; renderCounters()
            val ms = System.currentTimeMillis() - t0
            holder.setStatus(Status.DONE, elapsed = "done in ${ms} ms")
            timeline.endBar(barId, statusColor(Status.DONE))
            logEvent("done ${cap.label} (${ms} ms)")
        } catch (_: OirCancelledException) {
            cRunning--; cCancelled++; renderCounters()
            holder.setStatus(Status.CANCELLED, elapsed = "cancelled at t+${System.currentTimeMillis() - t0} ms")
            timeline.endBar(barId, statusColor(Status.CANCELLED))
            logEvent("cancelled ${cap.label}")
        } catch (t: Throwable) {
            cRunning--; cErrors++; renderCounters()
            val ms = System.currentTimeMillis() - t0
            holder.setStatus(Status.ERROR,
                    preview = t.javaClass.simpleName + ": " + (t.message ?: ""),
                    elapsed = "error at t+${ms} ms")
            timeline.endBar(barId, statusColor(Status.ERROR))
            logEvent("error ${cap.label} · ${t.javaClass.simpleName}")
            // Surface the full stack through logcat so `adb logcat -s OirDemo`
            // gets the real cause; the tile preview only shows the top-level
            // exception class + message, not the chain.
            Log.w("OirDemo", "tile error ${cap.label}: ${t.javaClass.name}: ${t.message}", t)
        } finally {
            // v0.6.9 safety net caught on cvd Fire All: tiles that threw
            // mid-stream (e.g. whisper load fail after setStatus(STREAMING))
            // were visually "stuck" in RUNNING/STREAMING on some sessions
            // — suspected Flow-cancellation edge case where the catch above
            // didn't fire before the scope wound down. Defensive recovery:
            // if the tile is still in a non-terminal state after try/catch,
            // force it to a terminal ERROR. Idempotent when the catch
            // already set ERROR/DONE/CANCELLED (no re-transition).
            val last = holder.currentStatus()
            if (last == Status.QUEUED || last == Status.RUNNING || last == Status.STREAMING) {
                holder.setStatus(Status.ERROR,
                        preview = "no terminal callback",
                        elapsed = "forced-error at t+${System.currentTimeMillis() - t0} ms")
                timeline.endBar(barId, statusColor(Status.ERROR))
                Log.w("OirDemo", "tile ${cap.label} ended in non-terminal state; forced ERROR")
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-capability calls into oir_sdk
    //
    // All suspend calls return here on Dispatchers.Main via the parent
    // scope; the SDK dispatches its own binder work to its internal
    // Default-pool scope. UI updates from within these bodies are
    // therefore main-thread-safe.
    // ------------------------------------------------------------------

    // Each Flow gets `.flowOn(Dispatchers.IO)` so the initial
    // blocking `adapter.submitTokenStream(...)` call inside callbackFlow
    // (which can sit on a model load for seconds on first run) runs on
    // the IO pool, not Main. The onEach / collect downstream stays on
    // Main (the scope's context) so TextView updates are main-thread-
    // safe without extra hopping.

    private suspend fun runComplete(h: TileHolder, barId: String, t0: Long) {
        val options = CompletionOptions(
            maxTokens   = DemoPresets.MAX_TOKENS,
            temperature = DemoPresets.TEMPERATURE,
        )
        h.setStatus(Status.STREAMING)
        timeline.updateBarColor(barId, statusColor(Status.STREAMING))
        val sb = StringBuilder()
        OpenIntelligence.text.completeStream(DemoPresets.PROMPT_COMPLETE, options)
                .flowOn(Dispatchers.IO)
                .onEach { chunk ->
                    sb.append(chunk.text)
                    h.setPreview(sb.toString())
                    h.setElapsed("streaming · t+${System.currentTimeMillis() - t0} ms")
                }
                .collect { /* consumed above */ }
    }

    private suspend fun runTranslate(h: TileHolder, barId: String, t0: Long) {
        val options = TranslationOptions(
            sourceLang = DemoPresets.TRANSLATE_SRC_LANG,
            targetLang = DemoPresets.TRANSLATE_TGT_LANG,
            maxTokens  = DemoPresets.MAX_TOKENS,
        )
        h.setStatus(Status.STREAMING)
        timeline.updateBarColor(barId, statusColor(Status.STREAMING))
        val sb = StringBuilder()
        OpenIntelligence.text.translateStream(DemoPresets.PROMPT_TRANSLATE, options)
                .flowOn(Dispatchers.IO)
                .onEach { chunk ->
                    sb.append(chunk.text)
                    h.setPreview(sb.toString())
                    h.setElapsed("streaming · t+${System.currentTimeMillis() - t0} ms")
                }
                .collect { }
    }

    private suspend fun runEmbed(h: TileHolder, t0: Long) {
        val vec: FloatArray = withContext(Dispatchers.IO) {
            OpenIntelligence.text.embed(DemoPresets.PROMPT_EMBED)
        }
        // ChatGPT review note: don't dump the raw vector — surface
        // dim + L2 norm + a few values. Gives the tile a meaningful
        // "vector ready" readout without flooding the tile.
        var sumSq = 0.0
        for (v in vec) sumSq += (v * v).toDouble()
        val norm = kotlin.math.sqrt(sumSq)
        val head = vec.take(4).joinToString(", ") { "%+.3f".format(it) }
        h.setPreview("dim=${vec.size}  ‖v‖=${"%.3f".format(norm)}  head=[${head}, …]")
    }

    private suspend fun runTranscribe(h: TileHolder, barId: String, t0: Long) {
        h.setStatus(Status.STREAMING)
        timeline.updateBarColor(barId, statusColor(Status.STREAMING))
        val sb = StringBuilder()
        OpenIntelligence.audio.transcribeStream(DemoPresets.ASSET_VOICE_WAV)
                .flowOn(Dispatchers.IO)
                .onEach { chunk ->
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(chunk.text)
                    h.setPreview(sb.toString())
                    h.setElapsed("streaming · t+${System.currentTimeMillis() - t0} ms")
                }
                .collect { }
    }

    // Anonymous (no tile) text.complete request — used by Priority
    // Race to generate independent pool pressure. Success/failure is
    // surfaced via the HUD counters + event log; the COMPLETE tile
    // itself stays untouched so it's free for a separate user-driven
    // submission.
    private suspend fun runAnonymousText(reqId: String) {
        val t0 = System.currentTimeMillis()
        cQueued++; renderCounters()
        timeline.startBar(reqId, reqId, statusColor(Status.QUEUED))
        logEvent("queued $reqId (text.complete)")
        try {
            cQueued--; cRunning++; renderCounters()
            timeline.updateBarColor(reqId, statusColor(Status.RUNNING))
            logEvent("started $reqId")
            withContext(Dispatchers.IO) {
                OpenIntelligence.text.complete(
                    DemoPresets.PROMPT_COMPLETE,
                    CompletionOptions(
                        maxTokens   = DemoPresets.MAX_TOKENS,
                        temperature = DemoPresets.TEMPERATURE,
                    ),
                )
            }
            cRunning--; cDone++; renderCounters()
            timeline.endBar(reqId, statusColor(Status.DONE))
            logEvent("done $reqId (${System.currentTimeMillis() - t0} ms)")
        } catch (_: OirCancelledException) {
            cRunning--; cCancelled++; renderCounters()
            timeline.endBar(reqId, statusColor(Status.CANCELLED))
            logEvent("cancelled $reqId")
        } catch (t: Throwable) {
            cRunning--; cErrors++; renderCounters()
            timeline.endBar(reqId, statusColor(Status.ERROR))
            logEvent("error $reqId · ${t.javaClass.simpleName}")
            Log.w("OirDemo", "race error $reqId: ${t.javaClass.name}: ${t.message}", t)
        }
    }

    private suspend fun runDetect(h: TileHolder, t0: Long) {
        // Plain suspend — detection is one-shot, not streamed.
        val objects = withContext(Dispatchers.IO) {
            OpenIntelligence.vision.detect(DemoPresets.ASSET_IMAGE_BUS)
        }
        // Format: "N objects · top: bus(0.91), person(0.84), …"
        if (objects.isEmpty()) {
            h.setPreview("no objects above threshold")
            return
        }
        val top = objects
                .sortedByDescending { it.score }
                .take(4)
                .joinToString(", ") { "${it.label}(${"%.2f".format(it.score)})" }
        h.setPreview("${objects.size} objects · top: $top")
    }

    // ------------------------------------------------------------------
    // UI wiring
    // ------------------------------------------------------------------

    private fun inflateTile(cap: CapabilityId): TileHolder {
        val view = LayoutInflater.from(this).inflate(R.layout.capability_tile, tileContainer, false)
        tileContainer.addView(view)

        val statusTv     = view.findViewById<TextView>(R.id.tile_status)
        val capabilityTv = view.findViewById<TextView>(R.id.tile_capability)
        val backendTv    = view.findViewById<TextView>(R.id.tile_backend)
        val previewTv    = view.findViewById<TextView>(R.id.tile_preview)
        val elapsedTv    = view.findViewById<TextView>(R.id.tile_elapsed)
        val cancelBtn    = view.findViewById<Button>(R.id.tile_cancel)

        capabilityTv.text = cap.label
        backendTv.text    = cap.backend
        backendTv.setTextColor(backendColor(cap.backend))
        cancelBtn.setOnClickListener { jobs[cap.name]?.cancel() }

        return TileHolder(statusTv, previewTv, elapsedTv, cancelBtn)
    }

    private fun renderCounters() {
        hudCounters.text =
            "queued $cQueued · running $cRunning · done $cDone · cancelled $cCancelled · errors $cErrors"
    }

    private fun logEvent(line: String) {
        val ts = System.currentTimeMillis() % 100000
        val sb = StringBuilder(hudEventLog.text)
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append("t+").append(ts).append(" · ").append(line)
        // Cap the log at ~40 lines so it doesn't balloon during a Loom.
        val lines = sb.split('\n')
        val kept = if (lines.size > 40) lines.subList(lines.size - 40, lines.size).joinToString("\n") else sb.toString()
        hudEventLog.text = kept
    }

    private fun statusColor(status: Status): Int =
            resources.getColor(status.colorRes, theme)

    private fun backendColor(backend: String): Int = when (backend) {
        "llama"   -> resources.getColor(R.color.backend_llama,   theme)
        "whisper" -> resources.getColor(R.color.backend_whisper, theme)
        "ort"     -> resources.getColor(R.color.backend_ort,     theme)
        "mtmd"    -> resources.getColor(R.color.backend_mtmd,    theme)
        else      -> Color.LTGRAY
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    private enum class CapabilityId(val label: String, val backend: String) {
        COMPLETE(  "text.complete",    "llama"),
        TRANSLATE( "text.translate",   "llama"),
        EMBED(     "text.embed",       "llama"),
        TRANSCRIBE("audio.transcribe", "whisper"),
        DETECT(    "vision.detect",    "ort"),
    }

    private enum class Status(val label: String, val colorRes: Int) {
        IDLE(      "IDLE",      R.color.status_idle),
        QUEUED(    "QUEUED",    R.color.status_queued),
        RUNNING(   "RUNNING",   R.color.status_running),
        STREAMING( "STREAMING", R.color.status_streaming),
        DONE(      "DONE",      R.color.status_done),
        CANCELLED( "CANCELLED", R.color.status_cancelled),
        ERROR(     "ERROR",     R.color.status_error),
    }

    private inner class TileHolder(
        val statusTv:  TextView,
        val previewTv: TextView,
        val elapsedTv: TextView,
        val cancelBtn: Button,
    ) {
        // v0.6.9: cached last status so the runCapability finally block can
        // detect a stuck non-terminal state and force-transition to ERROR.
        // Reading statusTv.text back would work too but relies on text==label
        // never being re-styled (bold/unicode variants); keep an explicit
        // enum field instead.
        private var last: Status = Status.QUEUED

        fun setStatus(status: Status, preview: String? = null, elapsed: String? = null) {
            last = status
            statusTv.text = status.label
            statusTv.backgroundTintList =
                ColorStateList.valueOf(resources.getColor(status.colorRes, theme))
            // Cancel button only meaningful while work is in flight.
            cancelBtn.visibility = when (status) {
                Status.QUEUED, Status.RUNNING, Status.STREAMING -> View.VISIBLE
                else -> View.INVISIBLE
            }
            if (preview != null) previewTv.text = preview
            if (elapsed != null) elapsedTv.text = elapsed
        }

        fun currentStatus(): Status = last

        fun setPreview(text: String) { previewTv.text = text }
        fun setElapsed(text: String) { elapsedTv.text = text }
    }
}
