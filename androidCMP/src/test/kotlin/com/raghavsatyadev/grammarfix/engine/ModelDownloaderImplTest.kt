package com.raghavsatyadev.grammarfix.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * These tests run against a mocked transport rather than the network.
 *
 * Without Koin started, `modelStorageDir()` falls back to the JVM temp directory, so the downloader
 * writes somewhere the test can inspect and clean up.
 */
class ModelDownloaderImplTest {

  private val modelFile = File(System.getProperty("java.io.tmpdir") ?: ".", "qwen3-1.7b-int4.gguf")

  @Before fun clean() = modelFile.delete().let {}

  @After fun cleanup() = modelFile.delete().let {}

  private fun downloaderReturning(
    body: ByteArray,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentLength: Long? = null,
  ): ModelDownloaderImpl {
    val engine = MockEngine { _ ->
      respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Length", (contentLength ?: body.size.toLong()).toString()),
      )
    }
    return ModelDownloaderImpl(HttpClient(engine))
  }

  private fun awaitStatus(
    downloader: ModelDownloaderImpl,
    vararg wanted: DownloadStatus,
  ): DownloadProgress = runBlocking {
    withTimeout(10_000L) {
      var state = downloader.progressState.value
      while (state.status !in wanted) {
        kotlinx.coroutines.delay(25)
        state = downloader.progressState.value
      }
      state
    }
  }

  @Test
  fun initialState_isNotDownloaded() {
    val downloader = downloaderReturning(ByteArray(0))
    val state = downloader.progressState.value
    assertEquals(DownloadStatus.NOT_DOWNLOADED, state.status)
    assertEquals(0L, state.downloadedBytes)
    assertNull(state.modelPath)
  }

  /**
   * Regression test for issue #1. A failing response used to fall through to a simulated download
   * that animated to 100%, wrote a 32-byte marker and reported COMPLETED.
   */
  @Test
  fun httpFailure_reportsErrorAndNeverClaimsSuccess() = runBlocking {
    val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
    val downloader = ModelDownloaderImpl(HttpClient(engine))

    downloader.startDownload("https://example.invalid/model.gguf")
    val state = awaitStatus(downloader, DownloadStatus.ERROR)

    assertEquals(DownloadStatus.ERROR, state.status)
    assertNotNull("the failure reason must reach the UI", state.errorMessage)
    assertTrue(state.errorMessage!!.contains("401"))
    assertNull(downloader.getModelPath())
    assertTrue("no model file may be left behind", !modelFile.exists() || modelFile.length() == 0L)
  }

  @Test
  fun successfulDownload_writesEveryByteAndCompletes() = runBlocking {
    val body = ByteArray(256 * 1024) { (it % 251).toByte() }
    val downloader = downloaderReturning(body)

    downloader.startDownload("https://example.invalid/model.gguf")
    val state = awaitStatus(downloader, DownloadStatus.COMPLETED, DownloadStatus.ERROR)

    assertEquals(DownloadStatus.COMPLETED, state.status)
    assertEquals(body.size.toLong(), state.downloadedBytes)
    assertEquals(100f, state.progressPercentage, 0.01f)
    assertEquals(body.size.toLong(), modelFile.length())
    assertEquals(downloader.getModelPath(), state.modelPath)
  }

  /** A truncated body must not be reported as a finished download. */
  @Test
  fun bodyShorterThanContentLength_reportsError() = runBlocking {
    val body = ByteArray(1024)
    val downloader = downloaderReturning(body, contentLength = 8192L)

    downloader.startDownload("https://example.invalid/model.gguf")
    val state = awaitStatus(downloader, DownloadStatus.ERROR, DownloadStatus.COMPLETED)

    assertEquals(DownloadStatus.ERROR, state.status)
    assertNotNull(state.errorMessage)
    assertNull(downloader.getModelPath())
  }

  /**
   * When a resume is requested but the server answers 200 with the whole body, appending would
   * corrupt the file. The download must restart from zero instead.
   */
  @Test
  fun serverIgnoresRange_restartsInsteadOfAppending() = runBlocking {
    val existing = ByteArray(4096) { 1 }
    modelFile.writeBytes(existing)

    val body = ByteArray(64 * 1024) { (it % 97).toByte() }
    val downloader = downloaderReturning(body, status = HttpStatusCode.OK)

    downloader.startDownload("https://example.invalid/model.gguf")
    val state = awaitStatus(downloader, DownloadStatus.COMPLETED, DownloadStatus.ERROR)

    assertEquals(DownloadStatus.COMPLETED, state.status)
    assertEquals(
      "file must equal the body, not body + stale partial",
      body.size.toLong(),
      modelFile.length(),
    )
  }

  /** A partial file must survive construction so a later start can resume it. */
  @Test
  fun existingPartialFile_isKeptAndReportedAsPaused() {
    modelFile.writeBytes(ByteArray(2048) { 7 })

    val downloader = downloaderReturning(ByteArray(0))
    val state = downloader.progressState.value

    assertEquals(DownloadStatus.PAUSED, state.status)
    assertEquals(2048L, state.downloadedBytes)
    assertTrue("partial download must not be deleted on startup", modelFile.exists())
  }

  @Test
  fun deleteModel_resetsDownloadStatusAndPath() {
    val downloader = downloaderReturning(ByteArray(1024))
    downloader.deleteModel()

    val state = downloader.progressState.value
    assertEquals(DownloadStatus.NOT_DOWNLOADED, state.status)
    assertNull(downloader.getModelPath())
    assertTrue(!modelFile.exists())
  }
}
