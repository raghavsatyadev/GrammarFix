package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs token generation on an on-device model (Qwen3 GGUF via llama.cpp). Every correction in the
 * app goes through one of these — there is no rule-based fallback.
 */
interface LlmDriver {
  /** Loads the downloaded GGUF model at [modelPath]; a no-op if it is already loaded. */
  suspend fun load(modelPath: String) {}

  val isLoaded: Boolean
    get() = true

  /**
   * Runs [prompt] on the model for [engineMode] and returns the raw model output. The driver
   * renders the prompt in its model's own format.
   *
   * Each call must be independent of the previous one: nothing from an earlier prompt may leak into
   * the next. The one allowance is [CorrectionPrompt.reusePrefix]: a driver may reuse the saved
   * state of a fixed opening that is identical across calls, never anything from a user's text.
   */
  suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String

  /**
   * The context window [engineMode] has to fit a whole request into, or null when the engine
   * manages its own and the caller need not divide it up.
   *
   * Only meaningful once the model is loaded: the counts come from its vocabulary.
   */
  suspend fun tokenWindow(engineMode: EngineMode): TokenWindow? = null

  /**
   * Scores each of [choices] as the model's continuation of [prompt] instead of generating text
   * ("System One"): one score per choice, in order. Null when [engineMode]'s model does not expose
   * token probabilities, and the caller then falls back to generating.
   *
   * The prompt is rendered as for [generate], [CorrectionPrompt.answerPrefix] included, and each
   * choice is read as the text that follows it, so it carries its own leading space (`" formal"`).
   * The same independence rule applies: only a [CorrectionPrompt.reusePrefix] opening may be
   * reused.
   */
  suspend fun scoreChoices(
    prompt: CorrectionPrompt,
    choices: List<String>,
    engineMode: EngineMode,
  ): List<ChoiceScore>? = null

  fun unload() {}
}

/**
 * How likely the model finds one candidate answer: [logProb] is the summed natural-log probability
 * of its [tokens] tokens.
 */
data class ChoiceScore(val logProb: Float, val tokens: Int) {
  /** Per-token log-probability, for comparing candidates of different lengths. */
  val meanLogProb: Float
    get() = if (tokens > 0) logProb / tokens else Float.NEGATIVE_INFINITY

  companion object {
    /**
     * [scores] as probabilities that sum to one across the candidates: the model's own
     * distribution, restricted to the answers on offer. Summed log-probabilities, so a label that
     * spans two tokens is not favoured or penalised for it beyond what the model says.
     */
    fun normalised(scores: List<ChoiceScore>): List<Float> {
      if (scores.isEmpty()) return emptyList()
      val max = scores.maxOf { it.logProb }
      if (max == Float.NEGATIVE_INFINITY) return scores.map { 0f }
      val weights = scores.map { kotlin.math.exp((it.logProb - max).toDouble()) }
      val total = weights.sum()
      return weights.map { (it / total).toFloat() }
    }
  }
}

/** The on-device driver for the current platform. */
expect fun createPlatformLlmDriver(): LlmDriver

/**
 * One engine per process. The in-app screen and the accessibility overlay both correct text, and a
 * second copy of a 1.2 GB model would double the memory used for no benefit.
 */
object LlmRuntime {
  val engine: InferenceEngine by lazy { InferenceEngineImpl(recorder = KoinCorrectionRecorder()) }

  /**
   * The engine the user picked. Shared so the accessibility overlay corrects with the same model as
   * the in-app screen.
   */
  val engineMode = MutableStateFlow(EngineMode.QWEN3_GGUF)
}

/**
 * A tone whose own prompt leaves no room for the user's text.
 *
 * A type rather than a bare `IllegalStateException` so the failure can be recorded as
 * [CorrectionErrorCode.PROMPT_TOO_LONG] without analytics ever reading the message — which is the
 * one place a driver could smuggle the user's own words into the database.
 */
