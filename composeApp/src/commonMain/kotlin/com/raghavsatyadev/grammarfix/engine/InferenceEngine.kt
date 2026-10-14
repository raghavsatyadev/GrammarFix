package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.flow.StateFlow

enum class EngineMode(val displayName: String, val description: String) {
  QWEN3_GGUF(
    displayName = "Qwen 3 1.7B GGUF (Local File)",
    description = "Universal 1.2 GB INT4 GGUF model running locally across all Android devices.",
  )
}

data class CorrectionResult(
  val originalText: String,
  val correctedText: String,
  val latencyMs: Long,
  val isNonThinkingMode: Boolean,
  val diffs: List<DiffItem>,
  val engineMode: EngineMode = EngineMode.QWEN3_GGUF,
  /** Which tone produced [correctedText]; the default is the proofreading pass. */
  val tonePresetId: String = DefaultTonePresets.POLISH_ID,
  val requestTimeMillis: Long = 0L,
  val responseTimeMillis: Long = 0L,
  val inputWordCount: Int = 0,
  val outputWordCount: Int = 0,
  val tokensPerSecond: Float = 0f,
  /**
   * How many requests the text was split into. 1 for anything that fitted one context window, 0
   * when inference never returned at all.
   */
  val chunks: Int = 1,
  val isSuccess: Boolean = true,
  /** What the UI shows the user. Never persisted — see [errorCode]. */
  val errorMessage: String? = null,
  /** The same failure as one of our own values, which is the only form analytics stores. */
  val errorCode: CorrectionErrorCode? = null,
  /**
   * Whether anyone was still waiting for this result when it arrived.
   *
   * False for a result the caller threw away — the overlay's field changed while the model was
   * running, so the correction describes text that no longer exists. Analytics keeps the row,
   * because the inference really did run, and marks it as not delivered so a chart of "corrections
   * made" counts what the user actually received. See [CorrectionRecorder] and issue #34.
   */
  val wasDelivered: Boolean = true,
)

interface InferenceEngine {
  suspend fun initialize(modelPath: String? = null): Boolean

  suspend fun isModelLoaded(): Boolean

  /**
   * Rewrites [text] with [tone]. [enforceNoThink] is the user's fast-mode preference: a tone that
   * declares [TonePreset.usesThinking] keeps the model's reasoning step regardless.
   *
   * [source] says which surface asked, and decides whether the run is recorded at all. It defaults
   * to real usage: the Playground has to say so explicitly, so a forgotten argument can only ever
   * add a genuine correction to the user's statistics, never a synthetic benchmark run.
   *
   * [isStillWanted] is asked once, as late as possible, whether the answer is still of any use —
   * the overlay checks that the field it was computed for still holds the same text. It is asked
   * here rather than by the caller afterwards so that the answer analytics records and the answer
   * the caller acts on are the same one; two separate checks can disagree, and the row would then
   * count a correction nobody ever saw. A caller with nothing to wait for leaves it alone.
   */
  suspend fun generateCorrection(
    text: String,
    enforceNoThink: Boolean = true,
    engineMode: EngineMode = EngineMode.QWEN3_GGUF,
    tone: TonePreset = DefaultTonePresets.POLISH,
    source: CorrectionSource = CorrectionSource.OVERLAY,
    isStillWanted: suspend () -> Boolean = { true },
  ): CorrectionResult

  /**
   * Reads the tone of [text] (#32): labels from [ToneDetection.LABELS] and a one-line summary.
   * Queues behind any correction in flight, since both share one model. Cancelling the caller stops
   * the model mid-answer.
   *
   * With [ToneReadMode.SCORE] (the default) the model scores each label instead of writing them,
   * and the result carries no summary yet ([ToneDetectionResult.summaryPending]); ask
   * [summarizeTone] for it only when it will be shown. A driver that cannot score answers as
   * [ToneReadMode.GENERATE] does: labels and summary from one generated answer.
   *
   * Nothing is recorded here: the caller decides whether the reading was delivered and stores only
   * its labels.
   */
  suspend fun detectTone(
    text: String,
    engineMode: EngineMode = EngineMode.QWEN3_GGUF,
    mode: ToneReadMode = ToneReadMode.SCORE,
  ): ToneDetectionResult

  /**
   * The one-line summary of how [text] comes across, written after the [labels] a scored read
   * chose, or empty when the model gave none. Throws on inference failure.
   */
  suspend fun summarizeTone(
    text: String,
    labels: List<String>,
    engineMode: EngineMode = EngineMode.QWEN3_GGUF,
  ): String

  fun unloadModel()

  /** What inference is doing now, and how much it has done: what the idle unload watches. */
  val activity: StateFlow<InferenceActivity>

  /**
   * Frees the loaded model if no request is running or waiting for it; true if one was freed.
   *
   * Taken under the same lock as a correction, so it can never pull the model out from under one.
   * The next correction loads it again.
   */
  suspend fun releaseIfIdle(): Boolean
}

/**
 * Requests in progress ([running], including any queued behind another) and requests completed
 * ([finished]).
 *
 * [finished] is what lets a watcher of a conflated [StateFlow] see a correction that started and
 * ended between two reads: [running] would look unchanged, the count would not.
 */
data class InferenceActivity(val running: Int = 0, val finished: Long = 0L) {
  val isRunning: Boolean
    get() = running > 0
}
