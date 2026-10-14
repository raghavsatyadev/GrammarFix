package com.raghavsatyadev.grammarfix.llama

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin binding over `grammarfix_llama.cpp`. One model per process; the native side serialises
 * calls.
 *
 * Every [complete] is independent of the previous one: the native code clears the KV cache,
 * restarts positions at 0 and samples greedily with a fresh sampler, so the same prompt always
 * yields the same text no matter what ran before. [completeWithPrefix] is the one exception, and
 * only the tone read uses it.
 */
object LlamaNative {

  @Volatile private var initialised = false

  /** Loads the native libraries and registers the CPU backend best suited to this device. */
  fun init(context: Context) {
    if (initialised) return
    synchronized(this) {
      if (initialised) return
      try {
        System.loadLibrary("grammarfix_llama")
      } catch (e: UnsatisfiedLinkError) {
        // An Error, not an Exception: rethrow as something callers already handle.
        throw IllegalStateException("On-device inference is only built for 64-bit ARM devices", e)
      }
      nativeInit(context.applicationInfo.nativeLibraryDir)
      initialised = true
    }
  }

  /** Loads [modelPath]; a no-op if it is already the loaded model. Throws on failure. */
  fun load(
    modelPath: String,
    contextSize: Int = DEFAULT_CONTEXT_TOKENS,
    threads: Int = defaultThreads(),
  ) {
    nativeLoad(modelPath, contextSize, threads)
  }

  /**
   * Tokens the context window holds. Qwen3 itself reaches much further, but the KV cache costs ~112
   * KB per token on this model (28 layers x 8 KV heads x 128 dims), so 2048 tokens is already ~230
   * MB on top of 1.2 GB of weights. Text that does not fit is split, not given a wider window.
   */
  const val DEFAULT_CONTEXT_TOKENS = 2048

  val isLoaded: Boolean
    get() = initialised && nativeIsLoaded()

  /**
   * Completes a fully formatted prompt and returns only the generated text. Throws on failure.
   *
   * [request] names this completion for [abort]; 0 means it cannot be aborted. Take ids from
   * [nextRequest] so no two completions share one.
   */
  fun complete(prompt: String, maxTokens: Int = 256, request: Long = 0L): String =
    nativeComplete(prompt, maxTokens, request)

  /**
   * Completes [prompt], which must begin with [prefix], reusing the KV state of [prefix] saved by
   * an earlier call with the same prefix (#32). Only the part after [prefix] is prefilled.
   *
   * For the tone read only. Decoding a cached opening and a fresh tail in separate batches changes
   * float accumulation order, so the answer can differ from [complete]'s by a token; proofreading
   * and rewrites must keep calling [complete], which never reads this cache. The first call with a
   * new prefix builds the cache and then answers exactly as a later call would, so the result never
   * depends on whether the cache was warm. The cache is dropped whenever the model is unloaded or
   * reloaded.
   */
  fun completeWithPrefix(
    prefix: String,
    prompt: String,
    maxTokens: Int,
    request: Long = 0L,
  ): String = nativeCompleteWithPrefix(prefix, prompt, maxTokens, request)

  /**
   * Scores each of [choices] as the continuation of [prompt] instead of generating one, and returns
   * `[logProb0, tokens0, logProb1, tokens1, …]`: the summed natural-log probability of each
   * choice's tokens and how many there were. Empty when [request] was aborted.
   *
   * [prompt] is decoded once; each choice is decoded after it and then rolled back. A non-empty
   * [prefix] (which [prompt] must begin with) reuses the same saved KV state [completeWithPrefix]
   * does, with the same caveat on accumulation order. Choices are tokenised on their own, so each
   * should carry the leading space that would follow [prompt] (`" formal"`).
   */
  fun scoreChoices(
    prefix: String,
    prompt: String,
    choices: List<String>,
    request: Long = 0L,
  ): FloatArray = nativeScoreChoices(prefix, prompt, choices.toTypedArray(), request)

  /**
   * Frees the saved openings [completeWithPrefix] and [scoreChoices] keep; the next call rebuilds.
   */
  fun dropPrefixCache() {
    if (initialised) nativeDropPrefixCache()
  }

  /**
   * Stops completion [request] at its next decode step; it returns what it had generated so far.
   * Does nothing to any other completion, so an abort that arrives after its request has finished
   * is harmless. Safe to call from any thread while [complete] runs.
   */
  fun abort(request: Long) {
    if (initialised && request != 0L) nativeAbort(request)
  }

  private val requests = AtomicLong(0L)

  /** A fresh, non-zero id for [complete]. */
  fun nextRequest(): Long = requests.incrementAndGet()

  /** Tokens the loaded context holds, or 0 when no model is loaded. */
  val contextTokens: Int
    get() = if (initialised) nativeContextTokens() else 0

  /**
   * What [text] costs in the loaded model's vocabulary. Requires a loaded model, and is the only
   * honest way to size a request: a chars-per-token estimate that runs low is rejected by
   * [complete] rather than trimmed.
   */
  fun tokenCount(text: String): Int = nativeTokenCount(text)

  fun unload() {
    if (initialised) nativeUnload()
  }

  /** Leave cores for the UI thread; phones throttle hard when every core is pegged. */
  private fun defaultThreads(): Int =
    (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

  private external fun nativeInit(backendDir: String)

  private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int)

  private external fun nativeIsLoaded(): Boolean

  private external fun nativeComplete(prompt: String, maxTokens: Int, request: Long): String

  private external fun nativeCompleteWithPrefix(
    prefix: String,
    prompt: String,
    maxTokens: Int,
    request: Long,
  ): String

  private external fun nativeScoreChoices(
    prefix: String,
    prompt: String,
    choices: Array<String>,
    request: Long,
  ): FloatArray

  private external fun nativeDropPrefixCache()

  private external fun nativeAbort(request: Long)

  private external fun nativeContextTokens(): Int

  private external fun nativeTokenCount(text: String): Int

  private external fun nativeUnload()
}
