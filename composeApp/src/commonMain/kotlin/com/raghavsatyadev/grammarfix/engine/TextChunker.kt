package com.raghavsatyadev.grammarfix.engine

import kotlin.math.max
import kotlin.math.min

/**
 * A text split into pieces that each fit one request, holding on to everything that lay between
 * them so the rewrites can be put back as the user wrote them.
 */
data class ChunkedText(
  val leading: String,
  val chunks: List<Chunk>,
  val trailing: String,
) {
  /** One request's worth of text. [separatorBefore] is empty for the first chunk. */
  data class Chunk(val separatorBefore: String, val text: String, val tokens: Int)

  /** Reassembles [rewritten] with the original spacing between the pieces. */
  fun rejoin(rewritten: List<String>): String {
    require(rewritten.size == chunks.size) {
      "Expected ${chunks.size} rewrites, got ${rewritten.size}"
    }
    return buildString {
      append(leading)
      rewritten.forEachIndexed { i, text ->
        append(chunks[i].separatorBefore)
        append(text)
      }
      append(trailing)
    }
  }
}

/**
 * Splits text that is too long for one request.
 *
 * Cuts are taken at the coarsest boundary that works — paragraph, then sentence, then word — so a
 * chunk is a piece of prose the model can rewrite on its own terms. A chunk that ends mid-sentence
 * would be rewritten as if the sentence ended there.
 *
 * Pieces are measured once each and packed by the sum of their counts. A tokeniser gives a run of
 * text slightly fewer tokens than its pieces measured separately, so packing this way errs towards
 * a smaller chunk — the safe direction, since the request is rejected outright if the estimate runs
 * low.
 */
object TextChunker {

  /**
   * Splits [text] into chunks of at most [maxTokens] as counted by [countTokens].
   *
   * [maxTokens] must be positive; the caller decides what to do when the prompt leaves no room for
   * the user's text at all.
   */
  suspend fun split(
    text: String,
    maxTokens: Int,
    countTokens: suspend (String) -> Int,
  ): ChunkedText {
    require(maxTokens > 0) { "maxTokens must be positive, was $maxTokens" }

    val leading = text.takeWhile { it.isWhitespace() }
    if (leading.length == text.length) {
      return ChunkedText("", listOf(ChunkedText.Chunk("", text, 0)), "")
    }
    val trailing = text.substring(leading.length).takeLastWhile { it.isWhitespace() }
    val body = text.substring(leading.length, text.length - trailing.length)

    val pieces = piecesOf(body, paragraphSeparators(body))
    val fitted = pieces.flatMap { (separator, content) ->
      fit(separator, content, maxTokens, countTokens)
    }

    return ChunkedText(leading, pack(fitted, maxTokens), trailing)
  }

  /**
   * How many sentences [text] holds, by the same boundaries [split] cuts on.
   *
   * Analytics counts sentences in and out. Reusing this scan keeps one definition of where a
   * sentence ends — a second counter written beside it would disagree on "Dr. Smith said 'go.'"
   * within a release or two.
   */
  fun sentenceCount(text: String): Int {
    val body = text.trim()
    if (body.isEmpty()) return 0
    return sentenceSeparators(body).size + 1
  }

  /** Greedily merges neighbouring pieces while they still fit in one request. */
  private fun pack(pieces: List<ChunkedText.Chunk>, maxTokens: Int): List<ChunkedText.Chunk> {
    val chunks = mutableListOf<ChunkedText.Chunk>()
    var current: ChunkedText.Chunk? = null
    for (piece in pieces) {
      val open = current
      val merged = if (open == null) null else open.tokens + piece.tokens
      current =
        if (open != null && merged != null && merged <= maxTokens) {
          open.copy(text = open.text + piece.separatorBefore + piece.text, tokens = merged)
        } else {
          if (open != null) chunks += open
          piece
        }
    }
    current?.let { chunks += it }
    return chunks
  }

