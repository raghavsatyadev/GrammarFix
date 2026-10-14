package com.raghavsatyadev.grammarfix.service

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay lands in the same place on every screen (issue #38).
 *
 * The two densities are the phones the bug was found on: a Pixel 10 Pro at 420 dpi and an S25 Ultra
 * at 600 dpi. With raw pixel constants the bubble sat 9.1 dp from the edge on one and 6.4 dp on the
 * other, so the assertions here are about dp, not about pixels.
 */
class OverlayPlacementTest {

  private val pixel = 2.625f
  private val s25 = 3.75f

  private fun Int.dpAt(density: Float) = this / density

  private fun assertSameDp(message: String, a: Float, b: Float) {
    assertTrue("$message: ${a}dp vs ${b}dp", abs(a - b) < 1f)
  }

  @Test
  fun theBubbleRestsTheSameDistanceFromTheCornerOnBothPhones() {
    val (px, py) = OverlayPlacement.restingPosition(pixel)
    val (sx, sy) = OverlayPlacement.restingPosition(s25)

    assertSameDp("left margin", px.dpAt(pixel), sx.dpAt(s25))
    assertSameDp("top margin", py.dpAt(pixel), sy.dpAt(s25))
  }

  /** The denser screen gets more pixels for the same physical distance, which was the whole bug. */
  @Test
  fun aDenserScreenGetsProportionallyMorePixels() {
    val (px, py) = OverlayPlacement.restingPosition(pixel)
    val (sx, sy) = OverlayPlacement.restingPosition(s25)

    assertTrue("x: $px -> $sx", sx > px)
    assertTrue("y: $py -> $sy", sy > py)
    assertEquals(s25 / pixel, sy.toFloat() / py.toFloat(), 0.05f)
  }

  @Test
  fun thePillKeepsTheSameGapAboveTheFieldOnBothPhones() {
    // The same field, a third of the way down each screen.
    val pixelField = (800 * pixel).toInt()
    val s25Field = (800 * s25).toInt()

    val pixelGap = pixelField - OverlayPlacement.pillTopFor(pixelField, pixel)!!
    val s25Gap = s25Field - OverlayPlacement.pillTopFor(s25Field, s25)!!

    assertSameDp("gap above the field", pixelGap.dpAt(pixel), s25Gap.dpAt(s25))
  }

  /** A field at the very top of the screen has no room above it; the overlay stays put. */
  @Test
  fun aFieldWithNoRoomAboveItLeavesTheOverlayWhereItIs() {
    assertNull(OverlayPlacement.pillTopFor(0, pixel))
    assertNull(OverlayPlacement.pillTopFor((20 * pixel).toInt(), pixel))
    assertNull(OverlayPlacement.pillTopFor((20 * s25).toInt(), s25))

    // Just past the threshold, there is room again.
    assertNotNull(OverlayPlacement.pillTopFor((200 * pixel).toInt(), pixel))
  }

  /** Whatever the field, the pill never climbs into the status bar. */
  @Test
  fun thePillNeverRidesAboveItsFloor() {
    for (density in listOf(pixel, s25)) {
      for (fieldTop in 100..2000 step 25) {
        val top = OverlayPlacement.pillTopFor(fieldTop, density) ?: continue
        assertTrue("$fieldTop at $density gave $top", top.dpAt(density) >= 39f)
        assertTrue("the pill is above the field", top < fieldTop)
      }
    }
  }
}
