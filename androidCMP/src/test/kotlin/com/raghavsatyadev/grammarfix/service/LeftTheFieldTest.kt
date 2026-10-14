package com.raghavsatyadev.grammarfix.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the overlay drops its fixes because the user is no longer in the field they were for. */
class LeftTheFieldTest {

  @Test
  fun closingTheNoteOrChangingScreenDropsTheFixes() {
    assertTrue(leftTheField(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, false, false))
    assertTrue(leftTheField(AccessibilityEvent.TYPE_VIEW_FOCUSED, false, false))
  }

  @Test
  fun aFocusedFieldKeepsThem() {
    assertFalse(leftTheField(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, true, false))
    assertFalse(leftTheField(AccessibilityEvent.TYPE_VIEW_FOCUSED, true, false))
  }

  /** The review sheet or rewrite panel taking focus is the user working with the fixes. */
  @Test
  fun grammarFixsOwnWindowsKeepThem() {
    assertFalse(leftTheField(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, false, true))
  }

  /** Text and selection events always come from a field, so they never mean it was left. */
  @Test
  fun textEventsNeverDropThem() {
    assertFalse(leftTheField(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, false, false))
    assertFalse(leftTheField(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, false, false))
  }
}
