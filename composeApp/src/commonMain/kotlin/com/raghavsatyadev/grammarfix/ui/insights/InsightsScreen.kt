@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.raghavsatyadev.grammarfix.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.raghavsatyadev.grammarfix.ui.components.SectionCard
import com.raghavsatyadev.grammarfix.ui.components.StatusPill
import com.raghavsatyadev.grammarfix.ui.components.StatusTone
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.insights_activity_caption
import grammarfix.composeapp.generated.resources.insights_activity_description
import grammarfix.composeapp.generated.resources.insights_activity_title
import grammarfix.composeapp.generated.resources.insights_breakdown_title
import grammarfix.composeapp.generated.resources.insights_clean_caption
import grammarfix.composeapp.generated.resources.insights_clean_title
import grammarfix.composeapp.generated.resources.insights_corrections
import grammarfix.composeapp.generated.resources.insights_empty_body
import grammarfix.composeapp.generated.resources.insights_empty_title
import grammarfix.composeapp.generated.resources.insights_fix_deleted
import grammarfix.composeapp.generated.resources.insights_fix_inserted
import grammarfix.composeapp.generated.resources.insights_fix_replaced
import grammarfix.composeapp.generated.resources.insights_fixes_title
import grammarfix.composeapp.generated.resources.insights_no_previous
import grammarfix.composeapp.generated.resources.insights_points
import grammarfix.composeapp.generated.resources.insights_vs_previous
import grammarfix.composeapp.generated.resources.insights_words_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun InsightsScreen(
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
  viewModel: InsightsViewModel = koinViewModel(),
) {
  // The ViewModel outlives the tab, and the overlay writes rows while the user is in another app.
  // ON_RESUME is replayed when the tab is opened and fires again when the user comes back to the
  // app, so both ways of returning re-read the figures.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
  val state by viewModel.state.collectAsState()
  InsightsScreen(state = state, contentPadding = contentPadding, modifier = modifier)
}

/**
 * What the on-device model has done for its user, drawn from `correction_event` and from nothing
 * else.
 *
 * The figures are deliberately few. Every one of them is a count the database actually holds: there
 * is no "time saved", because the honest version of that number needs a reading speed we have never
 * measured, and an invented figure on a dashboard devalues the ones beside it.
 */
@Composable
fun InsightsScreen(
  state: InsightsUiState,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val insights = state.insights
  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Nothing is drawn until the first read lands: a tab that flashes "no data yet" on the way in
    // tells a returning user their history is gone.
    if (insights == null) return@Column

    if (insights.isEmpty) {
      EmptyState()
      return@Column
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      StatTile(
        title = stringResource(Res.string.insights_words_title),
        value = insights.wordsPolished.value.grouped(),
        metric = insights.wordsPolished,
        spark = insights.wordsSpark,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
      StatTile(
        title = stringResource(Res.string.insights_fixes_title),
        value = insights.fixesApplied.value.grouped(),
        metric = insights.fixesApplied,
        spark = insights.fixesSpark,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.weight(1f),
      )
    }

    insights.cleanPercent?.let { clean ->
      SectionCard(
        title = stringResource(Res.string.insights_clean_title),
        icon = Icons.Rounded.Spellcheck,
        trailing = { CleanDelta(clean, insights.previousCleanPercent) },
      ) {
        Text(
          text = "$clean%",
          style = MaterialTheme.typography.displaySmall,
          fontWeight = FontWeight.Bold,
        )
        Caption(stringResource(Res.string.insights_clean_caption))
      }
    }

    SectionCard(
      title = stringResource(Res.string.insights_activity_title),
      icon = Icons.Rounded.ShowChart,
    ) {
      // A chart a screen reader cannot read is decoration. The description is built here, where
      // the strings are, because a semantics block is not a composable scope.
      val description =
        stringResource(
          Res.string.insights_activity_description,
          insights.wordsPerDay.max().grouped(),
        )
      ActivityBars(
        values = insights.wordsPerDay,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.semantics { contentDescription = description },
      )
      Caption(stringResource(Res.string.insights_activity_caption))
    }

    SectionCard(
      title = stringResource(Res.string.insights_breakdown_title),
      icon = Icons.Rounded.AutoFixHigh,
    ) {
      FixBreakdown(insights)
    }

    Caption(stringResource(Res.string.insights_corrections, insights.corrections.grouped()))
  }
}

@Composable
private fun StatTile(
  title: String,
  value: String,
  metric: Metric,
  spark: List<Int>,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = modifier,
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
      )
      Delta(metric.changePercent)
      Sparkline(values = spark, color = color)
    }
  }
}

/**
 * The change against the window before, or why there isn't one.
 *
 * Deliberately [StatusTone.NEUTRAL] whichever way it points. A week with more words in it is not a
 * better week, and needing more fixes is not doing worse — colouring either green would be the
 * dashboard telling the user how to feel about a number that does not mean that. The one figure
 * here with a direction, cleanliness, is coloured by [CleanDelta].
 */
@Composable
private fun Delta(changePercent: Int?) {
  if (changePercent == null) {
    Caption(stringResource(Res.string.insights_no_previous))
    return
  }
  StatusPill(text = signed(changePercent) + "%", tone = StatusTone.NEUTRAL)
  Caption(stringResource(Res.string.insights_vs_previous))
}

/**
 * The same comparison for a percentage, in points rather than percent.
 *
 * 80% against 40% is not "+100% clean"; it is forty points, and saying it the other way would be
 * the sort of true-but-misleading number this screen is meant to avoid.
 */
@Composable
private fun CleanDelta(clean: Int, previous: Int?) {
  if (previous == null) return
  val difference = clean - previous
  StatusPill(
    text = stringResource(Res.string.insights_points, signed(difference)),
    tone = if (difference >= 0) StatusTone.POSITIVE else StatusTone.NEUTRAL,
  )
}

@Composable
private fun FixBreakdown(insights: Insights) {
  val scheme = MaterialTheme.colorScheme
  val parts =
    listOf(
      Triple(stringResource(Res.string.insights_fix_replaced), insights.replaced, scheme.primary),
      Triple(stringResource(Res.string.insights_fix_inserted), insights.inserted, scheme.tertiary),
      Triple(stringResource(Res.string.insights_fix_deleted), insights.deleted, scheme.secondary),
    )

  Row(
    Modifier.fillMaxWidth().height(14.dp).clip(CircleShape),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    // A kind with nothing in it takes no width at all; Compose rejects a zero weight outright.
    parts
      .filter { it.second > 0 }
      .forEach { (_, count, color) ->
        Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
      }
    if (insights.fixesByKind == 0) {
      Box(Modifier.weight(1f).fillMaxHeight().background(scheme.surfaceContainerHighest))
    }
  }

  parts.forEach { (label, count, color) ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).clip(CircleShape).background(color))
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f).padding(start = 10.dp),
      )
      Text(
        text = count.grouped(),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun EmptyState() {
  // The first-run hero: one expressive shape, a headline, and what fills this tab.
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier =
        Modifier.size(144.dp)
          .clip(MaterialShapes.Cookie12Sided.toShape())
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.Rounded.Insights,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(56.dp),
      )
    }
    Spacer(Modifier.height(24.dp))
    Text(
      text = stringResource(Res.string.insights_empty_title),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      text = stringResource(Res.string.insights_empty_body),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun Caption(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private fun signed(value: Int): String = if (value >= 0) "+$value" else "$value"
