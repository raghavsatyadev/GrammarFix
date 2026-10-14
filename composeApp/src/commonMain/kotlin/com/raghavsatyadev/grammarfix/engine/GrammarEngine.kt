package com.raghavsatyadev.grammarfix.engine

data class TextSuggestion(
  val original: String,
  val replacement: String,
  val startIndex: Int = 0,
  val endIndex: Int = 0,
)

/**
 * One correction pass over [analyzedText]: the model's full output and each change located in it.
 */
data class TextAnalysis(
  val analyzedText: String,
  val correctedText: String,
  val suggestions: List<TextSuggestion>,
  /** Set when inference failed (for example, the model is not downloaded yet). */
  val errorMessage: String? = null,
  /**
   * True when the field moved on while the model was running, so [analyzedText] is no longer what
   * the user is looking at. Nothing here may be shown or applied — the fix would land in text it
   * was never computed for. See [CorrectionResult.wasDelivered].
   */
  val isStale: Boolean = false,
)

class GrammarEngine(
  private val inferenceEngine: InferenceEngine = LlmRuntime.engine,
  /** Which model to correct with; follows the user's choice in the app by default. */
  private val engineMode: () -> EngineMode = { LlmRuntime.engineMode.value },
  /**
   * What analytics records these corrections as. [defaultEngine] — the floating assistant's engine
   * — is the only caller that runs inference through [analyze], so the overlay is the default.
   */
  private val source: CorrectionSource = CorrectionSource.OVERLAY,
) {

  suspend fun analyzeText(text: String): List<TextSuggestion> = analyze(text).suggestions

  /**
   * Runs one correction pass and returns every suggestion with its character range in [text].
   *
   * The ranges let a caller apply a single fix with [applySuggestionAt] instead of replacing the
   * whole text with the model's full output.
   *
   * [isStillWanted] is asked when the model finishes, seconds later; a caller whose text can change
   * in the meantime passes its own check and reads the verdict back off [TextAnalysis.isStale].
   */
  suspend fun analyze(
    text: String,
    isStillWanted: suspend () -> Boolean = { true },
  ): TextAnalysis {
    if (text.isBlank()) return TextAnalysis(text, text, emptyList())

    val result =
      inferenceEngine.generateCorrection(
        text,
        enforceNoThink = true,
        engineMode = engineMode(),
        source = source,
        isStillWanted = isStillWanted,
      )
    return analysisOf(text, result)
  }

  /**
   * Turns one model [result] for [text] into located suggestions, without running inference.
   * Callers that already hold a [CorrectionResult] (the playground keeps its timing figures) use
   * this instead of [analyze].
   */
  fun analysisOf(text: String, result: CorrectionResult): TextAnalysis {
    val isStale = !result.wasDelivered
    if (!result.isSuccess)
      return TextAnalysis(text, text, emptyList(), result.errorMessage, isStale = isStale)

    return TextAnalysis(
      analyzedText = text,
      correctedText = result.correctedText,
      suggestions = locateSuggestions(text, result.diffs),
      isStale = isStale,
    )
  }

  /**
   * Maps word-level diffs onto character offsets in [text].
   *
   * [GrammarDiffAnalyzer] aligns the whitespace-separated words of [text] in order, so walking the
   * diff alongside the word spans of [text] gives each change an exact position.
   */
  private fun locateSuggestions(text: String, diffs: List<DiffItem>): List<TextSuggestion> {
    val spans = WORD.findAll(text).map { it.range }.toList()
    val suggestions = mutableListOf<TextSuggestion>()
    var k = 0

    for (diff in diffs) {
      when (diff.type) {
        DiffType.UNCHANGED -> k++

        DiffType.REPLACED -> {
          val span = spans.getOrNull(k++) ?: break
          suggestions +=
            TextSuggestion(diff.originalText, diff.correctedText, span.first, span.last + 1)
        }

        DiffType.DELETED -> {
          val span = spans.getOrNull(k++) ?: break
          // Take the following whitespace too, so no double space is left behind.
          val end = spans.getOrNull(k)?.first ?: (span.last + 1)
          suggestions += TextSuggestion(diff.originalText, "", span.first, end)
        }

        DiffType.INSERTED -> {
          val at = spans.getOrNull(k)?.first ?: text.length
          suggestions += TextSuggestion("", diff.correctedText, at, at)
        }
      }
    }
    return suggestions
  }

  /**
   * Applies exactly one [suggestion] to [text], leaving every other character untouched.
   *
   * Returns null when [text] no longer matches what was analysed at that position, so a stale
   * suggestion can never overwrite the wrong words.
   */
  fun applySuggestionAt(text: String, suggestion: TextSuggestion): String? {
    val (original, replacement, start, end) = suggestion
    if (start < 0 || end < start || end > text.length) return null

    if (original.isEmpty()) {
      // Insertion: separate the new word from its neighbour with a single space.
      return if (start >= text.length) "${text.trimEnd()} $replacement"
      else text.substring(0, start) + replacement + " " + text.substring(start)
    }

    if (!text.regionMatches(start, original, 0, original.length)) return null
    return text.substring(0, start) + replacement + text.substring(end)
  }

  companion object {
    val defaultEngine = GrammarEngine()

    /** Same word boundaries [GrammarDiffAnalyzer] splits on. */
    private val WORD = Regex("\\S+")
  }
}
