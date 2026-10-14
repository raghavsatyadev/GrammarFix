package com.raghavsatyadev.grammarfix.engine

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ModelDownloaderImpl(
  private val client: HttpClient = HttpClient {
    followRedirects = true
    // A ~1.2 GB transfer must not be killed by a default request deadline, but a
    // stalled socket still has to fail rather than hang the UI in DOWNLOADING.
    install(HttpTimeout) {
      requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
      socketTimeoutMillis = SOCKET_TIMEOUT_MS
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
    }
  }
) : ModelDownloader {

  private companion object {
    /** Below this a file on disk is a resumable partial, not a usable model. */
    const val MIN_VALID_MODEL_BYTES = 1_000_000_000L

    /** Fallback total, used only for progress display before the server reports one. */
    const val FALLBACK_TOTAL_BYTES = QWEN3_MODEL_BYTES

    const val READ_CHUNK_BYTES = 64 * 1024
    /** Coalesce reads so a full download costs ~300 file opens instead of ~19,500. */
    const val FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024

    const val SOCKET_TIMEOUT_MS = 60_000L
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val SPEED_SAMPLE_SECONDS = 0.5f

    /**
     * Minimum gap between progress emissions.
     *
     * At ~30 MB/s a 64 KB read loop emits ~500 states per second. Every one of those recomposes the
     * download card, which saturates the main thread and leaves the tab showing a stale frame for
     * the whole transfer. ~8 updates/s is smooth to the eye and leaves the UI thread free.
     */
    const val PROGRESS_EMIT_INTERVAL_MS = 125L
  }

  private val scope = CoroutineScope(Dispatchers.Default)
  private var downloadJob: Job? = null

  // Resolved lazily: on Android this reads the app context, which is not ready
  // while the dependency graph is still being constructed.
  private val modelFilePath: String by lazy { defaultModelPath() }

  private val _progressState = MutableStateFlow(DownloadProgress())
  override val progressState: StateFlow<DownloadProgress> = _progressState.asStateFlow()

  private var currentPath: String? = null

  init {
    checkExistingModel()
  }

  private fun checkExistingModel() {
    val size = getModelFileSize(modelFilePath)
    when {
      size >= MIN_VALID_MODEL_BYTES -> {
        currentPath = modelFilePath
        _progressState.value =
          DownloadProgress(
            status = DownloadStatus.COMPLETED,
            downloadedBytes = size,
            totalBytes = size,
            progressPercentage = 100f,
            modelPath = currentPath,
          )
      }

      // A partial file is kept so the next start resumes instead of refetching
      // everything. Deleting it here would make resume impossible across restarts.
      size > 0 ->
        _progressState.value =
          DownloadProgress(
            status = DownloadStatus.PAUSED,
            downloadedBytes = size,
            totalBytes = FALLBACK_TOTAL_BYTES,
            progressPercentage =
              (size.toFloat() / FALLBACK_TOTAL_BYTES.toFloat() * 100f).coerceIn(0f, 99f),
          )
    }
  }

  override suspend fun startDownload(url: String) {
    if (_progressState.value.status == DownloadStatus.DOWNLOADING) return

    downloadJob?.cancel()

    val targetUrl = url.ifBlank { defaultModelUrl }

    _progressState.value =
      _progressState.value.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null)

    downloadJob = scope.launch { runDownload(targetUrl) }
  }

  private suspend fun runDownload(targetUrl: String) {
    val resumeFrom = getModelFileSize(modelFilePath)

    try {
      client
        .prepareGet(targetUrl) { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
        .execute { response ->
          if (!response.status.isSuccess()) {
            failWith(
              "HTTP ${response.status.value} ${response.status.description}".trim() +
                explainStatus(response.status)
            )
            return@execute
          }

          // A server may ignore Range and return the whole body with 200. Appending
          // that to an existing partial would silently corrupt the file, so start over.
          val isPartial = response.status == HttpStatusCode.PartialContent
          val startAt =
            if (resumeFrom > 0 && !isPartial) {
              truncateModelFile(modelFilePath)
              0L
            } else {
              resumeFrom
            }

          val bodyLength = response.contentLength() ?: 0L
          val totalBytes =
            if (bodyLength > 0) bodyLength + startAt else _progressState.value.totalBytes

          streamToDisk(response.bodyAsChannel(), startAt, totalBytes)
        }
    } catch (e: CancellationException) {
      // Pause and cancel both cancel the job; neither is a download failure.
      throw e
    } catch (e: Exception) {
      failWith(e.message ?: "Failed to download model")
    }
  }

  private suspend fun streamToDisk(channel: ByteReadChannel, startAt: Long, totalBytes: Long) {
    val readBuffer = ByteArray(READ_CHUNK_BYTES)
    val flushBuffer = ByteArray(FLUSH_THRESHOLD_BYTES)
    var pending = 0

    var downloaded = startAt
    val timeSource = kotlin.time.TimeSource.Monotonic
    var lastMark = timeSource.markNow()
    var lastBytes = startAt
    var lastEmit = timeSource.markNow()
    var speedKbps = _progressState.value.speedKbps

    fun flush() {
      if (pending > 0) {
        appendModelFileChunk(modelFilePath, flushBuffer, pending)
        pending = 0
      }
    }

    try {
      while (!channel.isClosedForRead) {
        if (_progressState.value.status != DownloadStatus.DOWNLOADING) break

        val read = channel.readAvailable(readBuffer, 0, readBuffer.size)
        if (read <= 0) break

        if (pending + read > flushBuffer.size) flush()
        readBuffer.copyInto(flushBuffer, pending, 0, read)
        pending += read
        downloaded += read

        val elapsedSec = lastMark.elapsedNow().inWholeMilliseconds / 1000f
        if (elapsedSec >= SPEED_SAMPLE_SECONDS) {
          speedKbps = ((downloaded - lastBytes).toFloat() / elapsedSec) / 1024f
          lastMark = timeSource.markNow()
          lastBytes = downloaded
        }

        // Rate-limited so a fast transfer cannot swamp the UI with recompositions.
        if (lastEmit.elapsedNow().inWholeMilliseconds >= PROGRESS_EMIT_INTERVAL_MS) {
          lastEmit = timeSource.markNow()
          publishProgress(downloaded, totalBytes, speedKbps)
        }
      }
    } finally {
      // Runs on cancellation too, so a pause keeps the bytes already received and the
      // card shows the real byte count rather than the last throttled sample.
      flush()
      publishProgress(downloaded, totalBytes, speedKbps = 0f)
    }

    // Only a stream that ran to the end AND delivered every byte is a real download.
    // Anything else used to be reported as success while leaving a truncated file.
    if (downloaded >= totalBytes && totalBytes > 0) {
      currentPath = modelFilePath
      _progressState.value =
        _progressState.value.copy(
          status = DownloadStatus.COMPLETED,
          progressPercentage = 100f,
          downloadedBytes = downloaded,
          totalBytes = totalBytes,
          modelPath = currentPath,
          errorMessage = null,
        )
    } else if (_progressState.value.status == DownloadStatus.DOWNLOADING) {
      failWith("Connection ended early: got $downloaded of $totalBytes bytes. Tap to resume.")
    }
  }

  /** Keeps the current status; only the transfer figures change. */
  private fun publishProgress(downloaded: Long, totalBytes: Long, speedKbps: Float) {
    _progressState.value =
      _progressState.value.copy(
        downloadedBytes = downloaded,
        totalBytes = totalBytes,
        progressPercentage =
          if (totalBytes > 0)
            (downloaded.toFloat() / totalBytes.toFloat() * 100f).coerceIn(0f, 100f)
          else 0f,
        speedKbps = speedKbps,
      )
  }

  private fun explainStatus(status: HttpStatusCode): String =
    when (status.value) {
      401,
      403 -> " - the model repository is private, gated or does not exist. Check the URL."
      404 -> " - no file at that URL. Check the repository and file name."
      416 -> " - the cached partial file no longer matches the server. Delete it and retry."
      else -> ""
    }

  private fun failWith(message: String) {
    _progressState.value =
      _progressState.value.copy(
        status = DownloadStatus.ERROR,
        errorMessage = message,
        speedKbps = 0f,
      )
  }

  override fun pauseDownload() {
    // Set the status first: the streaming loop checks it, and it stops the cancelled
    // job's completion path from overwriting PAUSED.
    _progressState.value = _progressState.value.copy(status = DownloadStatus.PAUSED, speedKbps = 0f)
    downloadJob?.cancel()
  }

  override fun cancelDownload() {
    _progressState.value = _progressState.value.copy(status = DownloadStatus.NOT_DOWNLOADED)
    downloadJob?.cancel()
    removeModelFile(modelFilePath)
    _progressState.value =
      DownloadProgress(
        status = DownloadStatus.NOT_DOWNLOADED,
        downloadedBytes = 0L,
        progressPercentage = 0f,
      )
  }

  override fun deleteModel() {
    cancelDownload()
    currentPath = null
  }

  override fun getModelPath(): String? = currentPath
}
