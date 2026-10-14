@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.data.AppSettingsRepository
import com.raghavsatyadev.grammarfix.engine.DownloadStatus
import com.raghavsatyadev.grammarfix.engine.GrammarAssistantViewModel
import com.raghavsatyadev.grammarfix.library.support.theme.AppTheme
import com.raghavsatyadev.grammarfix.ui.insights.InsightsScreen
import com.raghavsatyadev.grammarfix.ui.model.ModelScreen
import com.raghavsatyadev.grammarfix.ui.onboarding.OnboardingScreen
import com.raghavsatyadev.grammarfix.ui.playground.PlaygroundScreen
import com.raghavsatyadev.grammarfix.ui.setup.AssistantSetup
import com.raghavsatyadev.grammarfix.ui.setup.SetupScreen
import com.raghavsatyadev.grammarfix.ui.tone.ToneScreen
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.app_name
import grammarfix.composeapp.generated.resources.app_subtitle
import grammarfix.composeapp.generated.resources.tab_insights
import grammarfix.composeapp.generated.resources.tab_model
import grammarfix.composeapp.generated.resources.tab_playground
import grammarfix.composeapp.generated.resources.tab_setup
import grammarfix.composeapp.generated.resources.tab_tones
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

enum class AppTab(
  val title: StringResource,
  val selectedIcon: ImageVector,
  val icon: ImageVector,
) {
  MODEL(Res.string.tab_model, Icons.Rounded.Memory, Icons.Outlined.Memory),
  INSIGHTS(Res.string.tab_insights, Icons.Rounded.Insights, Icons.Outlined.Insights),
  PLAYGROUND(Res.string.tab_playground, Icons.Rounded.EditNote, Icons.Outlined.EditNote),
  TONES(Res.string.tab_tones, Icons.Rounded.Palette, Icons.Outlined.Palette),
  SETUP(Res.string.tab_setup, Icons.Rounded.Tune, Icons.Outlined.Tune),
}

/** Tabs that exist only as developer tools and must never reach a release build. */
private val DeveloperOnlyTabs = setOf(AppTab.PLAYGROUND)

/**
 * The tabs a build should show.
 *
 * Both inputs are decided by the host and passed in — [hasAssistantSetup] because the floating
 * assistant is Android-only, [showDeveloperTools] because `BuildConfig.DEBUG` is an Android
 * constant that `commonMain` must not read.
 */
fun visibleAppTabs(hasAssistantSetup: Boolean, showDeveloperTools: Boolean): List<AppTab> =
  AppTab.entries.filter {
    (it != AppTab.SETUP || hasAssistantSetup) && (showDeveloperTools || it !in DeveloperOnlyTabs)
  }

/** The tab the app opens on. Always present in [visibleAppTabs]. */
fun defaultAppTab(showDeveloperTools: Boolean): AppTab =
  if (showDeveloperTools) AppTab.PLAYGROUND else AppTab.MODEL

/**
 * The whole app, shared by Android and iOS.
 *
 * [assistantSetup] is the Android floating assistant's state; without it (iOS) the Setup tab is
 * left out. [showDeveloperTools] gates the Playground, a developer tool: the Android host passes
 * `BuildConfig.DEBUG`, so a release build never shows it.
 */
@Composable
fun GrammarFixApp(
  assistantSetup: AssistantSetup? = null,
  showDeveloperTools: Boolean = false,
  modifier: Modifier = Modifier,
  viewModel: GrammarAssistantViewModel = koinViewModel(),
  settings: AppSettingsRepository = koinInject(),
) {
  AppTheme {
    val tabs = visibleAppTabs(assistantSetup != null, showDeveloperTools)
    var selected by rememberSaveable { mutableStateOf(defaultAppTab(showDeveloperTools)) }
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isModelReady = downloadProgress.status == DownloadStatus.COMPLETED
    val onboardingDone by settings.onboardingDone.collectAsState()
    val scope = rememberCoroutineScope()

    when (onboardingDone) {
      // Still reading the stored flag: a blank frame, not a flash of the wrong screen.
      null -> {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return@AppTheme
      }
      false -> {
        OnboardingScreen(
          hasAssistantSetup = assistantSetup != null,
          onDone = {
            // The model is the first thing the app needs, so a new user lands where it is fetched.
            if (!isModelReady) selected = AppTab.MODEL
            scope.launch { settings.setOnboardingDone() }
          },
          modifier = modifier,
        )
        return@AppTheme
      }
      true -> Unit
    }

    // A restored selection can name a tab this build does not have; fall back rather than render
    // an empty body with nothing highlighted in the bar.
    val current = if (selected in tabs) selected else defaultAppTab(showDeveloperTools)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
      modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
      containerColor = MaterialTheme.colorScheme.surface,
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = stringResource(Res.string.app_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          scrollBehavior = scrollBehavior,
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
      },
      bottomBar = {
        Box(
          Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 12.dp),
          contentAlignment = Alignment.Center,
        ) {
          FloatingTabBar(tabs = tabs, selected = current, onSelect = { selected = it })
        }
      },
    ) { innerPadding ->
      val layoutDirection = LocalLayoutDirection.current
      val contentPadding =
        PaddingValues(
          start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
          end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
          top = innerPadding.calculateTopPadding() + 8.dp,
          bottom = innerPadding.calculateBottomPadding() + 16.dp,
        )
      val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
      val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
      AnimatedContent(
        targetState = current,
        transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
        label = "tab",
      ) { tab ->
        when (tab) {
          AppTab.MODEL -> ModelScreen(viewModel, contentPadding)
          AppTab.INSIGHTS -> InsightsScreen(contentPadding)
          AppTab.PLAYGROUND -> PlaygroundScreen(viewModel, contentPadding)
          AppTab.TONES -> ToneScreen(contentPadding)
          AppTab.SETUP ->
            assistantSetup?.let {
              SetupScreen(
                setup = it,
                isModelReady = isModelReady,
                onOpenModelTab = { selected = AppTab.MODEL },
                contentPadding = contentPadding,
              )
            }
        }
      }
    }
  }
}

/**
 * M3 Expressive floating tab bar: a pill that floats above the content; the selected tab expands
 * into a tonal pill with its label, the others show only their icon.
 */
@Composable
private fun FloatingTabBar(tabs: List<AppTab>, selected: AppTab, onSelect: (AppTab) -> Unit) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    shadowElevation = 6.dp,
  ) {
    Row(
      modifier = Modifier.padding(6.dp).selectableGroup(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      tabs.forEach { tab ->
        val isSelected = tab == selected
        val container by
          animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
          )
        val content by
          animateColorAsState(
            targetValue =
              if (isSelected) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
          )
        val title = stringResource(tab.title)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.clip(CircleShape)
              .background(container)
              .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
              .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
          Icon(
            imageVector = if (isSelected) tab.selectedIcon else tab.icon,
            contentDescription = title,
            tint = content,
          )
          AnimatedVisibility(
            visible = isSelected,
            enter = expandHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
          ) {
            Text(
              text = title,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = content,
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
      }
    }
  }
}
