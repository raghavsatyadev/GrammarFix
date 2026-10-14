package com.raghavsatyadev.grammarfix.engine

/** Renders a [CorrectionPrompt] as Qwen3 ChatML. */
object QwenPromptBuilder {
  /** What Qwen3's chat template emits for `enable_thinking=false`. */
  const val EMPTY_THINK = "<think>\n\n</think>\n\n"

  fun buildPrompt(userText: String, enforceNoThink: Boolean = true): String =
    buildPrompt(CorrectionPrompt.forText(userText, enforceNoThink))

  /**
   * Builds the ChatML prompt: system turn, the examples as real user/assistant turns, then the
   * user's text.
   *
   * Non-thinking mode follows Qwen3's own chat template with `enable_thinking=false`: the assistant
   * turn opens with an empty `<think></think>` block, so the model answers directly. A `/no_think`
   * line placed before the system turn, as this used to do, sits outside every turn and is not a
   * switch the model recognises.
   */
  fun buildPrompt(prompt: CorrectionPrompt): String = buildString {
    append(fixedOpening(prompt))
    append(prompt.userText).append("<|im_end|>\n")
    append("<|im_start|>assistant\n")
    if (prompt.enforceNoThink) append(EMPTY_THINK)
    append(prompt.answerPrefix)
  }

  /**
   * The part of [buildPrompt] that does not depend on the user's text: the system turn, the worked
   * examples and the opening of the user's turn. Always a prefix of [buildPrompt]'s result, which
   * is what lets the native side reuse its KV state for [CorrectionPrompt.reusePrefix] prompts.
   */
  fun fixedOpening(prompt: CorrectionPrompt): String = buildString {
    append("<|im_start|>system\n").append(prompt.systemPrompt).append("<|im_end|>\n")
    prompt.examples.forEach { (input, output) ->
      append("<|im_start|>user\n").append(input).append("<|im_end|>\n")
      append("<|im_start|>assistant\n").append(output).append("<|im_end|>\n")
    }
    append("<|im_start|>user\n")
  }
}
