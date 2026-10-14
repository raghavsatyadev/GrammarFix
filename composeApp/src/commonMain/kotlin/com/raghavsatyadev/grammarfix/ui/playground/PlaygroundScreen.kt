package com.raghavsatyadev.grammarfix.ui.playground

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.engine.BenchmarkSentences
import com.raghavsatyadev.grammarfix.engine.CorrectionResult
import com.raghavsatyadev.grammarfix.engine.DefaultTonePresets
import com.raghavsatyadev.grammarfix.engine.GrammarAssistantViewModel
import com.raghavsatyadev.grammarfix.engine.SuggestionKind
import com.raghavsatyadev.grammarfix.engine.TextSuggestion
import com.raghavsatyadev.grammarfix.engine.TonePreset
import com.raghavsatyadev.grammarfix.engine.kind
import com.raghavsatyadev.grammarfix.ui.components.SectionCard
import com.raghavsatyadev.grammarfix.ui.components.StatusPill
import com.raghavsatyadev.grammarfix.ui.components.StatusTone
import com.raghavsatyadev.grammarfix.ui.components.oneDecimal
import com.raghavsatyadev.grammarfix.ui.tone.toneName
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.action_accept_all
import grammarfix.composeapp.generated.resources.action_check_now
import grammarfix.composeapp.generated.resources.action_clear
import grammarfix.composeapp.generated.resources.action_use_rewrite
import grammarfix.composeapp.generated.resources.analyzing
import grammarfix.composeapp.generated.resources.benchmark_run
import grammarfix.composeapp.generated.resources.benchmark_running
import grammarfix.composeapp.generated.resources.editor_label
import grammarfix.composeapp.generated.resources.editor_placeholder
import grammarfix.composeapp.generated.resources.inference_failed
import grammarfix.composeapp.generated.resources.no_issues
import grammarfix.composeapp.generated.resources.overlay_accept
import grammarfix.composeapp.generated.resources.overlay_kind_add
import grammarfix.composeapp.generated.resources.overlay_kind_remove
import grammarfix.composeapp.generated.resources.overlay_kind_replace
import grammarfix.composeapp.generated.resources.perf_latency
import grammarfix.composeapp.generated.resources.perf_ms
import grammarfix.composeapp.generated.resources.perf_speed
import grammarfix.composeapp.generated.resources.perf_title
import grammarfix.composeapp.generated.resources.perf_tok_s
import grammarfix.composeapp.generated.resources.perf_words_in
import grammarfix.composeapp.generated.resources.perf_words_out
import grammarfix.composeapp.generated.resources.ram_title
import grammarfix.composeapp.generated.resources.ram_value
import grammarfix.composeapp.generated.resources.ram_window
import grammarfix.composeapp.generated.resources.sample_label
import grammarfix.composeapp.generated.resources.samples_title
import grammarfix.composeapp.generated.resources.suggestions_title
import grammarfix.composeapp.generated.resources.tone_picker_title
import grammarfix.composeapp.generated.resources.tone_result_title
import org.jetbrains.compose.resources.stringResource

/** Playground tab: type or load a sentence, see and apply the model's fixes, and its speed. */
@Composable
fun PlaygroundScreen(
  viewModel: GrammarAssistantViewModel,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val text by viewModel.inputText.collectAsState()
  val isAnalyzing by viewModel.isAnalyzing.collectAsState()
  val result by viewModel.correctionResult.collectAsState()
  val analysis by viewModel.analysis.collectAsState()
  val benchmarkProgress by viewModel.benchmarkProgress.collectAsState()
  val ramHistory by viewModel.ramUsageHistory.collectAsState()
  val ramMb by viewModel.currentRamUsageMb.collectAsState()
  val tones by viewModel.tonePresets.collectAsState()
  val selectedTone by viewModel.selectedTone.collectAsState()

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SamplesCard(
      benchmarkProgress = benchmarkProgress,
      onSample = viewModel::loadSample,
      onRunBenchmark = viewModel::runBenchmark,
    )

    TonePickerCard(
      tones = tones,
      selectedId = selectedTone.id,
      onSelect = viewModel::selectTone,
    )

    EditorCard(
      text = text,
      isAnalyzing = isAnalyzing,
      onTextChange = viewModel::onInputTextChanged,
      onCheckNow = viewModel::checkNow,
    )

    val current = result
    if (current != null && !current.isSuccess) {
      Text(
        text = current.errorMessage ?: stringResource(Res.string.inference_failed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 8.dp),
      )
    }

    // A rewrite tone restructures whole sentences, so a word-by-word suggestion list would be
    // noise: show the model's text as one block to take or leave. Polish keeps the fix list.
    val isRewrite = selectedTone.id != DefaultTonePresets.POLISH_ID
    val suggestions = analysis?.suggestions
    AnimatedVisibility(visible = current?.isSuccess == true && suggestions != null && !isRewrite) {
      SuggestionsCard(
        suggestions = suggestions.orEmpty(),
        onAccept = viewModel::acceptSuggestion,
        onAcceptAll = viewModel::acceptAllSuggestions,
      )
    }

    AnimatedVisibility(visible = current?.isSuccess == true && isRewrite) {
      ToneResultCard(
        toneLabel = toneName(selectedTone),
        rewritten = current?.correctedText.orEmpty(),
        canApply = current?.correctedText != text,
        onApply = viewModel::applyRewrite,
      )
    }

    if (current != null && current.isSuccess) {
      PerformanceCard(result = current, ramHistory = ramHistory, ramMb = ramMb)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TonePickerCard(
  tones: List<TonePreset>,
  selectedId: String,
  onSelect: (String) -> Unit,
) {
  SectionCard(title = stringResource(Res.string.tone_picker_title), icon = Icons.Rounded.Palette) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      tones.forEach { tone ->
        val selected = tone.id == selectedId
        FilterChip(
          selected = selected,
          onClick = { onSelect(tone.id) },
          label = { Text(toneName(tone), style = MaterialTheme.typography.labelMedium) },
          leadingIcon =
            if (selected) {
              { Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(18.dp)) }
            } else null,
        )
      }
    }
  }
}

