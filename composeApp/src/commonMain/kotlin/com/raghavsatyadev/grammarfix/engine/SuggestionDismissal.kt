package com.raghavsatyadev.grammarfix.engine

/** What a suggestion does to the text, read off its diff; drives the popup's label. */
enum class SuggestionKind {
  ADD,
  REMOVE,
  REPLACE,
}

val TextSuggestion.kind: SuggestionKind
  get() =
    when {
      original.isEmpty() -> SuggestionKind.ADD
      replacement.isEmpty() -> SuggestionKind.REMOVE
      else -> SuggestionKind.REPLACE
    }

/**
 * Identifies a suggestion by its change rather than its position, so a fix the user dismissed stays
 * dismissed when the same text is analysed again.
 */
data class SuggestionKey(val original: String, val replacement: String)

val TextSuggestion.key: SuggestionKey
  get() = SuggestionKey(original, replacement)

/**
 * Drops the suggestions whose key is in [dismissed].
 *
 * [TextAnalysis.correctedText] is the model's full output, so it still contains the dismissed
 * fixes; it is rebuilt from the remaining suggestions so that "Accept all" never applies one the
 * user rejected. With nothing dismissed the analysis is returned unchanged.
 */
fun TextAnalysis.withoutDismissed(
  dismissed: Set<SuggestionKey>,
  engine: GrammarEngine,
): TextAnalysis {
  val kept = suggestions.filterNot { it.key in dismissed }
  if (kept.size == suggestions.size) return this

  // Apply from the end of the text backwards, so each fix leaves the offsets of the earlier ones
  // valid.
  val corrected =
    kept
      .sortedByDescending { it.startIndex }
      .fold(analyzedText) { text, suggestion ->
        engine.applySuggestionAt(text, suggestion) ?: text
      }
  return copy(correctedText = corrected, suggestions = kept)
}
