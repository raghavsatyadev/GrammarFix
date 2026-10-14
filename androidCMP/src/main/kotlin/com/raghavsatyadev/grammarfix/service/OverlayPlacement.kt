package com.raghavsatyadev.grammarfix.service

import kotlin.math.roundToInt

/**
 * Where the overlay's window sits, in the pixels [android.view.WindowManager.LayoutParams] wants
 * but measured in density-independent units (issue #38).
 *
 * A window's `x` and `y` are raw pixels, and a pixel is a different size on every screen. The same
 * literal put the bubble 9 dp from the edge on a Pixel 10 Pro and 6 dp from it on an S25 Ultra —
 * the denser the screen, the tighter into the corner, which is backwards, since a denser screen is
 * usually a physically larger one.
 *
 * The figures below are dp, converted here and nowhere else, so the bubble rests the same physical
 * distance from the corner on every phone and the pill keeps the same gap above the field it
 * belongs to. [DragToDismiss.forScreen] already worked this way; only window placement did not.
 */
internal object OverlayPlacement {

  /** Left margin of the resting bubble. */
  private const val RESTING_X_DP = 8

  /**
   * How far down the resting bubble starts: clear of the status bar and anything docked near it.
   */
  private const val RESTING_Y_DP = 112

  /** The gap the pill keeps above the field being edited. */
  private const val FIELD_GAP_DP = 80

  /** A field nearer the top than this has no room above it, so the pill stays where it is. */
  private const val MIN_FIELD_TOP_DP = 96

  /** The pill never rides higher than this, where the status bar would swallow it. */
  private const val MIN_PILL_TOP_DP = 40

  /** Where the collapsed bubble rests until the user drags it somewhere else. */
  fun restingPosition(density: Float): Pair<Int, Int> =
    RESTING_X_DP.toPx(density) to RESTING_Y_DP.toPx(density)

  /**
   * Where the pill should sit so that it hovers above a field whose top edge is at [fieldTop]
   * pixels, or null when the field is too close to the top of the screen for anything to fit above
   * it — in which case the caller leaves the overlay where the user put it.
   */
  fun pillTopFor(fieldTop: Int, density: Float): Int? {
    if (fieldTop <= MIN_FIELD_TOP_DP.toPx(density)) return null
    return (fieldTop - FIELD_GAP_DP.toPx(density)).coerceAtLeast(MIN_PILL_TOP_DP.toPx(density))
  }

  private fun Int.toPx(density: Float): Int = (this * density).roundToInt()
}
