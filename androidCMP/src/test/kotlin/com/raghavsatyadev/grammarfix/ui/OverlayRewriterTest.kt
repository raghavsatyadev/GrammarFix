package com.raghavsatyadev.grammarfix.ui

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.raghavsatyadev.grammarfix.data.GrammarFixDatabase
import com.raghavsatyadev.grammarfix.engine.CorrectionPrompt
import com.raghavsatyadev.grammarfix.engine.CorrectionRecorder
import com.raghavsatyadev.grammarfix.engine.CorrectionResult
import com.raghavsatyadev.grammarfix.engine.CorrectionSource
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.EngineMode
import com.raghavsatyadev.grammarfix.engine.InferenceEngineImpl
import com.raghavsatyadev.grammarfix.engine.LlmDriver
import com.raghavsatyadev.grammarfix.engine.RoomCorrectionRecorder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay's rewrite panel (#31), without a window: which tone runs, what a new chip does to the
 * one in flight, and what analytics is — and is not — told.
 */
class OverlayRewriterTest {

  /**
   * Answers each tone with "<first word of its system prompt> answer", but only once the test opens
   * that tone's gate, so a rewrite can be caught mid-flight.
   */
  private class GatedDriver : LlmDriver {
    val started = Channel<CorrectionPrompt>(Channel.UNLIMITED)
    val cancelled = mutableListOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<String>>()

    fun gate(systemPrompt: String) = gates.getOrPut(systemPrompt) { CompletableDeferred() }

