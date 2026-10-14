package com.raghavsatyadev.grammarfix

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.GrammarDiffAnalyzer
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import com.raghavsatyadev.grammarfix.engine.ToneCheck
import com.raghavsatyadev.grammarfix.engine.TonePreset
import com.raghavsatyadev.grammarfix.engine.ToneReading
import com.raghavsatyadev.grammarfix.engine.ToneStatus
import com.raghavsatyadev.grammarfix.library.support.di.appModule
import com.raghavsatyadev.grammarfix.library.support.theme.AppTheme
import com.raghavsatyadev.grammarfix.ui.GrammarFixApp
import com.raghavsatyadev.grammarfix.ui.RewriteResult
import com.raghavsatyadev.grammarfix.ui.SuggestionOverlay
import com.raghavsatyadev.grammarfix.ui.SuggestionOverlayActions
import com.raghavsatyadev.grammarfix.ui.SuggestionOverlayState
import com.raghavsatyadev.grammarfix.ui.insights.Insights
import com.raghavsatyadev.grammarfix.ui.insights.InsightsScreen
import com.raghavsatyadev.grammarfix.ui.insights.InsightsUiState
import com.raghavsatyadev.grammarfix.ui.insights.Metric
import com.raghavsatyadev.grammarfix.ui.model.ModelScreen
import com.raghavsatyadev.grammarfix.ui.playground.PlaygroundScreen
import com.raghavsatyadev.grammarfix.ui.setup.AssistantSetup
import com.raghavsatyadev.grammarfix.ui.setup.SetupScreen
import com.raghavsatyadev.grammarfix.ui.tone.ToneScreen
import com.raghavsatyadev.grammarfix.ui.tone.ToneUiState
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel

/**
 * Screenshot tests for the entire application UI and its main screens.
 *
 * The debug build: four tabs, opening on the Playground.
 */
@Suppress("DEPRECATION")
@PreviewTest
@Preview(showBackground = true)
@Composable
fun FullApp_DefaultTab_ScreenshotTest() {
  KoinApplication(
    application = {
      modules(appModule)
    }
  ) {
    AppTheme {
      GrammarFixApp(
        settings = onboardedSettings(),
        showDeveloperTools = true,
        assistantSetup =
          AssistantSetup(
            hasOverlayPermission = true,
            isAccessibilityEnabled = true,
            isAssistantRunning = false,
            onOpenOverlaySettings = {},
            onOpenAccessibilitySettings = {},
            onStartAssistant = {},
            onStopAssistant = {},
          ),
      )
    }
  }
}

/**
 * What actually ships: developer tools off, so no Playground tab and the app opens on Model. This
 * is the tab bar users see, so it gets a golden of its own.
 */
@Suppress("DEPRECATION")
@PreviewTest
@Preview(showBackground = true)
@Composable
fun FullApp_ReleaseTabs_ScreenshotTest() {
  KoinApplication(
    application = {
      modules(appModule)
    }
  ) {
    AppTheme {
      GrammarFixApp(
        settings = onboardedSettings(),
        showDeveloperTools = false,
        assistantSetup =
          AssistantSetup(
            hasOverlayPermission = true,
            isAccessibilityEnabled = true,
            isAssistantRunning = false,
            onOpenOverlaySettings = {},
            onOpenAccessibilitySettings = {},
            onStartAssistant = {},
            onStopAssistant = {},
          ),
      )
    }
  }
}

@Suppress("DEPRECATION")
@PreviewTest
@Preview(showBackground = true)
@Composable
fun ModelScreen_ScreenshotTest() {
  KoinApplication(
    application = {
      modules(appModule)
    }
  ) {
    AppTheme {
      ModelScreen(
        viewModel = koinViewModel(),
        contentPadding = PaddingValues(16.dp),
      )
    }
  }
}

