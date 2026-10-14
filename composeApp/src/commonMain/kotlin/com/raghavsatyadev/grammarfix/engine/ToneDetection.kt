package com.raghavsatyadev.grammarfix.engine

/**
 * What the model read in a message (#32): up to three labels from [ToneDetection.LABELS], strongest
 * first, and one line in its own words on how the message comes across.
 *
 * [summary] is the model's description of the user's text, so it is shown and never stored; only
 * [labels], which are our own closed vocabulary, may reach the database (#19).
 */
data class ToneReading(val labels: List<String>, val summary: String)

/**
 * How the tone read gets its labels.
 * - [SCORE]: the model scores each label after `Tone:` and the summary is a separate, later call
 *   ([InferenceEngine.summarizeTone]). Labels in one prefill, no decoding.
 * - [GENERATE]: the model writes labels and summary in one answer, as #32 shipped. Kept so the
 *   tone-spike eval can compare the two, and used whenever the driver cannot score.
 */
enum class ToneReadMode {
  SCORE,
  GENERATE,
}

/** One tone read: the reading, or why there is none. */
data class ToneDetectionResult(
  val reading: ToneReading?,
  val latencyMs: Long,
  val engineMode: EngineMode,
  /** What the UI shows when [reading] is null. Never persisted. */
  val errorMessage: String? = null,
  /**
   * The labels were scored and the summary was not asked for yet: [ToneReading.summary] is empty
   * until [InferenceEngine.summarizeTone] fills it.
   */
  val summaryPending: Boolean = false,
) {
  val isSuccess: Boolean
    get() = reading != null
}

/**
 * The tone read's vocabulary, prompt and answer parsing.
 *
 * The model does the reading. [parse] only checks that the answer kept to the format and to the
 * closed vocabulary: it splits the `Tone:` line on commas and keeps what is in [LABELS], and takes
 * the `Summary:` line as it is. It never decides a label from the user's words, and nothing is
 * mapped from one label to another (`.agent/rules/pure-local-llm-inference.md`).
 */
object ToneDetection {

  /**
   * The fixed vocabulary, as measured by the #32 spike. Small and closed, so a label can be stored
   * the way a preset id is. `urgent` and `direct` are on probation until the 60-sample run.
   */
  val LABELS: List<String> =
    listOf(
      "formal",
      "casual",
      "friendly",
      "direct",
      "uncertain",
      "urgent",
      "apologetic",
      "frustrated",
    )

  /** Up to three labels and a one-line summary; a ceiling, not a target. */
  const val MAX_OUTPUT_TOKENS = 48

  /** At most this many labels are kept, as the prompt asks. */
  const val MAX_LABELS = 3

  /** A summary longer than this is not the one short sentence asked for, and is dropped. */
  const val MAX_SUMMARY_CHARS = 160

  private const val TONE_KEY = "tone:"
  private const val SUMMARY_KEY = "summary:"

  fun prompt(text: String, reusePrefix: Boolean = true): CorrectionPrompt =
    CorrectionPrompt(
      systemPrompt = Prompts.ToneRead.SYSTEM,
      examples = Prompts.ToneRead.EXAMPLES,
      userText = Prompts.ToneRead.MESSAGE_PREFIX + text,
      enforceNoThink = true,
      maxOutputTokens = MAX_OUTPUT_TOKENS,
      reusePrefix = reusePrefix,
    )

