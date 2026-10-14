package com.raghavsatyadev.grammarfix.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.GrammarDiffAnalyzer
import com.raghavsatyadev.grammarfix.engine.TonePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rewrite panel (#31) as the user sees it: chips, loading, and a size that never jumps. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the panel needs no DI, and the app's own one starts Koin, which survives
// between tests in a shared JVM and fails the second one to ask for it.
@Config(sdk = [34], application = android.app.Application::class)
class RewritePanelTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val selected = mutableListOf<String>()
  private val instructions = mutableListOf<String>()
  private var used = 0

  private val actions =
    SuggestionOverlayActions(
      onAccept = {},
      onDismiss = {},
      onAcceptAll = {},
      onOpenSheet = {},
      onCloseSheet = {},
      onDragStart = {},
      onDrag = {},
      onDragEnd = {},
      onDragCancel = {},
      onBubbleTap = {},
      onOpenRewrite = {},
      onSelectTone = { selected += it },
      onSendInstruction = { instructions += it },
      onInstructionFocus = {},
      onUseRewrite = { used++ },
      onCloseRewrite = {},
    )

  private val original = "hey can u send me that file asap? thanks"

  private val custom =
    TonePreset(id = "custom-1", name = "Pirate", systemPrompt = "Talk like a pirate.")

  private fun panel(result: RewriteResult, selectedId: String = DefaultTonePresets.FORMAL_ID) =
    SuggestionOverlayState.Rewrite(
      original = original,
      tones = DefaultTonePresets.all + custom,
      selectedId = selectedId,
      result = result,
    )

  private fun ready(text: String) =
    RewriteResult.Ready(text, GrammarDiffAnalyzer.computeDiff(original, text))

  @Test
  fun listsBuiltInAndCustomTonesAndSelectsTheStoredOne() {
    composeTestRule.setContent { SuggestionOverlay(panel(RewriteResult.Loading), actions) }

    listOf("Polish", "Rephrase", "Shorten", "Friendly", "Formal", "Elaborate", "Pirate").forEach {
      composeTestRule.onNodeWithText(it).performScrollTo()
    }
    composeTestRule.onNodeWithText("Formal").assertIsSelected()
    composeTestRule.onNodeWithText("Polish").assertIsNotSelected()

    composeTestRule.onNodeWithText("Pirate").performScrollTo().performClick()
    assertEquals(listOf("custom-1"), selected)
  }

  @Test
  fun useThisVersionIsDisabledUntilThereIsAResult() {
    var state by mutableStateOf(panel(RewriteResult.Loading))
    composeTestRule.setContent { SuggestionOverlay(state, actions) }

    composeTestRule.onNodeWithText("Use this version").assertIsNotEnabled()

    state = panel(ready("Could you please send me that file at your earliest convenience?"))
    composeTestRule.onNodeWithText("Use this version").assertIsEnabled().performClick()
    assertEquals(1, used)
  }

  @Test
  fun aRewriteThatChangedNothingCannotBeUsed() {
    composeTestRule.setContent { SuggestionOverlay(panel(ready(original)), actions) }

    composeTestRule.onNodeWithText("Use this version").assertIsNotEnabled()
  }

  /**
   * The acceptance item: switching chips swaps the text inside the panel, never its size. Loading,
   * a short answer, a long one and a failure all measure the same.
   */
  @Test
  fun thePanelKeepsItsHeightThroughLoadingResultAndFailure() {
    var state by mutableStateOf(panel(RewriteResult.Loading))
    composeTestRule.setContent { SuggestionOverlay(state, actions) }

    fun height(): Dp {
      composeTestRule.waitForIdle()
      return composeTestRule.onRoot().getUnclippedBoundsInRoot().height
    }

    val loading = height()
    state = panel(ready("Send the file?"), selectedId = DefaultTonePresets.SHORTEN_ID)
    val short = height()
    state = panel(ready(List(40) { "Could you please send me that file today." }.joinToString(" ")))
    val long = height()
    state = panel(RewriteResult.Failed("Model is not loaded"))
    val failed = height()

    assertEquals(loading, short)
    assertEquals(loading, long)
    assertEquals(loading, failed)
  }

  @Test
  fun anInstructionIsSentFromItsField() {
    composeTestRule.setContent { SuggestionOverlay(panel(ready("Hi.")), actions) }

    composeTestRule.onNode(hasSetTextAction()).performTextInput("Make it sound professional")
    composeTestRule.onNode(hasSetTextAction()).performImeAction()

    assertEquals(listOf("Make it sound professional"), instructions)
  }

  @Test
  fun anInstructionRewriteSelectsNoChipAndTitlesItselfWithTheInstruction() {
    composeTestRule.setContent {
      SuggestionOverlay(
        panel(ready("Hello."))
          .copy(
            selectedId = DefaultTonePresets.INSTRUCTION_ID,
            instruction = "Make it sound professional",
          ),
        actions,
      )
    }

    composeTestRule.onNodeWithText("Formal").assertIsNotSelected()
    composeTestRule.onNodeWithText("“Make it sound professional”").assertExists()
  }

  // --- rewriteChanges -------------------------------------------------------------------------

  @Test
  fun changedWordsAreFoundInTheRewritesOwnText() {
    val before = "he go to school"
    val after = "He goes to school"
    val ranges = rewriteChanges(after, GrammarDiffAnalyzer.computeDiff(before, after))

    // "He" differs only in case, which the analyser counts as unchanged.
    assertEquals(listOf("goes"), ranges.map { after.substring(it) })
  }

  @Test
  fun neighbouringChangesMergeIntoOneRunAndDeletionsLeaveNothing() {
    val before = "I really think that we should go now"
    val after = "We must leave now"
    val ranges = rewriteChanges(after, GrammarDiffAnalyzer.computeDiff(before, after))

    // "we" only changed case, which the analyser counts as unchanged.
    assertEquals(listOf("must leave"), ranges.map { after.substring(it) })
  }

  @Test
  fun lineBreaksInTheRewriteAreKept() {
    val before = "hi team\nthe build is broken"
    val after = "Hi team,\nthe build is failing"
    val ranges = rewriteChanges(after, GrammarDiffAnalyzer.computeDiff(before, after))

    assertEquals(listOf("team,", "failing"), ranges.map { after.substring(it) })
    assertTrue(ranges.all { !after.substring(it).contains('\n') })
  }

  @Test
  fun anUnchangedRewriteHasNothingToTint() {
    val text = "Nothing to change here."
    assertTrue(rewriteChanges(text, GrammarDiffAnalyzer.computeDiff(text, text)).isEmpty())
  }
}
