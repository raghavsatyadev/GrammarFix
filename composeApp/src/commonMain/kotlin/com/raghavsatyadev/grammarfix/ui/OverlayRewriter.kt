package com.raghavsatyadev.grammarfix.ui

import com.raghavsatyadev.grammarfix.engine.CorrectionSource
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.EngineMode
import com.raghavsatyadev.grammarfix.engine.InferenceEngine
import com.raghavsatyadev.grammarfix.engine.LlmRuntime
import com.raghavsatyadev.grammarfix.engine.TonePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs the overlay's rewrite panel (#31): one rewrite of the field's text at a time, in the tone of
 * whichever chip was tapped last.
 *
 * Every rewrite is the on-device model's, through [InferenceEngine.generateCorrection] — the same
 * path the Tones tab and the Playground use, as `.agents/rules/pure-local-llm-inference.md`
 * requires. A typed instruction is no exception: it becomes a transient tone
 * ([DefaultTonePresets.forInstruction]) and goes down that same path.
 *
 * Runs are recorded as [CorrectionSource.OVERLAY], because that is where they come from. A typed
 * instruction is recorded as [DefaultTonePresets.INSTRUCTION_ID]; analytics stores only a tone's
 * id, so the instruction's own words never reach the database.
 *
 * Platform-free so the rules — a new chip cancels the rewrite in flight, a late answer never
 * overwrites a newer request — are tested without a window.
 */
class OverlayRewriter(
  private val engine: InferenceEngine,
  private val scope: CoroutineScope,
  private val engineMode: () -> EngineMode = { LlmRuntime.engineMode.value },
) {

  private val _state = MutableStateFlow<SuggestionOverlayState.Rewrite?>(null)

  /** The panel, or null while it is closed. */
  val state: StateFlow<SuggestionOverlayState.Rewrite?> = _state.asStateFlow()

  private var job: Job? = null

  /**
   * Counts requests, so an answer can tell whether it is still the one the panel is waiting for.
   */
  private var generation = 0L

  /**
   * Opens the panel over [text] and starts rewriting it in the tone stored as selected
   * ([selectedId]), falling back to the first tone if that one no longer exists.
   */
  fun open(text: String, tones: List<TonePreset>, selectedId: String) {
    val tone = tones.firstOrNull { it.id == selectedId } ?: tones.firstOrNull() ?: return
    _state.value =
      SuggestionOverlayState.Rewrite(original = text, tones = tones, selectedId = tone.id)
    run(tone, instruction = null)
  }

  /**
   * Rewrites in the tone with [id], cancelling whatever was running.
   *
   * Tapping the chip that is already showing its answer, or already running, does nothing: sampling
   * is greedy, so the model would only give the same answer again. A failed one is retried.
   */
  fun select(id: String) {
    val current = _state.value ?: return
    val tone = current.tones.firstOrNull { it.id == id } ?: return
    val same = current.selectedId == id && current.instruction == null
    if (same && current.result !is RewriteResult.Failed) return
    run(tone, instruction = null)
  }

  /** Rewrites following the user's own [instruction], as a one-off tone. Blank ones are ignored. */
  fun instruct(instruction: String) {
    if (_state.value == null) return
    val words = instruction.trim()
    if (words.isEmpty()) return
    run(DefaultTonePresets.forInstruction(words), instruction = words)
  }

  /** Closes the panel and drops the rewrite in flight. */
  fun close() {
    job?.cancel()
    job = null
    generation++
    _state.value = null
  }

  private fun run(tone: TonePreset, instruction: String?) {
    val panel = _state.value ?: return
    // Cancelling reaches the model itself: the driver stops generating rather than finishing an
    // answer nobody is waiting for, so the next chip starts as soon as it can.
    job?.cancel()
    val request = ++generation
    _state.value =
      panel.copy(selectedId = tone.id, instruction = instruction, result = RewriteResult.Loading)

    job = scope.launch {
      val result =
        engine.generateCorrection(
          text = panel.original,
          // The user's fast mode would skip reasoning for Polish only: every other tone declares
          // `usesThinking`, which keeps the reasoning step whatever this says.
          enforceNoThink = true,
          engineMode = engineMode(),
          tone = deeperPolish(tone),
          source = CorrectionSource.OVERLAY,
          isStillWanted = { generation == request },
        )
      if (generation != request) return@launch
      val current = _state.value ?: return@launch
      _state.value =
        current.copy(
          result =
            if (result.isSuccess) RewriteResult.Ready(result.correctedText, result.diffs)
            else RewriteResult.Failed(result.errorMessage.orEmpty())
        )
    }
  }

  /**
   * Polish in the panel is the live check's own prompt, which runs greedily without reasoning. Run
   * the same way it could only return what the check already found — for text the check passed, the
   * user's text unchanged. Here the user asked for it, so it takes the reasoning step, and the room
   * that step needs, to catch what the fast pass missed.
   */
  private fun deeperPolish(tone: TonePreset): TonePreset =
    if (tone.id != DefaultTonePresets.POLISH_ID) tone
    else
      tone.copy(
        usesThinking = true,
        maxOutputTokens = maxOf(tone.maxOutputTokens, POLISH_THINKING_TOKENS),
      )

  private companion object {
    /** Room for Polish's reasoning plus its answer, as the other reasoning tones have. */
    const val POLISH_THINKING_TOKENS = 512
  }
}
