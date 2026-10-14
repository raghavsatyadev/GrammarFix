package com.raghavsatyadev.grammarfix.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fields the assistant keeps its hands off (issue #33).
 *
 * With the assistant enabled, a Playground run used to be corrected twice: once by the Playground
 * and once by the overlay noticing the same field. The second run was recorded as an overlay
 * correction, which is how benchmark text reached the user's statistics despite being excluded in
 * both the engine and the recorder.
 */
class IsOurOwnFieldTest {

  private val ours = "com.raghavsatyadev.grammarfix"

  @Test
  fun ourOwnFieldsAreLeftAlone() {
    assertTrue(isOurOwnField(ours, ours))
  }

  /** A `CharSequence` that is not a `String`: an accessibility node hands back either. */
  @Test
  fun aPackageNameThatIsNotAStringIsStillRecognised() {
    assertTrue(isOurOwnField(StringBuilder(ours), ours))
  }

  @Test
  fun everyOtherAppIsAnalysedAsBefore() {
    assertFalse(isOurOwnField("com.google.android.apps.messaging", ours))
    assertFalse(isOurOwnField("com.android.chrome", ours))
  }

  /** An app whose id merely starts with ours is somebody else's app. */
  @Test
  fun aPackageThatOnlyLooksLikeOursIsAnalysed() {
    assertFalse(isOurOwnField("$ours.clone", ours))
  }

  /** A node that reports no package is not ours to skip; the user is owed their suggestions. */
  @Test
  fun aFieldWithNoPackageIsAnalysed() {
    assertFalse(isOurOwnField(null, ours))
  }
}