    fun release(systemPrompt: String, answer: String) {
      gate(systemPrompt).complete(answer)
    }

    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
      started.send(prompt)
      try {
        return gate(prompt.systemPrompt).await()
      } catch (e: CancellationException) {
        cancelled += prompt.systemPrompt
        throw e
      }
    }
  }

  /** Answers at once with a fixed text. */
  private class InstantDriver(private val answer: String) : LlmDriver {
    val prompts = mutableListOf<CorrectionPrompt>()

    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
      prompts += prompt
      return answer
    }
  }

  private class CapturingRecorder : CorrectionRecorder {
    val rows = mutableListOf<Pair<CorrectionResult, CorrectionSource>>()

    override suspend fun record(result: CorrectionResult, source: CorrectionSource) {
      rows += result to source
    }
  }

  private val text = "hey can u send the file"
  private val tones = DefaultTonePresets.all

  private suspend fun OverlayRewriter.awaitSettled(): SuggestionOverlayState.Rewrite =
    withTimeout(5_000) { state.first { it != null && it.result != RewriteResult.Loading }!! }

  @Test
  fun opensOnTheStoredToneAndShowsItsRewrite() = runBlocking {
    val driver = InstantDriver("Could you send the file?")
    val rewriter =
      OverlayRewriter(InferenceEngineImpl(llmDriver = driver), this, engineMode = { QWEN })

    rewriter.open(text, tones, selectedId = DefaultTonePresets.FORMAL_ID)
    assertEquals(RewriteResult.Loading, rewriter.state.value?.result)
    assertEquals(DefaultTonePresets.FORMAL_ID, rewriter.state.value?.selectedId)

    val panel = rewriter.awaitSettled()
    val ready = panel.result as RewriteResult.Ready
    assertEquals("Could you send the file?", ready.text)
    assertTrue(ready.diffs.isNotEmpty())
    assertEquals(DefaultTonePresets.FORMAL.systemPrompt, driver.prompts.single().systemPrompt)
    rewriter.close()
  }

  @Test
  fun aStoredToneThatNoLongerExistsFallsBackToTheFirstChip() = runBlocking {
    val rewriter =
      OverlayRewriter(InferenceEngineImpl(llmDriver = InstantDriver("x")), this) { QWEN }

    rewriter.open(text, tones, selectedId = "custom-99")

    assertEquals(tones.first().id, rewriter.state.value?.selectedId)
    rewriter.awaitSettled()
    rewriter.close()
  }

  /**
   * The acceptance item: a new chip cancels the rewrite in flight — down to the driver, so the
   * model is freed for the next one — and only the new tone's answer is ever shown.
   */
  @Test
  fun switchingChipsCancelsTheRunningRewriteAndStartsTheNewOne() = runBlocking {
    val driver = GatedDriver()
    val recorder = CapturingRecorder()
    val engine = InferenceEngineImpl(llmDriver = driver, recorder = recorder)
    val rewriter = OverlayRewriter(engine, this) { QWEN }

    rewriter.open(text, tones, selectedId = DefaultTonePresets.POLISH_ID)
    assertEquals(DefaultTonePresets.POLISH.systemPrompt, driver.started.receive().systemPrompt)

    rewriter.select(DefaultTonePresets.SHORTEN_ID)
    assertEquals(RewriteResult.Loading, rewriter.state.value?.result)
    assertEquals(DefaultTonePresets.SHORTEN_ID, rewriter.state.value?.selectedId)
    assertEquals(DefaultTonePresets.SHORTEN.systemPrompt, driver.started.receive().systemPrompt)
    assertEquals(listOf(DefaultTonePresets.POLISH.systemPrompt), driver.cancelled)

    // A late answer for the replaced tone has nowhere to land.
    driver.release(DefaultTonePresets.POLISH.systemPrompt, "Polished.")
    driver.release(DefaultTonePresets.SHORTEN.systemPrompt, "Send the file?")

    val panel = rewriter.awaitSettled()
    assertEquals("Send the file?", (panel.result as RewriteResult.Ready).text)
    assertEquals(DefaultTonePresets.SHORTEN_ID, panel.selectedId)
    // The cancelled run never finished, so it is not counted as a correction.
    assertEquals(listOf(DefaultTonePresets.SHORTEN_ID), recorder.rows.map { it.first.tonePresetId })
    rewriter.close()
  }

  @Test
  fun tappingTheChipAlreadyShowingDoesNotRunTheModelAgain() = runBlocking {
    val driver = InstantDriver("Send the file?")
    val rewriter = OverlayRewriter(InferenceEngineImpl(llmDriver = driver), this) { QWEN }

    rewriter.open(text, tones, selectedId = DefaultTonePresets.SHORTEN_ID)
    rewriter.awaitSettled()
    rewriter.select(DefaultTonePresets.SHORTEN_ID)

    assertTrue(rewriter.state.value?.result is RewriteResult.Ready)
    assertEquals(1, driver.prompts.size)
    rewriter.close()
  }

  @Test
  fun closingDropsTheRewriteInFlight() = runBlocking {
    val driver = GatedDriver()
    val rewriter = OverlayRewriter(InferenceEngineImpl(llmDriver = driver), this) { QWEN }

    rewriter.open(text, tones, selectedId = DefaultTonePresets.POLISH_ID)
    driver.started.receive()
    rewriter.close()

    assertNull(rewriter.state.value)
    withTimeout(5_000) { while (driver.cancelled.isEmpty()) kotlinx.coroutines.yield() }
    assertEquals(listOf(DefaultTonePresets.POLISH.systemPrompt), driver.cancelled)
  }

  /** Rewrites from the overlay are overlay usage, with the chip's id in `tone_preset_id`. */
  @Test
  fun aChipRewriteIsRecordedAsOverlayUnderTheChipsId() = runBlocking {
    val recorder = CapturingRecorder()
    val engine = InferenceEngineImpl(llmDriver = InstantDriver("Hi! Send it?"), recorder = recorder)
    val rewriter = OverlayRewriter(engine, this) { QWEN }

    rewriter.open(text, tones, selectedId = DefaultTonePresets.FRIENDLY_ID)
    rewriter.awaitSettled()

    val (result, source) = recorder.rows.single()
    assertEquals(CorrectionSource.OVERLAY, source)
    assertEquals(DefaultTonePresets.FRIENDLY_ID, result.tonePresetId)
    assertTrue(result.wasDelivered)
    rewriter.close()
  }

  /**
   * A typed instruction runs as a one-off tone on the same engine path, with the instruction in the
   * system prompt and no chip selected — and is recorded under a fixed id, not its own words.
   */
  @Test
  fun anInstructionRunsAsATransientToneAndIsRecordedUnderAFixedId() = runBlocking {
    val driver = InstantDriver("Arr, send the file!")
    val recorder = CapturingRecorder()
    val engine = InferenceEngineImpl(llmDriver = driver, recorder = recorder)
    val rewriter = OverlayRewriter(engine, this) { QWEN }

    rewriter.open(text, tones, selectedId = DefaultTonePresets.POLISH_ID)
    rewriter.awaitSettled()
    rewriter.instruct("  Make it sound like a pirate  ")

    val panel = rewriter.awaitSettled()
    assertEquals(DefaultTonePresets.INSTRUCTION_ID, panel.selectedId)
    assertEquals("Make it sound like a pirate", panel.instruction)
    val prompt = driver.prompts.last()
    assertTrue(prompt.systemPrompt.contains("Make it sound like a pirate"))
    assertTrue(prompt.examples.isEmpty())
    assertFalse("an instruction keeps the reasoning step", prompt.enforceNoThink)

    val (result, source) = recorder.rows.last()
    assertEquals(CorrectionSource.OVERLAY, source)
    assertEquals(DefaultTonePresets.INSTRUCTION_ID, result.tonePresetId)
    rewriter.close()
  }

  @Test
  fun aBlankInstructionIsIgnored() = runBlocking {
    val driver = InstantDriver("x")
    val rewriter = OverlayRewriter(InferenceEngineImpl(llmDriver = driver), this) { QWEN }

    rewriter.open(text, tones, selectedId = DefaultTonePresets.POLISH_ID)
    rewriter.awaitSettled()
    rewriter.instruct("   ")

    assertNull(rewriter.state.value?.instruction)
    assertEquals(1, driver.prompts.size)
    rewriter.close()
  }

  /**
   * The acceptance item, end to end against a real database file: after an instruction has run, its
   * words appear nowhere in the database — not in a row, not in the write-ahead log.
   */
  @Test
  fun anInstructionNeverReachesTheDatabase() {
    val directory = File.createTempFile("grammarfix-rewrite", "").apply { delete() }
    directory.mkdirs()
    val instruction = "Rewrite this like a zebra reciting sonnets"
    try {
      val database =
        Room.databaseBuilder<GrammarFixDatabase>(
            name = File(directory, "grammarfix.db").absolutePath
          )
          .setDriver(BundledSQLiteDriver())
          .setQueryCoroutineContext(Dispatchers.IO)
          .build()
      runBlocking {
        val engine =
          InferenceEngineImpl(
            llmDriver = InstantDriver("A zebra's sonnet: send the file."),
            recorder = RoomCorrectionRecorder(database),
          )
        val rewriter = OverlayRewriter(engine, this) { QWEN }
        rewriter.open(text, tones, selectedId = DefaultTonePresets.POLISH_ID)
        rewriter.awaitSettled()
        rewriter.instruct(instruction)
        rewriter.awaitSettled()
        rewriter.close()

        val rows = database.correctionEventDao().all()
        assertEquals(
          setOf(DefaultTonePresets.POLISH_ID, DefaultTonePresets.INSTRUCTION_ID),
          rows.map { it.tonePresetId }.toSet(),
        )
        assertEquals(setOf("OVERLAY"), rows.map { it.source }.toSet())
      }
      database.close()

      val files = directory.listFiles().orEmpty()
      assertTrue(files.isNotEmpty())
      files.forEach { file ->
        val content = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("${file.name} holds the instruction", content.contains("zebra"))
        assertFalse("${file.name} holds the user's text", content.contains(text))
      }
    } finally {
      directory.deleteRecursively()
    }
  }

  private companion object {
    val QWEN = EngineMode.QWEN3_GGUF
  }
}
