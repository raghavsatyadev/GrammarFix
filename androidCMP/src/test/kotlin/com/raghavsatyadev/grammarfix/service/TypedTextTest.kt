package com.raghavsatyadev.grammarfix.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** An empty field's hint is never taken for text the user typed. */
class TypedTextTest {

  @Test
  fun aFieldThatSaysItShowsItsHintIsEmpty() {
    assertEquals("", typedText("Message", "Message", showingHint = true))
  }

  /** A field can report its hint as text without saying so, as WhatsApp's message box does. */
  @Test
  fun textEqualToTheHintIsEmpty() {
    assertEquals("", typedText("Message", "Message", showingHint = false))
  }

  @Test
  fun typedTextIsKept() {
    assertEquals("Hello How is your? ", typedText("Hello How is your? ", "Message", false))
    assertEquals("Message", typedText("Message", null, false))
    assertEquals("", typedText(null, null, false))
  }
}