@Suppress("DEPRECATION")
@PreviewTest
@Preview(showBackground = true)
@Composable
fun PlaygroundScreen_ScreenshotTest() {
  KoinApplication(
    application = {
      modules(appModule)
    }
  ) {
    AppTheme {
      PlaygroundScreen(
        viewModel = koinViewModel(),
        contentPadding = PaddingValues(16.dp),
      )
    }
  }
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun SetupScreen_Running_ScreenshotTest() {
  AppTheme {
    SetupScreen(
      setup =
        AssistantSetup(
          hasOverlayPermission = true,
          isAccessibilityEnabled = true,
          isAssistantRunning = true,
          onOpenOverlaySettings = {},
          onOpenAccessibilitySettings = {},
          onStartAssistant = {},
          onStopAssistant = {},
        ),
      isModelReady = true,
      onOpenModelTab = {},
      contentPadding = PaddingValues(16.dp),
    )
  }
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun SetupScreen_MissingPermissions_ScreenshotTest() {
  AppTheme {
    SetupScreen(
      setup =
        AssistantSetup(
          hasOverlayPermission = false,
          isAccessibilityEnabled = false,
          isAssistantRunning = false,
          onOpenOverlaySettings = {},
          onOpenAccessibilitySettings = {},
          onStartAssistant = {},
          onStopAssistant = {},
        ),
      isModelReady = false,
      onOpenModelTab = {},
      contentPadding = PaddingValues(16.dp),
    )
  }
}

/** The tones tab with one tone of the user's own, so both sections render. */
@PreviewTest
@Preview(showBackground = true)
@Composable
fun ToneScreen_ScreenshotTest() {
  AppTheme {
    ToneScreen(
      state =
        ToneUiState(
          presets =
            DefaultTonePresets.all +
              TonePreset(
                id = "custom-1",
                name = "Casual",
                systemPrompt = "Rewrite the text like a message to a friend.",
                usesThinking = true,
              ),
          selectedId = DefaultTonePresets.SHORTEN_ID,
        ),
      onEvent = {},
      contentPadding = PaddingValues(16.dp),
    )
  }
}

/** The tones tab with tone detection on and a reading to show (#32). */
@PreviewTest
@Preview(showBackground = true, heightDp = 900)
@Composable
fun ToneScreen_DetectionOn_ScreenshotTest() {
  AppTheme {
    ToneScreen(
      state =
        ToneUiState(
          presets = DefaultTonePresets.all,
          selectedId = DefaultTonePresets.POLISH_ID,
          detectWhileTyping = true,
          lastReading = sampleReading,
        ),
      onEvent = {},
      contentPadding = PaddingValues(16.dp),
    )
  }
}

// --- The floating overlay (issue #30) ---------------------------------------------------------

private val overlayActions =
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
    onSelectTone = {},
    onSendInstruction = {},
    onInstructionFocus = {},
    onUseRewrite = {},
    onCloseRewrite = {},
  )

private const val OVERLAY_TEXT =
  "I meant matching the pop up overlay style of Grammarly completely while keeping our theme intact."

private val overlaySuggestions =
  listOf(
    TextSuggestion(original = "pop up", replacement = "pop-up", startIndex = 21, endIndex = 27),
    TextSuggestion(
      original = "completely",
      replacement = "exactly",
      startIndex = 56,
      endIndex = 66,
    ),
  )

/**
 * Nothing to suggest: the bubble a tap turns into the rewrite panel, or the app when there is no
 * text to rewrite (#23, #31).
 */
@PreviewTest
@Preview(showBackground = true)
@Composable
fun OverlayIdleBubble_ScreenshotTest() {
  SuggestionOverlay(SuggestionOverlayState.Idle, overlayActions)
}

/** Collapsed: how many fixes are waiting, and nothing that can be applied by accident. */
@PreviewTest
@Preview(showBackground = true)
@Composable
fun OverlayPill_ScreenshotTest() {
  SuggestionOverlay(
    SuggestionOverlayState.Review(OVERLAY_TEXT, overlaySuggestions),
    overlayActions,
  )
}

/** The sheet: one fix, read inside the sentence it changes. */
@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayReviewSheet_ScreenshotTest() {
  SuggestionOverlay(
    SuggestionOverlayState.Review(OVERLAY_TEXT, overlaySuggestions, open = true),
    overlayActions,
  )
}

/** A fix has been applied and the text is being checked again — most of what the user sees. */
@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayWorkingSheet_ScreenshotTest() {
  SuggestionOverlay(SuggestionOverlayState.Working, overlayActions)
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayAllClearSheet_ScreenshotTest() {
  SuggestionOverlay(SuggestionOverlayState.AllClear, overlayActions)
}

// --- The rewrite panel (issue #31) -------------------------------------------------------------

private const val REWRITE_ORIGINAL = "hey can u send me that file asap? we need it for the review"

private val rewriteTones =
  DefaultTonePresets.all +
    TonePreset(id = "custom-1", name = "Pirate", systemPrompt = "Talk like a pirate.")

private fun rewritePanel(
  selectedId: String,
  rewritten: String? = null,
): SuggestionOverlayState.Rewrite =
  SuggestionOverlayState.Rewrite(
    original = REWRITE_ORIGINAL,
    tones = rewriteTones,
    selectedId = selectedId,
    result =
      if (rewritten == null) RewriteResult.Loading
      else
        RewriteResult.Ready(
          rewritten,
          GrammarDiffAnalyzer.computeDiff(REWRITE_ORIGINAL, rewritten),
        ),
  )

/**
 * Most of what the user sees: a rewrite takes 15-25 s on device, so the skeleton holds its place.
 */
private val rewriteLoading = rewritePanel(DefaultTonePresets.POLISH_ID)

/** The answer, with every changed span tinted inline. */
private val rewriteResult =
  rewritePanel(
    DefaultTonePresets.POLISH_ID,
    "Hey, can you send me that file as soon as possible? We need it for the review.",
  )

/** A different chip picked: the selected one takes the accent outline, the title follows it. */
private val rewriteChipSelected =
  rewritePanel(
    DefaultTonePresets.SHORTEN_ID,
    "Please send the file soon; we need it for the review.",
  )

// --- Tone detection (issue #32) ----------------------------------------------------------------

private val sampleReading =
  ToneReading(listOf("casual", "urgent"), "A relaxed but pressing request for a file.")

/** Setting on: the reading sits under the sheet's title once the model has read the text. */
@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayReviewSheet_ToneReady_ScreenshotTest() {
  SuggestionOverlay(
    SuggestionOverlayState.Review(OVERLAY_TEXT, overlaySuggestions, open = true),
    overlayActions,
    tone = ToneCheck(OVERLAY_TEXT, ToneStatus.Ready(sampleReading)),
    autoDetectTone = true,
  )
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayReviewSheet_ToneDetecting_ScreenshotTest() {
  SuggestionOverlay(
    SuggestionOverlayState.Review(OVERLAY_TEXT, overlaySuggestions, open = true),
    overlayActions,
    tone = ToneCheck(OVERLAY_TEXT, ToneStatus.Detecting),
    autoDetectTone = true,
  )
}

/** The reading above the chip row, so the user sees how the text reads before picking a tone. */
@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayRewriteResult_ToneReady_ScreenshotTest() {
  SuggestionOverlay(
    rewriteResult,
    overlayActions,
    tone = ToneCheck(REWRITE_ORIGINAL, ToneStatus.Ready(sampleReading)),
    autoDetectTone = false,
  )
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayAllClearSheet_ToneReady_ScreenshotTest() {
  SuggestionOverlay(
    SuggestionOverlayState.AllClear,
    overlayActions,
    tone = ToneCheck(REWRITE_ORIGINAL, ToneStatus.Ready(sampleReading)),
  )
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayRewriteLoading_Dark_ScreenshotTest() {
  SuggestionOverlay(rewriteLoading, overlayActions)
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayRewriteLoading_Light_ScreenshotTest() {
  SuggestionOverlay(rewriteLoading, overlayActions, darkTheme = false)
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayRewriteResult_Dark_ScreenshotTest() {
  SuggestionOverlay(rewriteResult, overlayActions)
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayRewriteResult_Light_ScreenshotTest() {
  SuggestionOverlay(rewriteResult, overlayActions, darkTheme = false)
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayRewriteChipSelected_Dark_ScreenshotTest() {
  SuggestionOverlay(rewriteChipSelected, overlayActions)
}

@PreviewTest
@Preview(showBackground = true, widthDp = 411)
@Composable
fun OverlayRewriteChipSelected_Light_ScreenshotTest() {
  SuggestionOverlay(rewriteChipSelected, overlayActions, darkTheme = false)
}

/**
 * A month of invented but fixed figures for the Insights tab.
 *
 * Fixed, because goldens drawn from real rows would flap every time the device was used: the chart
 * would be a different shape on every run and the diff would say nothing about the code.
 */
private val sampleInsights =
  Insights(
    wordsPolished = Metric(value = 1284, previous = 990),
    fixesApplied = Metric(value = 96, previous = 120),
    cleanPercent = 92,
    previousCleanPercent = 88,
    // Varied enough to show the chart's shape, and the same on every run.
    wordsPerDay = List(30) { (it * 37 % 13) * 14 },
    fixesPerDay = List(30) { (it * 17 % 7) * 3 },
    replaced = 61,
    inserted = 22,
    deleted = 13,
    corrections = 42,
  )

/** The Insights tab with a month behind it (#20). */
@PreviewTest
@Preview(showBackground = true)
@Composable
fun InsightsScreen_ScreenshotTest() {
  AppTheme {
    InsightsScreen(
      state = InsightsUiState(isLoading = false, insights = sampleInsights),
      contentPadding = PaddingValues(16.dp),
    )
  }
}

/**
 * A fresh install opens this tab with nothing in the database, and "no data yet" has to look
 * deliberate rather than broken.
 */
@PreviewTest
@Preview(showBackground = true)
@Composable
fun InsightsScreen_Empty_ScreenshotTest() {
  AppTheme {
    InsightsScreen(
      state =
        InsightsUiState(
          isLoading = false,
          insights =
            sampleInsights.copy(
              corrections = 0,
              wordsPolished = Metric(0, 0),
              fixesApplied = Metric(0, 0),
              cleanPercent = null,
              previousCleanPercent = null,
              wordsPerDay = List(30) { 0 },
              fixesPerDay = List(30) { 0 },
              replaced = 0,
              inserted = 0,
              deleted = 0,
            ),
        ),
      contentPadding = PaddingValues(16.dp),
    )
  }
}
