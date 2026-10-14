package com.raghavsatyadev.grammarfix.service

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #22: the floating bubble had no way to be closed. Dropping it on the close target at the
 * bottom of the screen now closes the assistant.
 *
 * Density is 1 throughout, so every number below is both dp and px.
 */
class DragToDismissTest {

  private val screenWidth = 1080
  private val screenHeight = 2400
  private val bubble = 64

  // Bottom-centre of the screen: 56 dp margin, then half of the 64 dp target.
  private val targetX = screenWidth / 2
  private val targetY = screenHeight - 56 - 32

  private fun session() = DragToDismiss.forScreen(screenWidth, screenHeight, density = 1f)

  /** Drag delta that takes a bubble whose top-left is at ([fromX], [fromY]) onto ([toX], [toY]). */
  private fun deltaTo(fromX: Int, fromY: Int, toX: Int, toY: Int): Pair<Int, Int> =
    (toX - bubble / 2 - fromX) to (toY - bubble / 2 - fromY)

  @Test
  fun droppingTheBubbleOnTheTargetClosesTheAssistant() {
    val drag = session()
    drag.start(100, 300)

    val (dx, dy) = deltaTo(100, 300, targetX, targetY)
    drag.move(dx, dy, bubble, bubble)

    assertTrue("the target should have caught the bubble", drag.isArmed)
    assertTrue("letting go on the target must close the assistant", drag.end(cancelled = false))
  }

  @Test
  fun droppingTheBubbleAnywhereElseOnlyMovesIt() {
    val drag = session()
    drag.start(100, 300)

    val at = drag.move(40, 25, bubble, bubble)

    assertEquals(IntOffset(140, 325), at)
    assertFalse(drag.isArmed)
    assertFalse("an ordinary drag must not close anything", drag.end(cancelled = false))
  }

  /** The bubble snaps onto the target, which is the cue that a release would close it. */
  @Test
  fun aCaughtBubbleSnapsOntoTheTarget() {
    val drag = session()
    drag.start(100, 300)

    // Close enough to be caught, but not exactly on the target.
    val (dx, dy) = deltaTo(100, 300, targetX + 40, targetY - 40)
    val at = drag.move(dx, dy, bubble, bubble)

    assertTrue(drag.isArmed)
    assertEquals(IntOffset(targetX - bubble / 2, targetY - bubble / 2), at)
  }

  /** Snapping must not trap the bubble: the finger, not the snapped window, decides. */
  @Test
  fun draggingBackOutOfTheTargetCallsTheCloseOff() {
    val drag = session()
    drag.start(100, 300)
    val (dx, dy) = deltaTo(100, 300, targetX, targetY)
    drag.move(dx, dy, bubble, bubble)
    assertTrue(drag.isArmed)

    val at = drag.move(-400, 0, bubble, bubble)

    assertFalse("the bubble left the target", drag.isArmed)
    assertEquals(IntOffset(targetX - bubble / 2 - 400, targetY - bubble / 2), at)
    assertFalse(drag.end(cancelled = false))
  }

  @Test
  fun anInterruptedDragNeverCloses() {
    val drag = session()
    drag.start(100, 300)
    val (dx, dy) = deltaTo(100, 300, targetX, targetY)
    drag.move(dx, dy, bubble, bubble)

    assertFalse("a cancelled gesture is not a release", drag.end(cancelled = true))
  }

  /** The catch area forgives aim, because the finger covers the bubble. */
  @Test
  fun theTargetCatchesFromJustUnderNinetySixPixelsAway() {
    val justInside = session().apply { start(0, 0) }
    val (inX, inY) = deltaTo(0, 0, targetX + 96, targetY)
    justInside.move(inX, inY, bubble, bubble)
    assertTrue(justInside.isArmed)

    val justOutside = session().apply { start(0, 0) }
    val (outX, outY) = deltaTo(0, 0, targetX + 97, targetY)
    justOutside.move(outX, outY, bubble, bubble)
    assertFalse(justOutside.isArmed)
  }

  /** The target is at the bottom of the screen, so the top of the screen never closes anything. */
  @Test
  fun theTopOfTheScreenIsNotACloseTarget() {
    val drag = session()
    drag.start(targetX - bubble / 2, 0)

    drag.move(0, 0, bubble, bubble)

    assertFalse(drag.isArmed)
  }

  /** A second drag starts clean, whatever the one before it did. */
  @Test
  fun eachDragStartsDisarmed() {
    val drag = session()
    drag.start(100, 300)
    val (dx, dy) = deltaTo(100, 300, targetX, targetY)
    drag.move(dx, dy, bubble, bubble)
    drag.end(cancelled = false)

    drag.start(100, 300)

    assertFalse(drag.isArmed)
    assertFalse(drag.end(cancelled = false))
  }
}
