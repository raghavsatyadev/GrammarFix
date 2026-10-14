package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarEngineTest {

  private val testDriver =
    object : LlmDriver {
      override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String {
        val userText = prompt.userText.trim()
        return when (userText.lowercase()) {
          "he go to school" -> "He goes to school."
          "hello how is you?" -> "Hello, how are you?"
          "he go to school yesterday and he don't like it and he recieve bad grade" ->
            "He went to school yesterday and he didn't like it, and he received a bad grade."
          "teh car" -> "The car."
          "is user ofay? do he needs any halp." -> "Is the user okay? Does he need any help?"
          "the car is clean." -> "The car is clean."
          "hello world, everything looks great." -> "Hello world, everything looks great."
          else -> userText
        }
      }
    }

  private val engine = GrammarEngine(inferenceEngine = InferenceEngineImpl(llmDriver = testDriver))

  @Test
  fun analyzeText_detectsTargetWord_returnsSuggestions() = runBlocking {
    val input = "he go to school"
    val suggestions = engine.analyzeText(input)

    assertTrue(suggestions.isNotEmpty())
    assertEquals("go", suggestions[0].original.lowercase())
    assertEquals("goes", suggestions[0].replacement.lowercase())
  }

  @Test
  fun analyzeText_detectsHowIsYou_replacesWithHowAreYou() = runBlocking {
    val input = "Hello How is you?"
    val suggestions = engine.analyzeText(input)

    assertTrue(suggestions.isNotEmpty())
    val isSuggestion = suggestions.firstOrNull { it.original.equals("is", ignoreCase = true) }
    assertNotNull(isSuggestion)
    assertEquals("are", isSuggestion!!.replacement.lowercase())

    val helloSuggestion = suggestions.firstOrNull { it.original.equals("Hello", ignoreCase = true) }
    assertNotNull(helloSuggestion)
    assertEquals("Hello,", helloSuggestion!!.replacement)
  }

  @Test
  fun analyzeText_catchesMultipleErrorsInSingleSentence() = runBlocking {
    val input = "he go to school yesterday and he don't like it and he recieve bad grade"
    val suggestions = engine.analyzeText(input)

    assertTrue(suggestions.size >= 3)
  }

  @Test
  fun analyzeText_quickRules_detectsSpellingError() = runBlocking {
    val input = "teh car"
    val suggestions = engine.analyzeText(input)

    assertTrue(suggestions.isNotEmpty())
    assertTrue(suggestions[0].original.lowercase().contains("teh"))
    assertTrue(suggestions[0].replacement.lowercase().contains("the"))
  }

  @Test
  fun analyzeText_noMatch_returnsEmptyList() = runBlocking {
    val input = "Hello world, everything looks great."
    val suggestions = engine.analyzeText(input)

    assertTrue(suggestions.isEmpty())
  }

  @Test
  fun analyze_correctedText_containsTheFix() = runBlocking {
    val result = engine.analyze("he go to school")

    assertTrue(result.correctedText.contains("goes"))
  }

  @Test
  fun analyze_correctText_isLeftAsTyped() = runBlocking {
    val input = "The car is clean."
    val result = engine.analyze(input)

    assertTrue(result.suggestions.isEmpty())
    assertEquals(input, result.correctedText)
  }
}
