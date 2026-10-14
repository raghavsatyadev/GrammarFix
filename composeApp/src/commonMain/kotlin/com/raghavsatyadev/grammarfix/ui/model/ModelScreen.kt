package com.raghavsatyadev.grammarfix.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.raghavsatyadev.grammarfix.engine.DownloadProgress
import com.raghavsatyadev.grammarfix.engine.DownloadStatus
import com.raghavsatyadev.grammarfix.engine.GrammarAssistantViewModel
import com.raghavsatyadev.grammarfix.ui.components.SectionCard
import com.raghavsatyadev.grammarfix.ui.components.StatusPill
import com.raghavsatyadev.grammarfix.ui.components.StatusTone
import com.raghavsatyadev.grammarfix.ui.components.oneDecimal
import grammarfix.composeapp.generated.resources.Res
import grammarfix.composeapp.generated.resources.action_delete_model
import grammarfix.composeapp.generated.resources.action_download_model
import grammarfix.composeapp.generated.resources.action_pause
import grammarfix.composeapp.generated.resources.action_resume
import grammarfix.composeapp.generated.resources.download_progress
import grammarfix.composeapp.generated.resources.download_speed
import grammarfix.composeapp.generated.resources.download_status_downloading
import grammarfix.composeapp.generated.resources.download_status_error
import grammarfix.composeapp.generated.resources.download_status_not_downloaded
import grammarfix.composeapp.generated.resources.download_status_paused
import grammarfix.composeapp.generated.resources.download_status_ready
import grammarfix.composeapp.generated.resources.download_title
import grammarfix.composeapp.generated.resources.download_url_label
import grammarfix.composeapp.generated.resources.last_latency
import grammarfix.composeapp.generated.resources.thinking_off
import grammarfix.composeapp.generated.resources.thinking_on
import grammarfix.composeapp.generated.resources.thinking_title
import org.jetbrains.compose.resources.stringResource

/** Model tab: getting the offline model onto the device. */
@Composable
fun ModelScreen(
  viewModel: GrammarAssistantViewModel,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val modelUrl by viewModel.modelUrlInput.collectAsState()
  val downloadProgress by viewModel.downloadProgress.collectAsState()
  val isFastMode by viewModel.isNonThinkingMode.collectAsState()
  val lastResult by viewModel.correctionResult.collectAsState()

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    ModelDownloadCard(
      modelUrl = modelUrl,
      progress = downloadProgress,
      onUrlChanged = viewModel::onModelUrlChanged,
      onStart = viewModel::startModelDownload,
      onPause = viewModel::pauseModelDownload,
      onDelete = viewModel::deleteModel,
    )

    FastModeCard(
      enabled = isFastMode,
      lastLatencyMs = lastResult?.takeIf { it.isSuccess }?.latencyMs,
      onToggle = viewModel::toggleNonThinkingMode,
    )
  }
}

@Composable
private fun ModelDownloadCard(
  modelUrl: String,
  progress: DownloadProgress,
  onUrlChanged: (String) -> Unit,
  onStart: () -> Unit,
  onPause: () -> Unit,
  onDelete: () -> Unit,
) {
  val (statusText, tone) =
    when (progress.status) {
      DownloadStatus.NOT_DOWNLOADED ->
        stringResource(Res.string.download_status_not_downloaded) to StatusTone.NEUTRAL
      DownloadStatus.DOWNLOADING ->
        stringResource(Res.string.download_status_downloading) to StatusTone.ACTIVE
      DownloadStatus.PAUSED ->
        stringResource(Res.string.download_status_paused) to StatusTone.ACTIVE
      DownloadStatus.COMPLETED ->
        stringResource(Res.string.download_status_ready) to StatusTone.POSITIVE
      DownloadStatus.ERROR ->
        stringResource(Res.string.download_status_error) to StatusTone.NEGATIVE
    }

  SectionCard(
    title = stringResource(Res.string.download_title),
    icon = Icons.Rounded.CloudDownload,
    trailing = { StatusPill(statusText, tone) },
  ) {
    OutlinedTextField(
      value = modelUrl,
      onValueChange = onUrlChanged,
      label = { Text(stringResource(Res.string.download_url_label)) },
      singleLine = true,
      enabled = progress.status != DownloadStatus.DOWNLOADING,
      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )

    if (progress.status == DownloadStatus.DOWNLOADING || progress.status == DownloadStatus.PAUSED) {
      DownloadProgressRow(progress)
    }

    progress.errorMessage
      ?.takeIf { progress.status == DownloadStatus.ERROR }
      ?.let { message ->
        Text(
          message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      when (progress.status) {
        DownloadStatus.NOT_DOWNLOADED,
        DownloadStatus.ERROR ->
          Button(shapes = ButtonDefaults.shapes(), onClick = onStart) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Text(stringResource(Res.string.action_download_model), Modifier.padding(start = 8.dp))
          }
        DownloadStatus.DOWNLOADING ->
          OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = onPause) {
            Icon(Icons.Rounded.Pause, contentDescription = null)
            Text(stringResource(Res.string.action_pause), Modifier.padding(start = 8.dp))
          }
        DownloadStatus.PAUSED ->
          Button(shapes = ButtonDefaults.shapes(), onClick = onStart) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Text(stringResource(Res.string.action_resume), Modifier.padding(start = 8.dp))
          }
        DownloadStatus.COMPLETED ->
          OutlinedButton(
            shapes = ButtonDefaults.shapes(),
            onClick = onDelete,
            colors =
              ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
            Text(stringResource(Res.string.action_delete_model), Modifier.padding(start = 8.dp))
          }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadProgressRow(progress: DownloadProgress) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    LinearWavyProgressIndicator(
      progress = { progress.progressPercentage / 100f },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        text =
          stringResource(
            Res.string.download_progress,
            (progress.downloadedBytes / MB).toString(),
            (progress.totalBytes / MB).toString(),
            "${progress.progressPercentage.toDouble().oneDecimal()}%",
          ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (progress.status == DownloadStatus.DOWNLOADING) {
        Text(
          text =
            stringResource(Res.string.download_speed, (progress.speedKbps / 1024.0).oneDecimal()),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun FastModeCard(enabled: Boolean, lastLatencyMs: Long?, onToggle: () -> Unit) {
  SectionCard(
    title = stringResource(Res.string.thinking_title),
    icon = Icons.Rounded.Bolt,
    trailing = { Switch(checked = enabled, onCheckedChange = { onToggle() }) },
  ) {
    Text(
      text = stringResource(if (enabled) Res.string.thinking_on else Res.string.thinking_off),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    lastLatencyMs?.let { latency ->
      StatusPill(
        text = stringResource(Res.string.last_latency, latency.toString()),
        tone = StatusTone.POSITIVE,
      )
    }
  }
}

private const val MB = 1024 * 1024
