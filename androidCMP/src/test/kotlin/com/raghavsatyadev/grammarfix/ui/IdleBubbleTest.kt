package com.raghavsatyadev.grammarfix.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issues #23 and #31: with nothing to suggest, a tap on the bubble reaches the host, which opens
 * the rewrite panel or, with no text to rewrite, the app (see `BubbleTapActionTest`). Dragging it
 * must still only move it.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the overlay needs no DI, and the app's own one starts Koin, which
// survives between tests in a shared JVM and fails the second one to ask for it.
@Config(sdk = [34], application = android.app.Application::class)
class IdleBubbleTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var opened = 0
  private val dragged = mutableListOf<Offset>()

  private val actions =
    SuggestionOverlayActions(
      onAccept = {},
      onDismiss = {},
      onAcceptAll = {},
      onOpenSheet = {},
      onCloseSheet = {},
      onDragStart = {},
      onDrag = { dragged += it },
      onDragEnd = {},
      onDragCancel = {},
      onBubbleTap = { opened++ },
      onOpenRewrite = {},
      onSelectTone = {},
      onSendInstruction = {},
      onInstructionFocus = {},
      onUseRewrite = {},
      onCloseRewrite = {},
    )

  private fun showIdle() {
    composeTestRule.setContent { SuggestionOverlay(SuggestionOverlayState.Idle, actions) }
  }

  @Test
  fun tappingTheIdleBubbleReportsTheTap() {
    showIdle()

    composeTestRule.onNode(hasClickAction()).performClick()

    assertEquals(1, opened)
  }

  @Test
  fun draggingTheIdleBubbleMovesItInsteadOfReportingATap() {
    showIdle()

    composeTestRule.onNode(hasClickAction()).performTouchInput { swipeRight() }

    assertTrue("the drag never reached the host", dragged.isNotEmpty())
    assertEquals(0, opened)
  }

  @Test
  fun theBubbleNamesWhatATapDoes() {
    showIdle()

    val node = composeTestRule.onNode(hasClickAction()).fetchSemanticsNode()
    assertEquals(
      "Rewrite your text, or open GrammarFix",
      node.config[SemanticsActions.OnClick].label,
    )
  }

  @Test
  fun theBubbleStillSaysWhatItIsDoing() {
    showIdle()

    composeTestRule.onNodeWithContentDescription("Checking as you type").assertExists()
  }

  /** Only the idle bubble reports a tap; with a fix on offer the tap opens the sheet instead. */
  @Test
  fun theReviewPillOpensTheSheetRatherThanReportingABubbleTap() {
    val suggestion =
      TextSuggestion(original = "is", replacement = "are", startIndex = 10, endIndex = 12)
    composeTestRule.setContent {
      SuggestionOverlay(
        SuggestionOverlayState.Review("The cats is here", listOf(suggestion)),
        actions,
      )
    }

    // The pill's count is its first button; the fix beside it and its bin follow.
    composeTestRule.onAllNodes(hasClickAction())[0].performClick()

    assertEquals(0, opened)
  }
}
