package com.raghavsatyadev.grammarfix.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #30: a fix is read inside the sentence it belongs to, so the user can see what changes
 * without holding the original in their head.
 */
class SuggestionInContextTest {

  private val removed = SpanStyle(color = Color.Gray, textDecoration = TextDecoration.LineThrough)
  private val added = SpanStyle(color = Color.Green)

  private fun render(text: String, suggestion: TextSuggestion, window: Int = 90) =
    suggestionInContext(text, suggestion, removed, added, window)

  @Test
  fun aReplacementKeepsTheWholeSentenceAroundTheFix() {
    val text = "The cats is here"
    val fix = TextSuggestion(original = "is", replacement = "are", startIndex = 9, endIndex = 11)

    assertEquals("The cats is are here", render(text, fix).text)
  }

  @Test
  fun theOldWordIsStruckThroughAndTheNewOneIsNot() {
    val text = "The cats is here"
    val fix = TextSuggestion(original = "is", replacement = "are", startIndex = 9, endIndex = 11)

    val rendered = render(text, fix)
    val struck = rendered.spanStyles.single { it.item.textDecoration == TextDecoration.LineThrough }

    assertEquals("is", rendered.text.substring(struck.start, struck.end))
    val highlighted = rendered.spanStyles.single { it.item == added }
    assertEquals("are", rendered.text.substring(highlighted.start, highlighted.end))
  }

  /** An insertion has nothing to strike through. */
  @Test
  fun anInsertionShowsOnlyTheWordGoingIn() {
    val text = "cats are here"
    val fix = TextSuggestion(original = "", replacement = "The", startIndex = 0, endIndex = 0)

    val rendered = render(text, fix)

    assertEquals("Thecats are here", rendered.text)
    assertTrue(
      "an insertion struck something through",
      rendered.spanStyles.none { it.item.textDecoration == TextDecoration.LineThrough },
    )
  }

  /**
   * A deletion's range swallows the space after the word so no double space is left in the field.
   * Striking that range through would show a gap the user cannot account for, so only the word is
   * struck.
   */
  @Test
  fun aDeletionStrikesTheWordButNotTheSpaceItAlsoRemoves() {
    val text = "The very cats are here"
    val fix = TextSuggestion(original = "very", replacement = "", startIndex = 4, endIndex = 9)

    val rendered = render(text, fix)

    assertEquals("The verycats are here", rendered.text)
    val struck = rendered.spanStyles.single { it.item.textDecoration == TextDecoration.LineThrough }
    assertEquals("very", rendered.text.substring(struck.start, struck.end))
  }

  @Test
  fun longTextIsCutDownOnBothSidesAndMarkedWithAnEllipsis() {
    val before = "word ".repeat(40)
    val after = " ".plus("tail ".repeat(40))
    val text = before + "is" + after
    val fix =
      TextSuggestion(
        original = "is",
        replacement = "are",
        startIndex = before.length,
        endIndex = before.length + 2,
      )

    val rendered = render(text, fix, window = 30).text

    assertTrue("the context did not start with an ellipsis", rendered.startsWith("…"))
    assertTrue("the context did not end with an ellipsis", rendered.endsWith("…"))
    assertTrue("the fix was cut out of its own context", rendered.contains("is are"))
    assertTrue("the context was not cut down", rendered.length < text.length)
  }

  /** The window cuts at a space, so the context never begins or ends mid-word. */
  @Test
  fun theCutFallsOnAWordBoundary() {
    val before = "alpha bravo charlie delta echo foxtrot golf hotel india juliett "
    val text = before + "teh" + " kilo lima mike november oscar papa quebec romeo sierra"
    val fix =
      TextSuggestion(
        original = "teh",
        replacement = "the",
        startIndex = before.length,
        endIndex = before.length + 3,
      )

    val rendered = render(text, fix, window = 20).text

    assertTrue("the context started mid-word", rendered.startsWith("… "))
    assertTrue("the context did not end with an ellipsis", rendered.endsWith("…"))
    // Everything kept from the original is a whole word; the replacement is new, so it is not.
    val kept = rendered.removePrefix("… ").removeSuffix("…").replace("teh the ", "")
    for (word in kept.split(" ").filter { it.isNotEmpty() }) {
      assertTrue("'$word' is not a whole word from the text", " $word " in " $text ")
    }
  }

  /** Indices that no longer fit the text must not throw; the overlay outlives one edit. */
  @Test
  fun anOutOfRangeFixIsClampedInsteadOfCrashing() {
    val text = "short"
    val fix = TextSuggestion(original = "is", replacement = "are", startIndex = 40, endIndex = 90)

    assertEquals("shortis are", render(text, fix).text)
  }
}
