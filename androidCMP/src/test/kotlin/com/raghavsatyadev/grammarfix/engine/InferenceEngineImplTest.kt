package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceEngineImplTest {

  private val testDriver =
    object : LlmDriver {
      var loadedPath: String? = null
      override val isLoaded: Boolean
        get() = loadedPath != null

      override suspend fun load(modelPath: String) {
        loadedPath = modelPath
      }

      override fun unload() {
        loadedPath = null
      }

      override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
        return "He went to school yesterday and he didn't like it."
      }
    }

  private val engine =
    InferenceEngineImpl(llmDriver = testDriver, modelPathProvider = { "/m.gguf" })

  @Test
  fun generateCorrection_runsCorrectionAndComputesDiff() = runBlocking {
    val input = "he go to school yesterday and he don't like it"
    val result = engine.generateCorrection(input, enforceNoThink = true)

    assertTrue(result.isSuccess)
    assertTrue(result.isNonThinkingMode)
    assertEquals("He went to school yesterday and he didn't like it.", result.correctedText)
    assertTrue(result.diffs.isNotEmpty())
    assertTrue(result.inputWordCount > 0)
    assertTrue(result.outputWordCount > 0)
  }

  /** The model never sees the space a keyboard leaves at the end; the field keeps it. */
  @Test
  fun surroundingWhitespaceIsKeptAwayFromTheModel() = runBlocking {
    var seen: CorrectionPrompt? = null
    val recording =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
          seen = prompt
          return "Hello, how are you?"
        }
      }

    val result =
      InferenceEngineImpl(recording, modelPathProvider = { "/m.gguf" })
        .generateCorrection(" Hello How is your? ", enforceNoThink = true)

    assertEquals("Hello How is your?", seen?.userText)
    assertEquals(" Hello, how are you? ", result.correctedText)
    assertTrue(result.diffs.any { it.type != DiffType.UNCHANGED })
  }

  @Test
  fun initialize_and_isModelLoaded_managesState() = runBlocking {
    assertFalse(engine.isModelLoaded())

    val success = engine.initialize("/path/to/qwen3.gguf")
    assertTrue(success)
    assertTrue(engine.isModelLoaded())

    engine.unloadModel()
    assertFalse(engine.isModelLoaded())
  }

  /** No fallback: when the model cannot run, the text is untouched and the reason is reported. */
  @Test
  fun driverFailure_reportsErrorAndLeavesTextUntouched() = runBlocking {
    val failing =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
          throw IllegalStateException("Model not downloaded yet")
      }
    val input = "he go to school"

    val result =
      InferenceEngineImpl(failing, modelPathProvider = { "/m.gguf" }).generateCorrection(input)

    assertFalse(result.isSuccess)
    assertEquals(input, result.correctedText)
    assertTrue(result.diffs.isEmpty())
    assertEquals("Model not downloaded yet", result.errorMessage)
  }

  /** The prompt the driver receives is the Qwen3 ChatML prompt, in non-thinking mode. */
  @Test
  fun driverReceivesNonThinkingChatMlPrompt() = runBlocking {
    var seen: CorrectionPrompt? = null
    val recording =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
          seen = prompt
          return "He goes to school."
        }
      }

    InferenceEngineImpl(recording, modelPathProvider = { "/m.gguf" })
      .generateCorrection("he go to school", enforceNoThink = true)

    assertEquals("he go to school", seen?.userText)
    assertTrue(seen?.enforceNoThink == true)

    val rendered = QwenPromptBuilder.buildPrompt(seen!!)
    assertTrue(rendered.startsWith("<|im_start|>system"))
    assertTrue(rendered.contains("<|im_start|>user\nhe go to school<|im_end|>"))
    assertTrue(rendered.endsWith("<|im_start|>assistant\n" + QwenPromptBuilder.EMPTY_THINK))
  }

  /** The chosen tone decides the system prompt, the reasoning step and the token budget. */
  @Test
  fun generateCorrection_sendsTheSelectedTonesPrompt() = runBlocking {
    var seen: CorrectionPrompt? = null
    val recording =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
          seen = prompt
          return "We must ship this."
        }
      }

    val result =
      InferenceEngineImpl(recording, modelPathProvider = { "/m.gguf" })
        .generateCorrection(
          text = "we gotta ship this thing",
          enforceNoThink = true,
          tone = DefaultTonePresets.SHORTEN,
        )

    assertEquals(DefaultTonePresets.SHORTEN.systemPrompt, seen?.systemPrompt)
    assertEquals(DefaultTonePresets.SHORTEN.maxOutputTokens, seen?.maxOutputTokens)
    // Shorten declares usesThinking, so the reasoning step survives the fast-mode request.
    assertFalse(seen?.enforceNoThink == true)
    assertFalse(result.isNonThinkingMode)
    assertEquals(DefaultTonePresets.SHORTEN_ID, result.tonePresetId)
    assertEquals("We must ship this.", result.correctedText)
  }

  /** Callers that ask for no tone — the overlay, the live editor — proofread exactly as before. */
  @Test
  fun generateCorrection_defaultsToTheProofreadingTone() = runBlocking {
    var seen: CorrectionPrompt? = null
    val recording =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
          seen = prompt
          return "He goes to school."
        }
      }

    val result =
      InferenceEngineImpl(recording, modelPathProvider = { "/m.gguf" })
        .generateCorrection("he go to school", enforceNoThink = true)

    assertEquals(Prompts.Proofread.SYSTEM, seen?.systemPrompt)
    assertEquals(Prompts.Proofread.EXAMPLES, seen?.examples)
    assertTrue(seen?.enforceNoThink == true)
    assertEquals(DefaultTonePresets.POLISH_ID, result.tonePresetId)
  }

  /** A thinking tone streams its reasoning first; only the answer may reach the editor. */
  @Test
  fun generateCorrection_stripsReasoningFromAThinkingTone() = runBlocking {
    val thinking =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
          "<think>\nThe user wants this shorter.\n</think>\n\nWe must ship this."
      }

    val result =
      InferenceEngineImpl(thinking, modelPathProvider = { "/m.gguf" })
        .generateCorrection("we gotta ship this thing", tone = DefaultTonePresets.SHORTEN)

    assertEquals("We must ship this.", result.correctedText)
  }

  /**
   * A driver with a fixed context window, counting one token per character so the arithmetic in
   * these tests is readable. It answers by shouting back whatever it was given.
   */
  private class WindowedDriver(
    private val window: Int = 2048,
    private val overhead: Int = 300,
  ) : LlmDriver {
    val requests = mutableListOf<CorrectionPrompt>()

    override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
      requests += prompt
      return prompt.userText.uppercase()
    }

    override suspend fun tokenWindow(engineMode: EngineMode) =
      object : TokenWindow {
        override val contextTokens = window

        override suspend fun countTokens(text: String) = text.length

        override suspend fun overheadTokens(prompt: CorrectionPrompt) = overhead
      }
  }

  /** The overlay's short proofreading requests keep the budget they have always been sent with. */
  @Test
  fun textThatFitsIsSentAsOneRequestWithTheTonesOwnBudget() = runBlocking {
    val driver = WindowedDriver()

    val result =
      InferenceEngineImpl(driver, modelPathProvider = { "/m.gguf" })
        .generateCorrection("he go to school", enforceNoThink = true)

    assertEquals(1, driver.requests.size)
    assertEquals("he go to school", driver.requests.single().userText)
    assertEquals(TonePreset.DEFAULT_MAX_OUTPUT_TOKENS, driver.requests.single().maxOutputTokens)
    assertEquals("HE GO TO SCHOOL", result.correctedText)
  }

  /** Past the tone's cap the answer gets room for the text it was asked to rewrite. */
  @Test
  fun aLongerInputGetsMoreRoomForItsAnswerThanTheTonesCap() = runBlocking {
    val driver = WindowedDriver()
    val text = "b".repeat(600)

    InferenceEngineImpl(driver, modelPathProvider = { "/m.gguf" })
      .generateCorrection(text, enforceNoThink = true)

    // One request still: 600 tokens fit. The answer is sized from the input, not capped at 256.
    assertEquals(1, driver.requests.size)
    assertEquals(
      600 + ContextBudget.ANSWER_HEADROOM_TOKENS,
      driver.requests.single().maxOutputTokens,
    )
  }

  /** Text past one request is split, rewritten a piece at a time and put back as it was. */
  @Test
  fun textTooLongForOneRequestIsSplitAndRejoined() = runBlocking {
    val driver = WindowedDriver(window = 400, overhead = 100)
    val paragraph = List(16) { "aaaa" }.joinToString(" ")
    val text = List(3) { paragraph }.joinToString("\n\n")

    val result =
      InferenceEngineImpl(driver, modelPathProvider = { "/m.gguf" })
        .generateCorrection(text, enforceNoThink = true)

    assertEquals(3, driver.requests.size)
    driver.requests.forEach { assertEquals(paragraph, it.userText) }
    // Every piece is asked for with the tone the user chose.
    driver.requests.forEach { assertEquals(Prompts.Proofread.SYSTEM, it.systemPrompt) }
    assertEquals(List(3) { paragraph.uppercase() }.joinToString("\n\n"), result.correctedText)
    assertTrue(result.isSuccess)
  }

  /** A tone whose own prompt fills the window says so instead of failing inside the model. */
  @Test
  fun aPromptThatFillsTheWindowReportsTheReason() = runBlocking {
    val driver = WindowedDriver(window = 100, overhead = 200)
    val text = "he go to school"

    val result =
      InferenceEngineImpl(driver, modelPathProvider = { "/m.gguf" }).generateCorrection(text)

    assertFalse(result.isSuccess)
    assertEquals(text, result.correctedText)
    assertTrue(driver.requests.isEmpty())
    assertTrue(result.errorMessage!!.contains("fills the whole context window"))
  }

  /** An engine that manages its own window is handed the text whole, however long it is. */
  @Test
  fun anEngineWithNoWindowIsNeverSplit() = runBlocking {
    var requests = 0
    val unbounded =
      object : LlmDriver {
        override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
          requests++
          return prompt.userText.uppercase()
        }
      }
    val text = List(50) { "a long paragraph of text." }.joinToString("\n\n")

    val result =
      InferenceEngineImpl(unbounded, modelPathProvider = { "/m.gguf" }).generateCorrection(text)

    assertEquals(1, requests)
    assertEquals(text.uppercase(), result.correctedText)
  }
}
