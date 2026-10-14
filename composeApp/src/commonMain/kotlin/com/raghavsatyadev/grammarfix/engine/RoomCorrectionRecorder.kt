package com.raghavsatyadev.grammarfix.engine

import com.raghavsatyadev.grammarfix.data.CorrectionEventEntity
import com.raghavsatyadev.grammarfix.data.GrammarFixDatabase
import kotlin.time.Clock
import org.koin.mp.KoinPlatform

/**
 * Writes one `correction_event` row per correction, on device and nowhere else.
 *
 * What is stored is counts, categories and timings. What is not stored is the user's writing: the
 * result carries [CorrectionResult.originalText] and [CorrectionResult.correctedText] because the
 * UI needs them, and this class reads their lengths and throws them away.
 *
 * [now] is injectable so a test can pin the timestamp; in the app it is the wall clock, since the
 * whole point of the column is grouping rows by the day the user wrote on.
 */
class RoomCorrectionRecorder(
  private val database: GrammarFixDatabase,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CorrectionRecorder {

  override suspend fun record(result: CorrectionResult, source: CorrectionSource) {
    // The engine already drops these before reaching a recorder. Repeated here so that a future
    // caller holding a recorder directly cannot put synthetic runs into the user's statistics.
    if (source == CorrectionSource.PLAYGROUND) return

    database.correctionEventDao().insert(result.toEvent(now(), source))
  }
}

/**
 * The application's recorder, resolved from Koin on first use.
 *
 * [LlmRuntime.engine] is built lazily and outside the graph — the accessibility overlay reaches it
 * without a ViewModel — so the engine cannot be handed a recorder at construction. Looking it up on
 * the first correction keeps the database out of the engine's constructor, keeps Compose previews
 * and screenshot tests (which start Koin without the full graph) working, and leaves every
 * `InferenceEngineImpl` built in a test on [CorrectionRecorder.None].
 */
class KoinCorrectionRecorder : CorrectionRecorder {

  private val delegate: CorrectionRecorder by lazy {
    try {
      KoinPlatform.getKoin().get<CorrectionRecorder>()
    } catch (e: Exception) {
      e.printStackTrace()
      CorrectionRecorder.None
    }
  }

  override suspend fun record(result: CorrectionResult, source: CorrectionSource) =
    delegate.record(result, source)
}

/**
 * Counts what the correction did, without keeping what it was about.
 *
 * Sentence boundaries come from [TextChunker] rather than a second scan written here: the engine
 * already decides where a sentence ends when it splits long text, and two definitions would drift.
 */
private fun CorrectionResult.toEvent(at: Long, source: CorrectionSource) =
  CorrectionEventEntity(
    at = at,
    source = source.name,
    tonePresetId = tonePresetId,
    engine = engineMode.name,
    wordsIn = inputWordCount,
    wordsOut = outputWordCount,
    charsIn = originalText.length,
    charsOut = correctedText.length,
    sentencesIn = TextChunker.sentenceCount(originalText),
    sentencesOut = TextChunker.sentenceCount(correctedText),
    fixesTotal = diffs.count { it.type != DiffType.UNCHANGED },
    fixesReplaced = diffs.count { it.type == DiffType.REPLACED },
    fixesInserted = diffs.count { it.type == DiffType.INSERTED },
    fixesDeleted = diffs.count { it.type == DiffType.DELETED },
    latencyMs = latencyMs,
    tokensPerSecond = tokensPerSecond,
    chunks = chunks,
    thinking = !isNonThinkingMode,
    succeeded = isSuccess,
    errorCode = errorCode?.name,
    delivered = wasDelivered,
  )
