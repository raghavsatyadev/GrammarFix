package com.raghavsatyadev.grammarfix.engine

private const val NOT_AVAILABLE = "On-device inference is not available on this platform yet"

actual fun createPlatformLlmDriver(): LlmDriver = UnsupportedLlmDriver

/** No on-device model runtime is built for this platform yet; say so instead of guessing. */
private object UnsupportedLlmDriver : LlmDriver {
  override val isLoaded: Boolean
    get() = false

  override suspend fun load(modelPath: String) = throw UnsupportedOperationException(NOT_AVAILABLE)

  override suspend fun generate(prompt: CorrectionPrompt, engineMode: EngineMode): String =
    throw UnsupportedOperationException(NOT_AVAILABLE)
}