/** The model's whole rewrite, to take or leave — the counterpart of the Polish fix list. */
@Composable
private fun ToneResultCard(
  toneLabel: String,
  rewritten: String,
  canApply: Boolean,
  onApply: () -> Unit,
) {
  SectionCard(
    title = stringResource(Res.string.tone_result_title),
    icon = Icons.Rounded.AutoFixHigh,
    trailing = { StatusPill(toneLabel, StatusTone.ACTIVE) },
  ) {
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = rewritten,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
      )
    }
    FilledTonalButton(
      shapes = ButtonDefaults.shapes(),
      onClick = onApply,
      enabled = canApply,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(Res.string.action_use_rewrite))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SamplesCard(
  benchmarkProgress: Int?,
  onSample: (String) -> Unit,
  onRunBenchmark: () -> Unit,
) {
  val total = BenchmarkSentences.all.size
  SectionCard(
    title = stringResource(Res.string.samples_title),
    icon = Icons.Rounded.AutoFixHigh,
  ) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      BenchmarkSentences.all.forEachIndexed { index, sentence ->
        SuggestionChip(
          onClick = { onSample(sentence) },
          enabled = benchmarkProgress == null,
          label = {
            Text(
              stringResource(Res.string.sample_label, (index + 1).toString()),
              style = MaterialTheme.typography.labelSmall,
            )
          },
        )
      }
    }
    FilledTonalButton(
      shapes = ButtonDefaults.shapes(),
      onClick = onRunBenchmark,
      enabled = benchmarkProgress == null,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (benchmarkProgress != null) {
        LoadingIndicator(Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          stringResource(
            Res.string.benchmark_running,
            benchmarkProgress.toString(),
            total.toString(),
          )
        )
      } else {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.benchmark_run, total.toString()))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorCard(
  text: String,
  isAnalyzing: Boolean,
  onTextChange: (String) -> Unit,
  onCheckNow: () -> Unit,
) {
  SectionCard(
    title = stringResource(Res.string.editor_label),
    icon = Icons.Rounded.EditNote,
    trailing = {
      if (isAnalyzing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          LoadingIndicator(Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text(
            stringResource(Res.string.analyzing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    },
  ) {
    OutlinedTextField(
      value = text,
      onValueChange = onTextChange,
      placeholder = { Text(stringResource(Res.string.editor_placeholder)) },
      minLines = 4,
      shape = MaterialTheme.shapes.large,
      trailingIcon = {
        if (text.isNotEmpty()) {
          IconButton(shapes = IconButtonDefaults.shapes(), onClick = { onTextChange("") }) {
            Icon(Icons.Rounded.Clear, contentDescription = stringResource(Res.string.action_clear))
          }
        }
      },
      modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
    )
    Button(
      shapes = ButtonDefaults.shapes(),
      onClick = onCheckNow,
      enabled = text.isNotBlank() && !isAnalyzing,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Rounded.Check, contentDescription = null)
      Spacer(Modifier.width(8.dp))
      Text(stringResource(Res.string.action_check_now))
    }
  }
}

@Composable
private fun SuggestionsCard(
  suggestions: List<TextSuggestion>,
  onAccept: (Int) -> Unit,
  onAcceptAll: () -> Unit,
) {
  SectionCard(
    title = stringResource(Res.string.suggestions_title),
    icon = Icons.Rounded.AutoFixHigh,
    trailing = {
      if (suggestions.isEmpty()) {
        StatusPill(stringResource(Res.string.no_issues), StatusTone.POSITIVE)
      } else {
        StatusPill(suggestions.size.toString(), StatusTone.NEGATIVE)
      }
    },
  ) {
    suggestions.forEachIndexed { index, suggestion ->
      SuggestionRow(suggestion, onAccept = { onAccept(index) })
    }
    if (suggestions.size > 1) {
      FilledTonalButton(
        shapes = ButtonDefaults.shapes(),
        onClick = onAcceptAll,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(Res.string.action_accept_all))
      }
    }
  }
}

@Composable
private fun SuggestionRow(suggestion: TextSuggestion, onAccept: () -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          text =
            when (suggestion.kind) {
              SuggestionKind.ADD -> stringResource(Res.string.overlay_kind_add)
              SuggestionKind.REMOVE -> stringResource(Res.string.overlay_kind_remove)
              SuggestionKind.REPLACE ->
                stringResource(Res.string.overlay_kind_replace, suggestion.original)
            },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (suggestion.kind != SuggestionKind.ADD) {
            Text(
              text = suggestion.original,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
              textDecoration = TextDecoration.LineThrough,
            )
          }
          if (suggestion.kind == SuggestionKind.REPLACE) {
            Icon(
              Icons.AutoMirrored.Rounded.ArrowForward,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 6.dp).size(14.dp),
            )
          }
          if (suggestion.kind != SuggestionKind.REMOVE) {
            Text(
              text = suggestion.replacement,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
      FilledTonalIconButton(shapes = IconButtonDefaults.shapes(), onClick = onAccept) {
        Icon(Icons.Rounded.Check, contentDescription = stringResource(Res.string.overlay_accept))
      }
    }
  }
}

@Composable
private fun PerformanceCard(result: CorrectionResult, ramHistory: List<Float>, ramMb: Float) {
  SectionCard(
    title = stringResource(Res.string.perf_title),
    icon = Icons.Rounded.Speed,
    trailing = {
      StatusPill(
        text = stringResource(Res.string.perf_ms, result.latencyMs.toString()),
        tone = StatusTone.ACTIVE,
      )
    },
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Metric(
        stringResource(Res.string.perf_latency),
        stringResource(Res.string.perf_ms, result.latencyMs.toString()),
      )
      Metric(stringResource(Res.string.perf_words_in), result.inputWordCount.toString())
      Metric(stringResource(Res.string.perf_words_out), result.outputWordCount.toString())
      Metric(
        stringResource(Res.string.perf_speed),
        stringResource(Res.string.perf_tok_s, result.tokensPerSecond.toDouble().oneDecimal()),
      )
    }
    RamChart(history = ramHistory, currentMb = ramMb)
  }
}

