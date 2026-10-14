package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.flow.StateFlow

/** Size of [ModelDownloader.defaultModelUrl] (Qwen3-1.7B-Q4_K_M.gguf, ~1.2 GB). */
const val QWEN3_MODEL_BYTES = 1_282_439_264L

enum class DownloadStatus {
  NOT_DOWNLOADED,
  DOWNLOADING,
  PAUSED,
  COMPLETED,
  ERROR,
}

data class DownloadProgress(
  val status: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
  val downloadedBytes: Long = 0L,
  val totalBytes: Long = QWEN3_MODEL_BYTES,
  val progressPercentage: Float = 0f,
  val speedKbps: Float = 0f,
  val modelPath: String? = null,
  val errorMessage: String? = null,
)

interface ModelDownloader {
  val progressState: StateFlow<DownloadProgress>

  /**
   * Q4_K_M build of Qwen3 1.7B from `ggml-org`, the llama.cpp organisation.
   *
   * The previous URL pointed at `Qwen/Qwen3-1.7B-Instruct-GGUF`, a repository that does not exist;
   * HuggingFace answers 401 for unknown repositories, so every download failed immediately.
   * `Qwen/Qwen3-1.7B-GGUF` is real but only publishes Q8_0.
   */
  val defaultModelUrl: String
    get() = "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"

  suspend fun startDownload(url: String = defaultModelUrl)

  fun pauseDownload()

  fun cancelDownload()

  fun deleteModel()

  fun getModelPath(): String?
}
