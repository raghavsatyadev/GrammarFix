@file:OptIn(
  ExperimentalMaterial3Api::class,
  ExperimentalMaterial3ExpressiveApi::class,
  KoinExperimentalAPI::class,
)

package com.raghavsatyadev.grammarfix.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.raghavsatyadev.grammarfix.data.AppSettingsRepository
import com.raghavsatyadev.grammarfix.engine.DownloadStatus
import com.raghavsatyadev.grammarfix.engine.GrammarAssistantViewModel
import com.raghavsatyadev.grammarfix.library.support.theme.AppTheme
import com.raghavsatyadev.grammarfix.ui.onboarding.OnboardingScreen
import com.raghavsatyadev.grammarfix.ui.setup.AssistantSetup
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.app_name
import grammarfix.composeapp.generated.resources.app_subtitle
import grammarfix.composeapp.generated.resources.ic_edit_note
import grammarfix.composeapp.generated.resources.ic_edit_note_filled
import grammarfix.composeapp.generated.resources.ic_insights
import grammarfix.composeapp.generated.resources.ic_insights_filled
import grammarfix.composeapp.generated.resources.ic_memory
import grammarfix.composeapp.generated.resources.ic_memory_filled
import grammarfix.composeapp.generated.resources.ic_palette
import grammarfix.composeapp.generated.resources.ic_palette_filled
import grammarfix.composeapp.generated.resources.ic_tune
import grammarfix.composeapp.generated.resources.ic_tune_filled
import grammarfix.composeapp.generated.resources.tab_insights
import grammarfix.composeapp.generated.resources.tab_model
import grammarfix.composeapp.generated.resources.tab_playground
import grammarfix.composeapp.generated.resources.tab_setup
import grammarfix.composeapp.generated.resources.tab_tones
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.koinInject
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI

enum class AppTab(
  val title: StringResource,
  val selectedIcon: DrawableResource,
  val icon: DrawableResource,
) {
  MODEL(Res.string.tab_model, Res.drawable.ic_memory_filled, Res.drawable.ic_memory),
  INSIGHTS(Res.string.tab_insights, Res.drawable.ic_insights_filled, Res.drawable.ic_insights),
  PLAYGROUND(
    Res.string.tab_playground,
    Res.drawable.ic_edit_note_filled,
    Res.drawable.ic_edit_note,
  ),
  TONES(Res.string.tab_tones, Res.drawable.ic_palette_filled, Res.drawable.ic_palette),
  SETUP(Res.string.tab_setup, Res.drawable.ic_tune_filled, Res.drawable.ic_tune),
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
    val startTab = defaultAppTab(showDeveloperTools)
    val backStack = rememberNavBackStack(TabNavConfig, TabRoute(startTab))
    // The start tab sits at the bottom; any other tab goes on top of it, so Back returns to it.
    val select: (AppTab) -> Unit = { tab ->
      val target = TabRoute(tab)
      if (backStack.last() != target) {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (backStack.first() != target) backStack.add(target)
      }
    }
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val isModelReady = downloadProgress.status == DownloadStatus.COMPLETED
    val onboardingDone by settings.onboardingDone.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val entries = koinEntryProvider<NavKey>()

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
            if (!isModelReady) select(AppTab.MODEL)
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
    val selected = (backStack.last() as TabRoute).tab
    val current = if (selected in tabs) selected else startTab
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Wide windows (tablets, unfolded foldables, desktop) get a side rail; phones the floating bar.
    val windowWidth =
      with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = windowWidth >= WideLayoutMinWidth

    Row(modifier.fillMaxSize()) {
      if (wide) TabRail(tabs = tabs, selected = current, onSelect = select)
      Scaffold(
        modifier =
          Modifier.weight(1f).fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
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
          if (!wide) {
            Box(
              Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp),
              contentAlignment = Alignment.Center,
            ) {
              FloatingTabBar(tabs = tabs, selected = current, onSelect = select)
            }
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
        val transition = fadeIn(enter) togetherWith fadeOut(exit)
        val host =
          TabHost(
            viewModel = viewModel,
            contentPadding = contentPadding,
            tabs = tabs,
            startTab = startTab,
            assistantSetup = assistantSetup,
            isModelReady = isModelReady,
            select = select,
          )
        CompositionLocalProvider(LocalTabHost provides host) {
          NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryDecorators =
              listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
              ),
            transitionSpec = { transition },
            popTransitionSpec = { transition },
            predictivePopTransitionSpec = { transition },
            entryProvider = entries,
          )
        }
      }
    }
  }
}

/** Window width from which the tabs move to a side rail (Material's medium width class). */
private val WideLayoutMinWidth = 600.dp

/** The tabs as an M3 Expressive side rail, for wide windows. */
@Composable
private fun TabRail(tabs: List<AppTab>, selected: AppTab, onSelect: (AppTab) -> Unit) {
  WideNavigationRail {
    tabs.forEach { tab ->
      val isSelected = tab == selected
      WideNavigationRailItem(
        selected = isSelected,
        onClick = { onSelect(tab) },
        icon = {
          Icon(
            vectorResource(if (isSelected) tab.selectedIcon else tab.icon),
            contentDescription = null,
          )
        },
        label = { Text(stringResource(tab.title)) },
        railExpanded = false,
      )
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
            imageVector = vectorResource(if (isSelected) tab.selectedIcon else tab.icon),
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