@Composable
private fun Metric(label: String, value: String) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

/** Sparkline of device RAM over the last [ramHistory] samples (one per second). */
@Composable
private fun RamChart(history: List<Float>, currentMb: Float) {
  val points = if (history.size < 2) listOf(currentMb, currentMb) else history
  val minRam = points.min() * 0.9f
  val maxRam = maxOf(points.max() * 1.1f, minRam + 10f)
  val line = MaterialTheme.colorScheme.primary
  val dotCenter = MaterialTheme.colorScheme.surfaceContainer

  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(Res.string.ram_title),
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = stringResource(Res.string.ram_value, (currentMb / 1024.0).oneDecimal()),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val stepX = size.width / (points.size - 1)
        val range = maxRam - minRam
        fun yOf(value: Float): Float {
          val normalized = 1f - ((value - minRam) / range).coerceIn(0f, 1f)
          return normalized * (size.height - 8.dp.toPx()) + 4.dp.toPx()
        }

        val path = Path()
        points.forEachIndexed { index, value ->
          val x = index * stepX
          val y = yOf(value)
          if (index == 0) {
            path.moveTo(x, y)
          } else {
            val prevX = (index - 1) * stepX
            val midX = (prevX + x) / 2f
            path.cubicTo(midX, yOf(points[index - 1]), midX, y, x, y)
          }
        }
        val fill =
          Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
          }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.3f), Color.Transparent)))
        drawPath(
          path,
          color = line,
          style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val last = Offset(size.width, yOf(points.last()))
        drawCircle(line, radius = 4.dp.toPx(), center = last)
        drawCircle(dotCenter, radius = 2.dp.toPx(), center = last)
      }
      Text(
        text = stringResource(Res.string.ram_window, points.size.toString()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
