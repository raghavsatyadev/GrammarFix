package com.raghavsatyadev.grammarfix.ui

import com.raghavsatyadev.grammarfix.engine.ToneCheck
import com.raghavsatyadev.grammarfix.engine.ToneReading
import com.raghavsatyadev.grammarfix.engine.ToneStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the overlay's tone line shows for the text on screen (#32). */
class ToneStripTest {

  private val text = "Send me the final numbers by 5 pm."

  @Test
  fun settingOffOffersTheButtonUntilThereIsAReading() {
    assertEquals(ToneStripState.Offer, toneStripState(null, text, autoDetect = false))
    assertEquals(
      ToneStripState.Offer,
      toneStripState(ToneCheck(text, ToneStatus.Idle), text, autoDetect = false),
    )
  }

  @Test
  fun settingOnShowsNothingWhileTheReadIsOnItsWay() {
    assertEquals(ToneStripState.Hidden, toneStripState(null, text, autoDetect = true))
  }

  @Test
  fun aReadingForThisTextIsShown() {
    val check = ToneCheck(text, ToneStatus.Ready(ToneReading(listOf("direct"), "A clear order.")))

    assertEquals(
      ToneStripState.Ready(listOf("direct"), "A clear order."),
      toneStripState(check, text, autoDetect = false),
    )
    assertEquals(
      ToneStripState.Detecting,
      toneStripState(ToneCheck(text, ToneStatus.Detecting), text, autoDetect = true),
    )
  }

  @Test
  fun aReadingForOtherTextSaysNothingAboutThisOne() {
    val stale = ToneCheck("Send me", ToneStatus.Ready(ToneReading(listOf("direct"), "")))

    assertEquals(ToneStripState.Offer, toneStripState(stale, text, autoDetect = false))
    assertEquals(ToneStripState.Hidden, toneStripState(stale, text, autoDetect = true))
  }

  @Test
  fun noTextNoStrip() {
    assertEquals(ToneStripState.Hidden, toneStripState(null, null, autoDetect = false))
    assertEquals(ToneStripState.Hidden, toneStripState(null, " ", autoDetect = false))
  }
}
