package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One token per character, so every boundary in these tests is easy to count by eye. */
private val perCharacter: suspend (String) -> Int = { it.length }

class TextChunkerTest {

  @Test
  fun cutsAtParagraphBoundariesFirst() = runBlocking {
    val chunked = TextChunker.split("aaa\n\nbbb\n\nccc", maxTokens = 4, countTokens = perCharacter)

    assertEquals(listOf("aaa", "bbb", "ccc"), chunked.chunks.map { it.text })
    assertEquals(listOf("", "\n\n", "\n\n"), chunked.chunks.map { it.separatorBefore })
    assertEquals("X\n\nY\n\nZ", chunked.rejoin(listOf("X", "Y", "Z")))
  }

  /** Neighbouring paragraphs travel together while they still fit in one request. */
  @Test
  fun packsWhatFitsIntoOneChunk() = runBlocking {
    val chunked = TextChunker.split("aaa\n\nbbb\n\nccc", maxTokens = 7, countTokens = perCharacter)

    assertEquals(listOf("aaa\n\nbbb", "ccc"), chunked.chunks.map { it.text })
    assertEquals("XY\n\nZ", chunked.rejoin(listOf("XY", "Z")))
  }

  @Test
  fun fallsBackToSentencesWhenAParagraphIsTooLong() = runBlocking {
    val text = "One sentence here. Two sentence here."

    val chunked = TextChunker.split(text, maxTokens = 20, countTokens = perCharacter)

    assertEquals(listOf("One sentence here.", "Two sentence here."), chunked.chunks.map { it.text })
    assertEquals("A B", chunked.rejoin(listOf("A", "B")))
  }

  /** A closing quote belongs to the sentence it ends, not to the one that follows. */
  @Test
  fun keepsClosingPunctuationWithItsSentence() = runBlocking {
    val text = "He said \"go home.\" She stayed where she was."

    // 30 is under the 44 the two sentences make together, and over the 25 the longer one needs.
    val chunked = TextChunker.split(text, maxTokens = 30, countTokens = perCharacter)

    assertEquals(
      listOf("He said \"go home.\"", "She stayed where she was."),
      chunked.chunks.map { it.text },
    )
  }

  @Test
  fun fallsBackToWordsWhenASentenceIsTooLong() = runBlocking {
    val chunked = TextChunker.split("aaaa bbbb cccc", maxTokens = 9, countTokens = perCharacter)

    assertEquals(listOf("aaaa bbbb", "cccc"), chunked.chunks.map { it.text })
    assertEquals("X Y", chunked.rejoin(listOf("X", "Y")))
  }

  /** A URL or a base64 blob has no boundary to cut on, and still has to move. */
  @Test
  fun cutsAnUnbrokenRunByLength() = runBlocking {
    val chunked = TextChunker.split("a".repeat(25), maxTokens = 10, countTokens = perCharacter)

    assertEquals(listOf(10, 10, 5), chunked.chunks.map { it.text.length })
    assertTrue(chunked.chunks.drop(1).all { it.separatorBefore.isEmpty() })
    assertEquals("XYZ", chunked.rejoin(listOf("X", "Y", "Z")))
  }

  @Test
  fun keepsTheTextsOwnLeadingAndTrailingWhitespace() = runBlocking {
    val chunked =
      TextChunker.split("  \nalpha\n\nbeta\n ", maxTokens = 6, countTokens = perCharacter)

    assertEquals(listOf("alpha", "beta"), chunked.chunks.map { it.text })
    assertEquals("  \nA\n\nB\n ", chunked.rejoin(listOf("A", "B")))
  }

  @Test
  fun noChunkExceedsTheBudget() = runBlocking {
    val text =
      "The first paragraph runs on for a while and says several things.\n\n" +
        "The second one is shorter. But it has two sentences.\n\n" +
        "third_paragraph_is_one_very_long_unbroken_token_with_no_spaces_at_all_in_it"

    val chunked = TextChunker.split(text, maxTokens = 30, countTokens = perCharacter)

    assertTrue(chunked.chunks.isNotEmpty())
    chunked.chunks.forEach { assertTrue("chunk over budget: ${it.text}", it.tokens <= 30) }
  }

  /** Rejoining the chunks unchanged has to give back exactly what was split. */
  @Test
  fun rejoiningTheOriginalChunksRebuildsTheText() = runBlocking {
    val text = "  Alpha one. Alpha two.\n\n\nBeta one.\n\nGamma one. Gamma two. Gamma three.  "

    val chunked = TextChunker.split(text, maxTokens = 15, countTokens = perCharacter)

    assertEquals(text, chunked.rejoin(chunked.chunks.map { it.text }))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejoinRejectsAMismatchedNumberOfRewrites() = runBlocking {
    val chunked = TextChunker.split("aaa\n\nbbb", maxTokens = 4, countTokens = perCharacter)
    chunked.rejoin(listOf("only one"))
    Unit
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsABudgetWithNoRoomInIt() = runBlocking {
    TextChunker.split("anything", maxTokens = 0, countTokens = perCharacter)
    Unit
  }

  // --- sentence counting, which analytics reuses rather than writing a second scan --------------

  @Test
  fun countsSentencesByTheBoundariesItSplitsOn() {
    assertEquals(0, TextChunker.sentenceCount(""))
    assertEquals(0, TextChunker.sentenceCount("   \n  "))
    assertEquals(1, TextChunker.sentenceCount("no terminator here"))
    assertEquals(1, TextChunker.sentenceCount("  One sentence.  "))
    assertEquals(2, TextChunker.sentenceCount("One sentence. And a second one!"))
    assertEquals(3, TextChunker.sentenceCount("Who? Me. Yes…"))
    // A closing quote still belongs to the sentence it ends.
    assertEquals(2, TextChunker.sentenceCount("\"Go.\" She left."))
    // A blank line between paragraphs is still one boundary, not two sentences' worth.
    assertEquals(2, TextChunker.sentenceCount("First para.\n\nSecond para."))
  }
}
