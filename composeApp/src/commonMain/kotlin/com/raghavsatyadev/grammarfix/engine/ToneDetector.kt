package com.raghavsatyadev.grammarfix.engine

import com.raghavsatyadev.grammarfix.data.ToneEventDao
import com.raghavsatyadev.grammarfix.data.ToneEventEntity
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a tone read of one text has got to. */
sealed interface ToneStatus {
  /** Nothing asked yet, or waiting for typing to settle. */
  data object Idle : ToneStatus

  /** The model is reading the text. */
  data object Detecting : ToneStatus

  data class Ready(val reading: ToneReading) : ToneStatus

  /** [message] is for display only and never stored. */
  data class Failed(val message: String) : ToneStatus
}

/** The tone read for [text], the field's text as the grammar check last saw it. */
data class ToneCheck(val text: String, val status: ToneStatus)

/** Where delivered tone readings are kept: their labels only, never the text (#19). */
interface ToneRecorder {
  suspend fun record(reading: ToneReading, latencyMs: Long, engineMode: EngineMode)

  /** The labels of the last reading kept, or null when there is none. */
  suspend fun latestLabels(): List<String>?

  object None : ToneRecorder {
    override suspend fun record(reading: ToneReading, latencyMs: Long, engineMode: EngineMode) {}

    override suspend fun latestLabels(): List<String>? = null
  }
}

