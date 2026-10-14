package com.raghavsatyadev.grammarfix.engine

/**
 * Which surface asked for a correction.
 *
 * Passed in by the caller rather than inferred, because the one thing that must never be guessed is
 * whether a run is real. [PLAYGROUND] runs are synthetic — "Run all 10" fires ten canned benchmark
 * sentences — and would swamp real usage in every chart.
 *
 * [PLAYGROUND] rows are **not written at all** rather than written and filtered later, so no query
 * added in a year's time can forget the filter. The exclusion is enforced twice: once in
 * [InferenceEngineImpl], before any recorder is reached, and again in
 * [com.raghavsatyadev.grammarfix.engine.RoomCorrectionRecorder].
 *
 * [OVERLAY] is the default everywhere, so a call site that forgets to say counts as real usage — a
 * row too many, never a Playground run smuggled into the user's statistics.
 */
enum class CorrectionSource {
  /** The floating assistant correcting text in another app. */
  OVERLAY,
  /** The in-app editor. */
  EDITOR,
  /** The developer Playground. Never recorded. */
  PLAYGROUND,
}

/**
 * Why a correction failed, as one of our own values.
 *
 * Deliberately not the exception's message: the messages are ours today, but a driver could surface
 * one built from the text it was given, and analytics must not be the place that finds out.
 */
enum class CorrectionErrorCode {
  /** The tone's own prompt leaves no room in the context window for the user's text. */
  PROMPT_TOO_LONG,
  /**
   * Anything else the model or its driver threw — no model downloaded, a background caller, a
   * native crash. Split this further only when a driver offers a type to split it on; guessing from
   * message text would put user content back in the database by the side door.
   */
  INFERENCE_FAILED,
}

/**
 * Records what the on-device model did, on device.
 *
 * Implementations store counts, never text. Nothing here uploads anything: this interface exists so
 * the user can see their own statistics, and a network-backed implementation would be a different
 * feature needing a different conversation.
 *
 * A recorder must never fail a correction. [InferenceEngineImpl] swallows whatever [record] throws,
 * so an implementation is free to throw rather than hide a real bug behind an empty catch.
 */
interface CorrectionRecorder {

  /** Called once per correction, after inference returns — successful or not. */
  suspend fun record(result: CorrectionResult, source: CorrectionSource)

  /** The default: keeps the engine and its tests free of a database. */
  object None : CorrectionRecorder {
    override suspend fun record(result: CorrectionResult, source: CorrectionSource) = Unit
  }
}
