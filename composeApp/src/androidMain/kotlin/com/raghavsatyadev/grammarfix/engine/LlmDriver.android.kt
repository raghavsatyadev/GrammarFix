package com.raghavsatyadev.grammarfix.engine

import android.content.Context
import com.raghavsatyadev.grammarfix.llama.LlamaNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

actual fun createPlatformLlmDriver(): LlmDriver = LlamaCppDriver()

/** Qwen3 GGUF through llama.cpp, entirely on the device. */
class LlamaCppDriver : LlmDriver {

  override suspend fun load(modelPath: String) =
    withContext(Dispatchers.IO) {
      val size = getModelFileSize(modelPath)
      check(size > 0) { "Model not downloaded yet. Open the Model Downloader tab first." }
      check(size >= MIN_COMPLETE_MODEL_BYTES) {
        "Model download is incomplete. Resume it in the Model Downloader tab."
      }
      LlamaNative.init(KoinPlatform.getKoin().get<Context>())
      LlamaNative.load(modelPath)
    }

  override val isLoaded: Boolean
    get() = LlamaNative.isLoaded

  /**
   * Cancelling the caller stops the model, not just the wait for it (#31). The native call blocks a
   * thread and cannot see coroutine cancellation, so a watcher running beside it aborts this
   * request by id; otherwise a rewrite the user has already replaced with another chip would hold
   * the model for its full 15-25 s before the new one could start.
   */
  override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
    abortable { request ->
      // The budget comes from the tone: a rewrite that expands text, or one that reasons
      // before answering, runs past the 256 tokens a proofreading pass needs.
      val text = QwenPromptBuilder.buildPrompt(prompt)
      if (prompt.reusePrefix) {
        // The tone read only (#32): its fixed opening's KV state is reused across calls.
        LlamaNative.completeWithPrefix(
          QwenPromptBuilder.fixedOpening(prompt),
          text,
          prompt.maxOutputTokens,
          request,
        )
      } else {
        LlamaNative.complete(text, prompt.maxOutputTokens, request)
      }
    }

  override suspend fun scoreChoices(
    prompt: CorrectionPrompt,
    choices: List<String>,
    engineMode: EngineMode,
  ): List<ChoiceScore> = abortable { request ->
    val prefix = if (prompt.reusePrefix) QwenPromptBuilder.fixedOpening(prompt) else ""
    val raw =
      LlamaNative.scoreChoices(prefix, QwenPromptBuilder.buildPrompt(prompt), choices, request)
    // An aborted request comes back empty; its caller has been cancelled and never reads this.
    if (raw.size != choices.size * 2) return@abortable emptyList()
    List(choices.size) { ChoiceScore(raw[it * 2], raw[it * 2 + 1].toInt()) }
  }

  /**
   * Runs [block] on the IO dispatcher with a fresh request id, and aborts that native call when the
   * caller is cancelled (#31). The native call blocks a thread and cannot see coroutine
   * cancellation, so a watcher running beside it aborts it by id; otherwise a rewrite the user has
   * already replaced with another chip would hold the model for its full 15-25 s.
   */
  private suspend fun <T> abortable(block: (request: Long) -> T): T = coroutineScope {
    val request = LlamaNative.nextRequest()
    val watcher = launch {
      try {
        awaitCancellation()
      } finally {
        // Also reached when the answer arrives and the watcher is dismissed; aborting a request
        // that has already finished is a no-op by design.
        LlamaNative.abort(request)
      }
    }
    try {
      withContext(Dispatchers.IO) { block(request) }
    } finally {
      watcher.cancel()
    }
  }

  override suspend fun tokenWindow(engineMode: EngineMode): TokenWindow? =
    if (LlamaNative.isLoaded) LlamaTokenWindow else null

  override fun unload() = LlamaNative.unload()

  private companion object {
    /** Same floor the downloader uses to tell a finished model from a partial one. */
    const val MIN_COMPLETE_MODEL_BYTES = 1_000_000_000L
  }
}

/** Measures requests against the loaded GGUF model's own vocabulary and context size. */
private object LlamaTokenWindow : TokenWindow {

  override val contextTokens: Int
    get() = LlamaNative.contextTokens

  override suspend fun countTokens(text: String): Int =
    withContext(Dispatchers.IO) { LlamaNative.tokenCount(text) }

  /**
   * What the ChatML scaffolding costs: the system turn, the worked examples as prior turns, the
   * delimiters around the user's turn, and the empty `<think>` block in non-thinking mode.
   */
  override suspend fun overheadTokens(prompt: CorrectionPrompt): Int =
    countTokens(QwenPromptBuilder.buildPrompt(prompt.copy(userText = "")))
}
