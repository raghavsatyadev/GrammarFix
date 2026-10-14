package com.raghavsatyadev.grammarfix.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #22: the overlay had no way to be closed. It now reports the whole drag — start, deltas and
 * release — so the host can offer a drop-to-close target, and draws that target itself.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the overlay needs no DI, and the app's own one starts Koin, which
// survives between tests in a shared JVM and fails the second one to ask for it.
@Config(sdk = [34], application = android.app.Application::class)
class OverlayDismissTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var dragStarts = 0
  private var dragEnds = 0
  private var dragCancels = 0
  private var opened = 0
  private val dragged = mutableListOf<Offset>()

  private val actions =
    SuggestionOverlayActions(
      onAccept = {},
      onDismiss = {},
      onAcceptAll = {},
      onOpenSheet = {},
      onCloseSheet = {},
      onDragStart = { dragStarts++ },
      onDrag = { dragged += it },
      onDragEnd = { dragEnds++ },
      onDragCancel = { dragCancels++ },
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

  /** Without a release the host can never tell that the bubble was dropped on the close target. */
  @Test
  fun draggingTheBubbleReportsTheStartAndTheReleaseOfTheDrag() {
    showIdle()

    composeTestRule.onNode(hasClickAction()).performTouchInput { swipeDown() }

    assertEquals("the drag never started", 1, dragStarts)
    assertTrue("the drag never reached the host", dragged.isNotEmpty())
    assertEquals("the release never reached the host", 1, dragEnds)
    assertEquals(0, dragCancels)
  }

  /** Issue #23 must not regress: a tap is not a drag, and it still opens the app. */
  @Test
  fun tappingTheBubbleIsNotADragAndStillOpensTheApp() {
    showIdle()

    composeTestRule.onNode(hasClickAction()).performClick()

    assertEquals(1, opened)
    assertEquals(0, dragStarts)
    assertEquals(0, dragEnds)
  }

  /** Dragging is still only dragging. */
  @Test
  fun draggingTheBubbleDoesNotOpenTheApp() {
    showIdle()

    composeTestRule.onNode(hasClickAction()).performTouchInput { swipeDown() }

    assertEquals(0, opened)
  }

  @Test
  fun theCloseTargetSaysWhatDroppingTheBubbleOnItDoes() {
    composeTestRule.setContent { DismissTarget(armed = false) }

    composeTestRule.onNodeWithContentDescription("Close the assistant").assertExists()
  }

  /** Armed is the moment before the user commits, so the target has to stay on screen. */
  @Test
  fun theCloseTargetStaysOnScreenOnceItHasCaughtTheBubble() {
    composeTestRule.setContent { DismissTarget(armed = true) }

    composeTestRule.onNodeWithContentDescription("Close the assistant").assertExists()
  }

  /**
   * The armed target grows by a `graphicsLayer` scale, which draws larger without laying out
   * larger. The host draws this in a wrap-content window, so unless the layout reserves the grown
   * size the window is measured for the resting circle and the growth is clipped off the top.
   */
  @Test
  fun theCloseTargetReservesRoomForTheGrowthSoTheWindowDoesNotClipIt() {
    composeTestRule.setContent { DismissTarget(armed = true) }

    val height = composeTestRule.onRoot().getUnclippedBoundsInRoot().height
    val armedRadius = DismissTargetDiameter * ARMED_SCALE / 2f
    val needed = DismissTargetBottomMargin + DismissTargetDiameter / 2f + armedRadius

    assertTrue(
      "the target is laid out ${height.value}dp tall but the grown circle needs " +
        "${needed.value}dp, so its top is clipped",
      height.value >= needed.value,
    )
  }

  /**
   * `DragToDismiss` computes where the drop is caught from [DismissTargetBottomMargin] and
   * [DismissTargetDiameter] alone. Reserving room for the growth must not move the circle, or the
   * target would catch the bubble somewhere other than where it is drawn.
   */
  @Test
  fun reservingThatRoomDoesNotMoveTheTargetsCentre() {
    composeTestRule.setContent { DismissTarget(armed = false) }

    val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
    val icon =
      composeTestRule.onNodeWithContentDescription("Close the assistant").getUnclippedBoundsInRoot()
    val centreFromBottom = root.height - (icon.top + icon.height / 2f)
    val expected = DismissTargetBottomMargin + DismissTargetDiameter / 2f

    assertEquals(expected.value, centreFromBottom.value, 0.5f)
  }

  private companion object {
    /** Mirrors the private growth factor in `DismissTarget`. */
    const val ARMED_SCALE = 1.25f
  }
}