/** [ToneRecorder] over `tone_event`. The summary is dropped here, before anything is written. */
class RoomToneRecorder(
  private val dao: ToneEventDao,
  private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ToneRecorder {
  override suspend fun record(reading: ToneReading, latencyMs: Long, engineMode: EngineMode) {
    val labels = ToneDetection.encodeLabels(reading.labels)
    if (labels.isEmpty()) return
    dao.insert(
      ToneEventEntity(at = now(), labels = labels, engine = engineMode.name, latencyMs = latencyMs)
    )
  }

  override suspend fun latestLabels(): List<String>? =
    dao.latest()?.let { ToneDetection.decodeLabels(it.labels) }?.takeIf { it.isNotEmpty() }
}

/**
 * Decides when the tone of the field's text is read (#32), and holds the answer.
 *
 * Two ways in:
 * - [onGrammarChecked] with `autoDetect` on — the "Detect tone while typing" setting. The read
 *   starts once the grammar check for that text has finished *and* typing has been still for
 *   [idleMs]; both, because the two share one model and a read that jumps the queue would delay the
 *   fixes the user is waiting for.
 * - [detectNow]: the "Detect tone" button, when the setting is off.
 *
 * Any edit ([onTextChanged]) cancels a read that is waiting or running. Cancelling reaches the
 * model itself: [InferenceEngine.detectTone] goes through the driver, whose watcher aborts the
 * native call (`g_abort_request`), so a stale read never holds the model while the next grammar
 * check waits.
 *
 * A reading that arrives is kept only if nothing replaced its request in the meantime, and only
 * then is it recorded — as labels, through [recorder].
 *
 * Platform-free, so the timing rules are tested without a device.
 */
class ToneDetector(
  private val engine: InferenceEngine,
  private val scope: CoroutineScope,
  private val recorder: ToneRecorder = ToneRecorder.None,
  private val idleMs: Long = TYPING_IDLE_MS,
  private val engineMode: () -> EngineMode = { LlmRuntime.engineMode.value },
  private val now: () -> Long = monotonicMillis(),
  /** Scored labels by default; [ToneReadMode.GENERATE] is the #32 path, kept for comparison. */
  private val readMode: ToneReadMode = ToneReadMode.SCORE,
  /**
   * Whether anything shows the summary line. Every surface that shows a reading today (the
   * overlay's tone strip, the Tones tab) shows it too, so this is on; a caller that shows labels
   * only turns it off and a scored read then never generates a word.
   */
  private val showSummary: Boolean = true,
) {

  private val _check = MutableStateFlow<ToneCheck?>(null)

  /** The read for the field's current text; null before any text was seen. */
  val check: StateFlow<ToneCheck?> = _check.asStateFlow()

  private val _lastReading = MutableStateFlow<ToneReading?>(null)

  /** The latest reading delivered in this process, whatever text it was for. */
  val lastReading: StateFlow<ToneReading?> = _lastReading.asStateFlow()

  private var job: Job? = null

  /** Read by the reading coroutine, which runs off the thread that bumps it. */
  @Volatile private var generation = 0L
  private var lastEdit = Long.MIN_VALUE

  /**
   * The field's text changed: whatever was being read is about text that no longer exists, so it
   * stops, and typing is taken to have happened just now.
   */
  fun onTextChanged(text: String) {
    lastEdit = now()
    cancelRead()
    if (_check.value?.text != text) _check.value = ToneCheck(text, ToneStatus.Idle)
  }

  /**
   * The grammar check for [text] finished. With [autoDetect] on, reads its tone once typing has
   * been still for [idleMs] — right away if it already has.
   */
  fun onGrammarChecked(text: String, autoDetect: Boolean) {
    if (!autoDetect || text.isBlank()) return
    val current = _check.value
    // Already read, or being read, for this very text: a repeat check must not start it again.
    if (current?.text == text && current.status !is ToneStatus.Idle) return
    val still = if (lastEdit == Long.MIN_VALUE) idleMs else now() - lastEdit
    start(text, waitMs = (idleMs - still).coerceAtLeast(0L))
  }

  /** Reads [text] now, replacing any read in flight. The button's path. */
  fun detectNow(text: String) {
    if (text.isBlank()) return
    start(text, waitMs = 0L)
  }

  /** Stops any read and forgets the field, e.g. when the assistant closes. */
  fun clear() {
    cancelRead()
    _check.value = null
  }

  private fun cancelRead() {
    job?.cancel()
    job = null
    generation++
  }

  private fun start(text: String, waitMs: Long) {
    cancelRead()
    val request = generation
    _check.value = ToneCheck(text, ToneStatus.Idle)
    job = scope.launch {
      if (waitMs > 0) delay(waitMs)
      _check.value = ToneCheck(text, ToneStatus.Detecting)
      val result = engine.detectTone(text, engineMode(), readMode)
      if (request != generation) return@launch
      val reading = result.reading
      _check.value =
        ToneCheck(
          text,
          if (reading != null) ToneStatus.Ready(reading)
          else ToneStatus.Failed(result.errorMessage.orEmpty()),
        )
      if (reading != null) {
        _lastReading.value = reading
        try {
          recorder.record(reading, result.latencyMs, result.engineMode)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // A reading the user can see is never lost to a failed write.
          e.printStackTrace()
        }
        if (result.summaryPending && showSummary) addSummary(text, reading, result, request)
      }
    }
  }

  /**
   * The labels are on screen; now the summary line under them. Same cancellation as the read: an
   * edit stops it, and a summary for text that has changed is dropped. A summary that fails leaves
   * the labels as they are.
   */
  private suspend fun addSummary(
    text: String,
    reading: ToneReading,
    result: ToneDetectionResult,
    request: Long,
  ) {
    val summary =
      try {
        engine.summarizeTone(text, reading.labels, result.engineMode)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        e.printStackTrace()
        return
      }
    if (request != generation || summary.isBlank()) return
    val full = reading.copy(summary = summary)
    _check.value = ToneCheck(text, ToneStatus.Ready(full))
    _lastReading.value = full
  }

  companion object {
    /**
     * How long typing has to have stopped before the tone is read: the issue asks for 1-2 s. Long
     * enough that a pause between words does not start a read the next keystroke throws away.
     */
    const val TYPING_IDLE_MS = 1_500L

    private fun monotonicMillis(): () -> Long {
      val origin = TimeSource.Monotonic.markNow()
      return { origin.elapsedNow().inWholeMilliseconds }
    }
  }
}