  /**
   * The reading in [raw], or null when it names no label from [LABELS]. A missing or overlong
   * summary leaves [ToneReading.summary] empty rather than failing a reading whose labels are fine.
   */
  fun parse(raw: String): ToneReading? {
    val lines =
      raw.substringAfterLast("</think>").substringBefore("<|im_end|>").lines().map { it.trim() }
    val labels =
      lines
        .firstOrNull { it.lowercase().startsWith(TONE_KEY) }
        ?.substring(TONE_KEY.length)
        ?.split(',')
        ?.map { it.trim().trimEnd('.').lowercase() }
        ?.filter { it in LABELS }
        ?.distinct()
        ?.take(MAX_LABELS)
        .orEmpty()
    if (labels.isEmpty()) return null
    val summary =
      lines
        .firstOrNull { it.lowercase().startsWith(SUMMARY_KEY) }
        ?.substring(SUMMARY_KEY.length)
        ?.trim()
        ?.takeIf { it.length <= MAX_SUMMARY_CHARS }
        .orEmpty()
    return ToneReading(labels, summary)
  }

  // ---- Scored read ("System One") ----

  /**
   * Where the scored read puts each label: right after the `Tone:` the worked examples begin their
   * answers with, so the model's probability for `" formal"` there is its probability of answering
   * "formal" first.
   */
  const val SCORE_ANSWER_PREFIX = "Tone:"

  /** The labels as the text that follows [SCORE_ANSWER_PREFIX], in [LABELS] order. */
  val SCORE_CHOICES: List<String> = LABELS.map { " $it" }

  /**
   * A label after the first is kept only when the model gives it at least this share of the
   * probability it spreads over [LABELS]. The first is always kept: it is what greedy generation
   * would have written. Chosen on the 60-sample tone-spike run; see [pickLabels].
   */
  const val SECONDARY_LABEL_MIN_SHARE = 0.01f

  /** The summary alone, after the labels already chosen: one short line. */
  const val SUMMARY_MAX_OUTPUT_TOKENS = 40

  /**
   * The same prompt as [prompt], with the model's answer opened at `Tone:`. Same fixed opening, so
   * it shares the saved KV state with the generated read and the summary.
   */
  fun scoringPrompt(text: String): CorrectionPrompt =
    prompt(text).copy(answerPrefix = SCORE_ANSWER_PREFIX)

  /**
   * The labels the model scored highest, strongest first: the top one always, then up to
   * [MAX_LABELS] in all whose share of the probability is at least [minShare]. [scores] is in
   * [SCORE_CHOICES] order. Empty when there are no usable scores.
   *
   * The model does the reading: this only ranks the probabilities it gave to our fixed vocabulary.
   */
  fun pickLabels(
    scores: List<ChoiceScore>,
    minShare: Float = SECONDARY_LABEL_MIN_SHARE,
  ): List<String> {
    if (scores.size != LABELS.size || scores.all { it.logProb == Float.NEGATIVE_INFINITY }) {
      return emptyList()
    }
    val ranked =
      ChoiceScore.normalised(scores).withIndex().sortedByDescending { it.value }.take(MAX_LABELS)
    return ranked
      .filterIndexed { rank, share -> rank == 0 || share.value >= minShare }
      .map { LABELS[it.index] }
  }

  /**
   * Asks for the summary line alone, after [labels] the scored read already chose: the answer is
   * opened with them so the summary agrees with the labels shown beside it.
   */
  fun summaryPrompt(text: String, labels: List<String>): CorrectionPrompt =
    prompt(text)
      .copy(
        answerPrefix = "$SCORE_ANSWER_PREFIX ${labels.joinToString(", ")}\nSummary:",
        maxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS,
      )

  /** The summary in the answer to [summaryPrompt], or empty when it is not one short line. */
  fun parseSummary(raw: String): String =
    raw
      .substringAfterLast("</think>")
      .substringBefore("<|im_end|>")
      .trim()
      .lineSequence()
      .firstOrNull()
      ?.trim()
      ?.takeIf { it.length <= MAX_SUMMARY_CHARS }
      .orEmpty()

  /** Stored form of [ToneReading.labels]: vocabulary ids only, comma-separated. */
  fun encodeLabels(labels: List<String>): String = labels.filter { it in LABELS }.joinToString(",")

  fun decodeLabels(stored: String): List<String> = stored.split(',').filter { it in LABELS }
}
