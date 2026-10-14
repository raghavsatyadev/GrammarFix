package com.raghavsatyadev.grammarfix.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a tap on the idle bubble does. Grammarly opens its rewrite tools from a clean field, so ours
 * does too (#31); opening the app (#23) remains for when there is nothing to rewrite.
 */
class BubbleTapActionTest {

  @Test
  fun aFieldWithTextOpensTheRewritePanel() {
    assertEquals(BubbleTapAction.REWRITE, bubbleTapAction("The report is ready."))
  }

  /** No editable field of another app is focused: there is nowhere to write a rewrite back to. */
  @Test
  fun noFocusedFieldOpensTheApp() {
    assertEquals(BubbleTapAction.OPEN_APP, bubbleTapAction(null))
  }

  @Test
  fun anEmptyFieldDoesNothing() {
    assertEquals(BubbleTapAction.NOTHING, bubbleTapAction(""))
  }

  /** Whitespace is nothing the model could rewrite. */
  @Test
  fun aBlankFieldDoesNothing() {
    assertEquals(BubbleTapAction.NOTHING, bubbleTapAction("  \n "))
  }
}
