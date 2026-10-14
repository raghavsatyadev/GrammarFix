package com.raghavsatyadev.grammarfix.service

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.raghavsatyadev.grammarfix.ui.DismissTarget
import com.raghavsatyadev.grammarfix.ui.DismissTargetBottomMargin
import com.raghavsatyadev.grammarfix.ui.DismissTargetCaptureRadius
import com.raghavsatyadev.grammarfix.ui.DismissTargetDiameter

/**
 * One drag of the floating overlay, measured against the drop-to-close target ([DismissTarget]) at
 * the bottom of the screen (issue #22).
 *
 * All of the dismiss decision lives here, in plain screen pixels, so it can be tested without a
 * window: [FloatingWidgetService] only has to place the window where [move] says and stop itself
 * when [end] says so.
 */
internal class DragToDismiss(
  private val centerX: Int,
  private val centerY: Int,
  private val captureRadius: Int,
) {

  /**
   * Where the window would be if it simply followed the finger. Kept apart from the window's real
   * position so that a caught overlay can snap onto the target and still be dragged back out: the
   * finger, not the snapped bubble, decides when it escapes.
   */
  private var freeX = 0
  private var freeY = 0

  /** The target has caught the overlay, so letting go now closes the assistant. */
  var isArmed = false
    private set

  /** Begins a drag from the window's current position. */
  fun start(x: Int, y: Int) {
    freeX = x
    freeY = y
    isArmed = false
  }

  /**
   * Applies one drag delta for an overlay window of [width] by [height], and returns the position
   * the window should take: the finger's, or the target's once it has been caught.
   */
  fun move(dx: Int, dy: Int, width: Int, height: Int): IntOffset {
    freeX += dx
    freeY += dy
    isArmed = catches(freeX + width / 2, freeY + height / 2)
    return if (isArmed) IntOffset(centerX - width / 2, centerY - height / 2)
    else IntOffset(freeX, freeY)
  }

  /**
   * Ends the drag and reports whether the assistant should be closed. A [cancelled] drag was
   * interrupted rather than released, so it never closes anything.
   */
  fun end(cancelled: Boolean): Boolean {
    val dismiss = isArmed && !cancelled
    isArmed = false
    return dismiss
  }

  private fun catches(x: Int, y: Int): Boolean {
    val dx = (x - centerX).toLong()
    val dy = (y - centerY).toLong()
    return dx * dx + dy * dy <= captureRadius.toLong() * captureRadius
  }

  companion object {
    /**
     * Places the target bottom-centre of a [screenWidth] by [screenHeight] screen, matching how
     * [DismissTarget] lays itself out in its own full-width window.
     */
    fun forScreen(screenWidth: Int, screenHeight: Int, density: Float): DragToDismiss {
      fun px(dp: Dp) = (dp.value * density).toInt()
      return DragToDismiss(
        centerX = screenWidth / 2,
        centerY = screenHeight - px(DismissTargetBottomMargin) - px(DismissTargetDiameter) / 2,
        captureRadius = px(DismissTargetCaptureRadius),
      )
    }
  }
}