  /** Measures [content] and, when it is too long, cuts it at the next finer boundary. */
  private suspend fun fit(
    separatorBefore: String,
    content: String,
    maxTokens: Int,
    countTokens: suspend (String) -> Int,
  ): List<ChunkedText.Chunk> {
    val tokens = countTokens(content)
    if (tokens <= maxTokens) return listOf(ChunkedText.Chunk(separatorBefore, content, tokens))

    for (separators in listOf(::sentenceSeparators, ::wordSeparators)) {
      val parts = piecesOf(content, separators(content))
      if (parts.size > 1) {
        return parts.flatMapIndexed { i, part ->
          fit(if (i == 0) separatorBefore else part.first, part.second, maxTokens, countTokens)
        }
      }
    }
    // One unbroken run — a URL, a base64 blob, a language that does not space its words.
    return hardSplit(separatorBefore, content, tokens, maxTokens, countTokens)
  }

  /**
   * Cuts a run with no boundary to cut on. The model will not make sense of half a token sequence,
   * but returning nothing at all is worse, and this is the only way the text moves at all.
   */
  private suspend fun hardSplit(
    separatorBefore: String,
    content: String,
    tokens: Int,
    maxTokens: Int,
    countTokens: suspend (String) -> Int,
  ): List<ChunkedText.Chunk> {
    val chunks = mutableListOf<ChunkedText.Chunk>()
    val charsPerToken = max(1, content.length / max(tokens, 1))
    var start = 0
    while (start < content.length) {
      var end =
        boundary(content, start, start + min(maxTokens * charsPerToken, content.length - start))
      var count = countTokens(content.substring(start, end))
      while (count > maxTokens && end - start > 1) {
        end = boundary(content, start, start + max(1, (end - start) * 3 / 4))
        count = countTokens(content.substring(start, end))
      }
      chunks +=
        ChunkedText.Chunk(
          if (chunks.isEmpty()) separatorBefore else "",
          content.substring(start, end),
          count,
        )
      start = end
    }
    return chunks
  }

  /** Keeps a cut from landing between the halves of a surrogate pair, and always advances. */
  private fun boundary(text: String, start: Int, at: Int): Int {
    val end = at.coerceIn(start + 1, text.length)
    if (end < text.length && text[end - 1].isHighSurrogate()) {
      return if (end - 1 > start) end - 1 else min(end + 1, text.length)
    }
    return end
  }

  /**
   * Builds (separatorBefore, content) pairs by cutting [text] out of the given separator ranges.
   * The first pair's separator is always empty.
   */
  private fun piecesOf(text: String, separators: List<IntRange>): List<Pair<String, String>> {
    if (separators.isEmpty()) return listOf("" to text)
    val pieces = mutableListOf<Pair<String, String>>()
    var contentStart = 0
    var separator = ""
    for (range in separators) {
      pieces += separator to text.substring(contentStart, range.first)
      separator = text.substring(range.first, range.last + 1)
      contentStart = range.last + 1
    }
    pieces += separator to text.substring(contentStart)
    return pieces.filter { it.second.isNotEmpty() }
  }

  /** Whitespace runs holding a blank line: the gap between two paragraphs. */
  private fun paragraphSeparators(text: String): List<IntRange> =
    whitespaceRuns(text).filter { range ->
      (range.first..range.last).count { text[it] == '\n' } >= 2
    }

  /** Whitespace runs that follow the end of a sentence. */
  private fun sentenceSeparators(text: String): List<IntRange> =
    whitespaceRuns(text).filter { range ->
      var i = range.first - 1
      // Let closing quotes and brackets stay with the sentence they end.
      while (i >= 0 && text[i] in CLOSERS) i--
      i >= 0 && text[i] in SENTENCE_ENDS
    }

  private fun wordSeparators(text: String): List<IntRange> = whitespaceRuns(text)

  /** Every maximal run of whitespace, excluding one at the very start or end. */
  private fun whitespaceRuns(text: String): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
      if (!text[i].isWhitespace()) {
        i++
        continue
      }
      val start = i
      while (i < text.length && text[i].isWhitespace()) i++
      if (start > 0 && i < text.length) runs += start until i
    }
    return runs
  }

  private const val SENTENCE_ENDS = ".!?…"
  private const val CLOSERS = "\"')]»”’"
}
