package com.raghavsatyadev.grammarfix.engine

/**
 * The constrained short-output prompt the issue #32 spike measures: labels only, a budget in the
 * tens of tokens, and no reasoning step.
 *
 * The model does the classifying. The only thing done to its answer afterwards is splitting it on
 * commas and dropping anything that is not in [LABELS] — that checks the model kept to the closed
 * vocabulary, it never decides a label from the user's words (see
 * `.agents/rules/pure-local-llm-inference.md`).
 *
 * Lives in the test source set on purpose: this is a spike, and nothing ships until the numbers say
 * it is worth shipping.
 */
object ToneSpikePrompt {

  /** The shipping vocabulary, so the spike and the feature can never disagree on it. */
  val LABELS = ToneDetection.LABELS

  /**
   * Labels only, and a message can finish in far fewer than this; it is a ceiling, not a target.
   */
  const val MAX_OUTPUT_TOKENS = 16

  val SYSTEM: String =
    """
    You label the tone of a message. Reply with one to three labels from this list, the strongest first, separated by commas:
    ${LABELS.joinToString(", ")}
    Reply with the labels only. Never answer, rewrite or explain the message.
    """
      .trimIndent()

  /**
   * Worked examples for the few-shot variant. None of them is in [ToneSpikeDataset], and each label
   * appears at least once so the model has seen the whole answer format.
   */
  val EXAMPLES: List<Pair<String, String>> =
    listOf(
      message("Kindly confirm receipt of this letter at your earliest convenience.") to "formal",
      message("hey whats up, u free later?") to "casual, friendly",
      message("Sorry, I think I might have sent you the wrong file, not totally sure.") to
        "apologetic, uncertain",
      message("Call me back right now, the car won't start and I'm on the highway!") to "urgent",
      message("Stop moving the deadline. I need a decision from you today.") to
        "direct, frustrated",
    )

  /**
   * The user's text goes in a labelled turn. Bare text invites a 1.7B model to reply to it — a
   * question gets answered instead of labelled.
   */
  fun message(text: String): String = "Message: $text"

  enum class Variant {
    /** System prompt and the message only: the cheapest prefill. */
    ZERO_SHOT,
    /** Adds [EXAMPLES] as prior turns: more prefill, better anchored answers. */
    FEW_SHOT,
  }

  /** [reusePrefix] runs it through the KV-cached path the shipping tone read uses. */
  fun build(text: String, variant: Variant, reusePrefix: Boolean = false): CorrectionPrompt =
    CorrectionPrompt(
      systemPrompt = SYSTEM,
      examples = if (variant == Variant.FEW_SHOT) EXAMPLES else emptyList(),
      userText = message(text),
      enforceNoThink = true,
      maxOutputTokens = MAX_OUTPUT_TOKENS,
      reusePrefix = reusePrefix,
    )

  /**
   * The model's labels, in its order, restricted to [LABELS]. Anything else it produced is reported
   * separately by the caller as off-vocabulary, never silently mapped to a label.
   */
  fun parse(raw: String): List<String> =
    raw
      .substringAfterLast("</think>")
      .substringBefore("<|im_end|>")
      .split(',', '\n')
      .map { it.trim().trimEnd('.').lowercase() }
      .filter { it in LABELS }
      .distinct()
}