class PromptTooLongException(message: String) : IllegalStateException(message)

class InferenceEngineImpl(
  private val llmDriver: LlmDriver = createPlatformLlmDriver(),
  private val modelPathProvider: () -> String = ::defaultModelPath,
  /**
   * Where corrections are counted. The no-op default keeps the engine and its tests free of a
   * database; the app passes the Room-backed recorder through [LlmRuntime].
   */
  private val recorder: CorrectionRecorder = CorrectionRecorder.None,
) : InferenceEngine {

  /** A model context runs one generation at a time; callers from several places queue here. */
  private val mutex = Mutex()

  private val _activity = MutableStateFlow(InferenceActivity())
  override val activity: StateFlow<InferenceActivity> = _activity.asStateFlow()

  /**
   * Runs [block] under the model lock, counted as running from before it queues for the lock. A
   * request waiting its turn is as good as running: freeing the model then would only make it load
   * the model straight back.
   */
  private suspend fun <T> busy(block: suspend () -> T): T {
    _activity.update { it.copy(running = it.running + 1) }
    try {
      return mutex.withLock { block() }
    } finally {
      _activity.update { InferenceActivity(running = it.running - 1, finished = it.finished + 1) }
    }
  }

  override suspend fun initialize(modelPath: String?): Boolean = busy {
    llmDriver.load(modelPath ?: modelPathProvider())
    llmDriver.isLoaded
  }

  override suspend fun isModelLoaded(): Boolean = llmDriver.isLoaded

  override suspend fun generateCorrection(
    text: String,
    enforceNoThink: Boolean,
    engineMode: EngineMode,
    tone: TonePreset,
    source: CorrectionSource,
    isStillWanted: suspend () -> Boolean,
  ): CorrectionResult {
    val startTime = kotlin.time.TimeSource.Monotonic.markNow()

    // The model sees the text without the whitespace around it, which goes back on afterwards. A
    // keyboard leaves a space after every word, and with one at the end the model handed
    // "Hello How is your? " back untouched while it fixed the same sentence without it.
    val core = text.trim()
    val prompt = CorrectionPrompt.forTone(tone, core, fastMode = enforceNoThink)

    if (text.isBlank()) {
      // Nothing ran, so there is nothing to count: no row for an empty box.
      return CorrectionResult(
        originalText = text,
        correctedText = text,
        latencyMs = 0L,
        isNonThinkingMode = prompt.enforceNoThink,
        diffs = emptyList(),
        engineMode = engineMode,
        tonePresetId = tone.id,
        chunks = 0,
      )
    }

    val rewritten =
      try {
        busy {
          ensureLoaded(engineMode)
          rewrite(prompt, core, engineMode)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Report the failure rather than inventing a correction: the UI shows the reason and
        // the user's text is left untouched. A failure is data too — "how often does inference
        // fail" is worth answering — so the row is written like any other.
        return recorded(
          CorrectionResult(
            originalText = text,
            correctedText = text,
            latencyMs = startTime.elapsedNow().inWholeMilliseconds,
            isNonThinkingMode = prompt.enforceNoThink,
            diffs = emptyList(),
            engineMode = engineMode,
            tonePresetId = tone.id,
            chunks = 0,
            isSuccess = false,
            errorMessage = e.message ?: "On-device inference failed",
            errorCode =
              if (e is PromptTooLongException) CorrectionErrorCode.PROMPT_TOO_LONG
              else CorrectionErrorCode.INFERENCE_FAILED,
          ),
          source,
          isStillWanted,
        )
      }

    val correctedText =
      text.takeWhile { it.isWhitespace() } +
        rewritten.text.ifBlank { core } +
        text.takeLastWhile { it.isWhitespace() }

    val diffs = GrammarDiffAnalyzer.computeDiff(text, correctedText)
    val elapsedMs = startTime.elapsedNow().inWholeMilliseconds

    val inputWords = text.split(" ").filter { it.isNotEmpty() }.size
    val outputWords = correctedText.split(" ").filter { it.isNotEmpty() }.size
    val approxTokens = (outputWords * 1.3f)
    val tps = if (elapsedMs > 0) (approxTokens / (elapsedMs / 1000f)) else 0f

    return recorded(
      CorrectionResult(
        originalText = text,
        correctedText = correctedText,
        latencyMs = elapsedMs,
        isNonThinkingMode = prompt.enforceNoThink,
        diffs = diffs,
        engineMode = engineMode,
        tonePresetId = tone.id,
        inputWordCount = inputWords,
        outputWordCount = outputWords,
        tokensPerSecond = tps,
        chunks = rewritten.chunks,
        isSuccess = true,
      ),
      source,
      isStillWanted,
    )
  }

  /**
   * Counts [result] and returns it unchanged.
   *
   * Two things this must never do. It must never record a [CorrectionSource.PLAYGROUND] run — those
   * are canned benchmark sentences and would swamp real usage — and it must never fail a
   * correction, so a database error is logged and dropped. A user does not lose their grammar fix
   * because a write failed.
   *
   * Every correction passes through here, which is why the Playground check lives here rather than
   * in a recorder that a later caller could bypass, and why [isStillWanted] is asked here: the flag
   * the row carries and the verdict the caller acts on are then the same answer, decided once.
   */
  private suspend fun recorded(
    result: CorrectionResult,
    source: CorrectionSource,
    isStillWanted: suspend () -> Boolean,
  ): CorrectionResult {
    val counted = result.copy(wasDelivered = stillWanted(isStillWanted))
    if (source == CorrectionSource.PLAYGROUND) return counted
    try {
      recorder.record(counted, source)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return counted
  }

  /**
   * Asks the caller, once, whether its answer is still of any use.
   *
   * A check that throws is treated as a delivered result. It is the caller's own code, so a throw
   * is a bug in it rather than evidence about the user — and of the two ways to be wrong, counting
   * a correction the user probably did receive is better than quietly deleting it from their own
   * statistics.
   */
  private suspend fun stillWanted(check: suspend () -> Boolean): Boolean =
    try {
      check()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      e.printStackTrace()
      true
    }

  /**
   * Rewrites [text], splitting it first when it is longer than one request can carry.
   *
   * Text that already fits is sent as one request with the prompt it has always been sent with: the
   * proofreading pass behind the accessibility overlay and the live editor must not change because
   * a longer text elsewhere now needs splitting.
   *
   * The answer's budget is sized from the text actually being sent rather than fixed at the tone's
   * cap, which is what left a long rewrite cut off mid-sentence. The tone's cap stays as a floor,
   * so short requests are byte-for-byte what they were.
   */
  private suspend fun rewrite(
    prompt: CorrectionPrompt,
    text: String,
    engineMode: EngineMode,
  ): Rewrite {
    // An engine that manages its own window decides for itself how much of a request it takes.
    val window =
      llmDriver.tokenWindow(engineMode)
        ?: return Rewrite(generateOne(prompt, text, engineMode), chunks = 1)

    val plan = ContextBudget.plan(prompt, window)
    if (plan.maxInputTokens <= 0) {
      throw PromptTooLongException(
        "This tone's prompt fills the whole context window. Shorten it in the Tones tab."
      )
    }

    val tokens = window.countTokens(text)
    if (tokens <= plan.maxInputTokens) {
      return Rewrite(
        generateOne(prompt, text, engineMode, plan.answerTokens(tokens, prompt.maxOutputTokens)),
        chunks = 1,
      )
    }

    val chunked = TextChunker.split(text, plan.maxInputTokens, window::countTokens)
    val rewrites =
      chunked.chunks.map { chunk ->
        val answer = plan.answerTokens(chunk.tokens, prompt.maxOutputTokens)
        // A chunk the model returns nothing for keeps the user's own words rather than vanishing.
        generateOne(prompt, chunk.text, engineMode, answer).ifBlank { chunk.text }
      }
    return Rewrite(chunked.rejoin(rewrites), chunks = chunked.chunks.size)
  }

  /** The model's answer and how many requests it took, which analytics counts. */
  private data class Rewrite(val text: String, val chunks: Int)

  /** One request, cleaned of the reasoning block and the ChatML delimiters around the answer. */
  private suspend fun generateOne(
    prompt: CorrectionPrompt,
    text: String,
    engineMode: EngineMode,
    maxOutputTokens: Int = prompt.maxOutputTokens,
  ): String {
    val request = prompt.copy(userText = text, maxOutputTokens = maxOutputTokens)
    return cleanLlmOutput(llmDriver.generate(request, engineMode))
  }

  override suspend fun detectTone(
    text: String,
    engineMode: EngineMode,
    mode: ToneReadMode,
  ): ToneDetectionResult {
    val startTime = kotlin.time.TimeSource.Monotonic.markNow()
    if (text.isBlank()) {
      return ToneDetectionResult(null, 0L, engineMode, errorMessage = "Nothing to read")
    }
    return try {
      // Counted like a correction, so the idle unload never frees the model under a tone read
      // and a read resets its idle timer.
      val (reading, scored) =
        busy {
          ensureLoaded(engineMode)
          val scores =
            if (mode == ToneReadMode.SCORE) {
              llmDriver.scoreChoices(
                ToneDetection.scoringPrompt(text),
                ToneDetection.SCORE_CHOICES,
                engineMode,
              )
            } else null
          if (scores != null) {
            val labels = ToneDetection.pickLabels(scores)
            (if (labels.isEmpty()) null else ToneReading(labels, summary = "")) to true
          } else {
            ToneDetection.parse(llmDriver.generate(ToneDetection.prompt(text), engineMode)) to false
          }
        }
      ToneDetectionResult(
        reading = reading,
        latencyMs = startTime.elapsedNow().inWholeMilliseconds,
        engineMode = engineMode,
        errorMessage = if (reading == null) "The model gave no tone label" else null,
        summaryPending = scored && reading != null,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ToneDetectionResult(
        reading = null,
        latencyMs = startTime.elapsedNow().inWholeMilliseconds,
        engineMode = engineMode,
        errorMessage = e.message ?: "On-device inference failed",
      )
    }
  }

  override suspend fun summarizeTone(
    text: String,
    labels: List<String>,
    engineMode: EngineMode,
  ): String = busy {
    ensureLoaded(engineMode)
    ToneDetection.parseSummary(
      llmDriver.generate(ToneDetection.summaryPrompt(text, labels), engineMode)
    )
  }

  private suspend fun ensureLoaded(engineMode: EngineMode) {
    if (engineMode == EngineMode.QWEN3_GGUF && !llmDriver.isLoaded) {
      llmDriver.load(modelPathProvider())
    }
  }

  override fun unloadModel() {
    llmDriver.unload()
  }

  override suspend fun releaseIfIdle(): Boolean {
    val asked = _activity.value
    if (asked.isRunning) return false
    return mutex.withLock {
      // A request that started after the question was asked, even one already finished, is fresh
      // use: the caller's idea of "idle" is out of date.
      if (_activity.value != asked || !llmDriver.isLoaded) return@withLock false
      llmDriver.unload()
      true
    }
  }

  /** Strips `<think>…</think>` reasoning and ChatML delimiters from the raw token stream. */
  private fun cleanLlmOutput(rawOutput: String): String {
    var output = rawOutput.trim()

    if (output.contains("</think>")) {
      output = output.substringAfterLast("</think>").trim()
    }

    output = output.substringBefore("<|im_end|>").substringBefore("<|endoftext|>").trim()
    output = output.substringBefore("<|im_start|>").trim()

    return output
  }
}
