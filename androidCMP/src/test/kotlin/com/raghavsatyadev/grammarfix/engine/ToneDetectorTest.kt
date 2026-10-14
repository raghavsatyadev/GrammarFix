package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the tone is read (#32): only after the grammar check *and* a still keyboard, never twice for
 * the same text, and never past a keystroke — which must stop the model, not just the wait.
 */
class ToneDetectorTest {

  /** Answers each tone read only when the test releases it, so a read can be caught mid-flight. */
  private class GatedDriver : LlmDriver {
    val started = Channel<CorrectionPrompt>(Channel.UNLIMITED)
    val cancelled = mutableListOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<String>>()

    private fun gate(userText: String) = gates.getOrPut(userText) { CompletableDeferred() }

    fun release(text: String, answer: String) {
      gate(Prompts.ToneRead.MESSAGE_PREFIX + text).complete(answer)
    }

    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
      started.send(prompt)
      try {
        return gate(prompt.userText).await()
      } catch (e: CancellationException) {
        cancelled += prompt.userText
        throw e
      }
    }
  }

  private class CapturingRecorder : ToneRecorder {
    val rows = mutableListOf<ToneReading>()

    override suspend fun record(reading: ToneReading, latencyMs: Long, engineMode: EngineMode) {
      rows += reading
    }

    override suspend fun latestLabels(): List<String>? = rows.lastOrNull()?.labels
  }

  private var clock = 0L
  private val idle = 1_500L

  private fun detector(
    driver: LlmDriver,
    scope: kotlinx.coroutines.CoroutineScope,
    recorder: ToneRecorder = ToneRecorder.None,
  ) =
    ToneDetector(
      engine = InferenceEngineImpl(llmDriver = driver),
      scope = scope,
      recorder = recorder,
      idleMs = idle,
      engineMode = { EngineMode.QWEN3_GGUF },
      now = { clock },
    )

  private suspend fun ToneDetector.awaitStatus(text: String, predicate: (ToneStatus) -> Boolean) =
    withTimeout(5_000) { check.first { it?.text == text && predicate(it.status) }!!.status }

  @Test
  fun withTheSettingOffAFinishedGrammarCheckReadsNothing() = runBlocking {
    val driver = GatedDriver()
    val tone = detector(driver, this)

    tone.onTextChanged("See you at six")
    clock += 10_000
    tone.onGrammarChecked("See you at six", autoDetect = false)

    assertNull(withTimeoutOrNull(200) { driver.started.receive() })
    assertEquals(ToneStatus.Idle, tone.check.value?.status)
    tone.clear()
  }

  @Test
  fun readsAtOnceWhenTypingStoppedLongEnoughAgo() = runBlocking {
    val driver = GatedDriver()
    val recorder = CapturingRecorder()
    val tone = detector(driver, this, recorder)

    tone.onTextChanged("No. We are not changing it.")
    clock += idle + 1 // the grammar check itself took longer than the idle wait
    tone.onGrammarChecked("No. We are not changing it.", autoDetect = true)

    val prompt = withTimeout(1_000) { driver.started.receive() }
    assertTrue(prompt.reusePrefix)
    driver.release("No. We are not changing it.", "Tone: direct, frustrated\nSummary: Blunt.")

    val status = tone.awaitStatus("No. We are not changing it.") { it is ToneStatus.Ready }
    assertEquals(listOf("direct", "frustrated"), (status as ToneStatus.Ready).reading.labels)
    assertEquals(listOf("direct", "frustrated"), tone.lastReading.value?.labels)
    assertEquals(listOf(ToneReading(listOf("direct", "frustrated"), "Blunt.")), recorder.rows)
  }

  @Test
  fun waitsOutTheRestOfTheIdleTimeBeforeReading() = runBlocking {
    val driver = GatedDriver()
    // A short real wait, so the test measures the rule rather than sleeping 1.5 s.
    val tone =
      ToneDetector(
        engine = InferenceEngineImpl(llmDriver = driver),
        scope = this,
        idleMs = 400L,
        engineMode = { EngineMode.QWEN3_GGUF },
        now = { clock },
      )

    tone.onTextChanged("hey")
    clock += 100 // only 100 ms of the 400 have passed when the grammar check lands
    tone.onGrammarChecked("hey", autoDetect = true)

    assertNull(withTimeoutOrNull(150) { driver.started.receive() })
    assertEquals(
      "hey",
      withTimeout(2_000) { driver.started.receive() }
        .userText
        .removePrefix(Prompts.ToneRead.MESSAGE_PREFIX),
    )
    tone.clear()
  }

  /** The acceptance item: a keystroke cancels the read, down to the driver. */
  @Test
  fun aKeystrokeCancelsTheRunningReadAndItsLateAnswerIsDropped() = runBlocking {
    val driver = GatedDriver()
    val recorder = CapturingRecorder()
    val tone = detector(driver, this, recorder)

    tone.onTextChanged("I waited two hours")
    clock += idle
    tone.onGrammarChecked("I waited two hours", autoDetect = true)
    withTimeout(1_000) { driver.started.receive() }
    assertEquals(ToneStatus.Detecting, tone.check.value?.status)

    tone.onTextChanged("I waited two hours.")
    // Cancelled at the driver: in the app that watcher is what calls the native abort.
    withTimeout(1_000) { while (driver.cancelled.isEmpty()) delay(10) }
    assertEquals(listOf("Message: I waited two hours"), driver.cancelled)
    assertEquals(ToneCheck("I waited two hours.", ToneStatus.Idle), tone.check.value)

    driver.release("I waited two hours", "Tone: frustrated\nSummary: Fed up.")
    delay(100)
    assertEquals(ToneCheck("I waited two hours.", ToneStatus.Idle), tone.check.value)
    assertTrue(recorder.rows.isEmpty())
    assertNull(tone.lastReading.value)
  }

  @Test
  fun aKeystrokeDuringTheIdleWaitMeansTheReadNeverStarts() = runBlocking {
    val driver = GatedDriver()
    val tone =
      ToneDetector(
        engine = InferenceEngineImpl(llmDriver = driver),
        scope = this,
        idleMs = 300L,
        engineMode = { EngineMode.QWEN3_GGUF },
        now = { clock },
      )

    tone.onTextChanged("hello")
    tone.onGrammarChecked("hello", autoDetect = true)
    tone.onTextChanged("hello there")

    assertNull(withTimeoutOrNull(500) { driver.started.receive() })
    tone.clear()
  }

  @Test
  fun aRepeatedGrammarCheckForTheSameTextDoesNotReadItAgain() = runBlocking {
    val driver = GatedDriver()
    val tone = detector(driver, this)

    tone.onTextChanged("Thanks so much!")
    clock += idle
    tone.onGrammarChecked("Thanks so much!", autoDetect = true)
    withTimeout(1_000) { driver.started.receive() }
    driver.release("Thanks so much!", "Tone: friendly\nSummary: Warm.")
    tone.awaitStatus("Thanks so much!") { it is ToneStatus.Ready }

    tone.onGrammarChecked("Thanks so much!", autoDetect = true)
    assertNull(withTimeoutOrNull(200) { driver.started.receive() })
  }

  @Test
  fun theButtonReadsRightAwayWithTheSettingOff() = runBlocking {
    val driver = GatedDriver()
    val tone = detector(driver, this)

    tone.onTextChanged("Would it be okay if I took Friday off?")
    tone.detectNow("Would it be okay if I took Friday off?")

    withTimeout(1_000) { driver.started.receive() }
    driver.release("Would it be okay if I took Friday off?", "Tone: uncertain\nSummary: Hesitant.")
    val status =
      tone.awaitStatus("Would it be okay if I took Friday off?") { it is ToneStatus.Ready }
    assertEquals(listOf("uncertain"), (status as ToneStatus.Ready).reading.labels)
  }

  @Test
  fun anAnswerWithNoLabelIsAFailureAndIsNotRecorded() = runBlocking {
    val driver = GatedDriver()
    val recorder = CapturingRecorder()
    val tone = detector(driver, this, recorder)

    tone.detectNow("gonna grab food")
    withTimeout(1_000) { driver.started.receive() }
    driver.release("gonna grab food", "Sure, what do you want?")

    assertTrue(tone.awaitStatus("gonna grab food") { it is ToneStatus.Failed } is ToneStatus.Failed)
    assertTrue(recorder.rows.isEmpty())
  }

  @Test
  fun blankTextIsNeverRead() = runBlocking {
    val driver = GatedDriver()
    val tone = detector(driver, this)

    tone.detectNow("   ")
    tone.onTextChanged("")
    clock += idle
    tone.onGrammarChecked("", autoDetect = true)

    assertNull(withTimeoutOrNull(200) { driver.started.receive() })
    tone.clear()
  }

  /** Scores labels like the Qwen driver; summaries go through [generate] like the real one. */
  private class ScoringDriver(private val shares: Map<String, Double>) : LlmDriver {
    val generated = mutableListOf<CorrectionPrompt>()

    override suspend fun scoreChoices(
      prompt: CorrectionPrompt,
      choices: List<String>,
      engineMode: EngineMode,
    ): List<ChoiceScore> = choices.map { choice ->
      val p = shares[choice.trim()] ?: 1e-6
      ChoiceScore(kotlin.math.ln(p).toFloat(), tokens = 1)
    }

    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
      generated += prompt
      return " A blunt refusal.\n"
    }
  }

  @Test
  fun aScoredReadShowsTheLabelsThenAddsTheSummary() = runBlocking {
    val driver = ScoringDriver(mapOf("direct" to 0.6, "frustrated" to 0.3, "formal" to 0.001))
    val recorder = CapturingRecorder()
    val tone = detector(driver, this, recorder)

    tone.detectNow("No. We are not changing it.")

    val status =
      tone.awaitStatus("No. We are not changing it.") {
        it is ToneStatus.Ready && it.reading.summary.isNotEmpty()
      }
    assertEquals(
      ToneReading(listOf("direct", "frustrated"), "A blunt refusal."),
      (status as ToneStatus.Ready).reading,
    )
    // One generation, for the summary only, opened with the labels the scores chose.
    assertEquals(1, driver.generated.size)
    assertEquals("Tone: direct, frustrated\nSummary:", driver.generated.single().answerPrefix)
    // Recorded once, when the labels arrived: the summary is never stored.
    assertEquals(listOf(ToneReading(listOf("direct", "frustrated"), "")), recorder.rows)
  }

  @Test
  fun theGenerateSwitchNeverScores() = runBlocking {
    val driver = ScoringDriver(mapOf("direct" to 1.0))
    val engine = InferenceEngineImpl(llmDriver = driver)
    val result = engine.detectTone("Stop.", mode = ToneReadMode.GENERATE)
    // The fake's answer is no "Tone:" line, so no reading: what matters is that it generated.
    assertEquals(1, driver.generated.size)
    assertEquals("", driver.generated.single().answerPrefix)
    assertFalse(result.summaryPending)
  }

  /** A tone read is usage for the idle unload (#53): it holds the model and resets the timer. */
  @Test
  fun aToneReadCountsAsActivityAndIsNeverUnloadedUnder() = runBlocking {
    val driver = GatedDriver()
    val engine = InferenceEngineImpl(llmDriver = driver)
    val before = engine.activity.value
    val read = async { engine.detectTone("Stop moving the deadline.") }

    withTimeout(1_000) { driver.started.receive() }
    assertEquals(1, engine.activity.value.running)
    assertFalse(engine.releaseIfIdle())

    driver.release("Stop moving the deadline.", "Tone: direct\nSummary: Firm.")
    assertEquals(listOf("direct"), read.await().reading?.labels)
    assertEquals(0, engine.activity.value.running)
    assertEquals(before.finished + 1, engine.activity.value.finished)
  }
}
