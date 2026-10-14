package com.raghavsatyadev.grammarfix.engine

import kotlinx.serialization.Serializable

/**
 * One way of rewriting the user's text: the system prompt the on-device model is given, the worked
 * examples that anchor its answer format, and how much room that answer needs.
 *
 * A tone is only ever a prompt. Nothing here rewrites text by rule — every word the user sees comes
 * out of the model, as `.agents/rules/pure-local-llm-inference.md` requires.
 */
@Serializable
data class TonePreset(
  val id: String,
  /** Default English name; the UI shows a translated string for the built-in tones. */
  val name: String,
  val systemPrompt: String,
  /** Worked (input, rewritten output) pairs sent as prior turns; empty for user-defined tones. */
  val examples: List<Pair<String, String>> = emptyList(),
  /**
   * Lets the model reason before answering, whatever fast mode is set to. A rewrite changes more
   * than spelling, so the tones that restructure a sentence take the reasoning step.
   */
  val usesThinking: Boolean = false,
  /**
   * Token budget for the answer: a tone that expands text needs more room than one that fixes it.
   */
  val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
  /**
   * How long this tone's answer runs next to the text it was given. A tone that expands needs its
   * input kept small, or the rewrite runs past the room the context window has left for it.
   */
  val outputGrowth: Float = 1f,
  val isBuiltIn: Boolean = false,
) {
  companion object {
    const val DEFAULT_MAX_OUTPUT_TOKENS = 256
  }
}

/** What is stored between runs: the user's choice and only the parts they changed. */
@Serializable
data class TonePresetSettings(
  val selectedPresetId: String = DefaultTonePresets.POLISH_ID,
  /** Edited system prompts for built-in tones, by preset id. Absent means "still the default". */
  val builtInPrompts: Map<String, String> = emptyMap(),
  val customPresets: List<TonePreset> = emptyList(),
)

/**
 * The tones the app ships with, in the order the Tones tab and the overlay's rewrite panel list
 * them.
 *
 * [POLISH] is the proofreading pass the app has always run: the same system prompt and examples the
 * overlay and the live editor use, so selecting it changes nothing about how text is corrected.
 */
object DefaultTonePresets {
  const val POLISH_ID = "polish"
  const val FORMAL_ID = "formal"
  const val ELABORATE_ID = "elaborate"
  const val SHORTEN_ID = "shorten"
  const val REPHRASE_ID = "rephrase"
  const val FRIENDLY_ID = "friendly"

  /**
   * What analytics records a free-text instruction from the overlay as (#31). The instruction is
   * the user's own words, so it must never reach the database; this constant is all that does.
   */
  const val INSTRUCTION_ID = "instruction"

  val POLISH =
    TonePreset(
      id = POLISH_ID,
      name = "Polish",
      systemPrompt = Prompts.Proofread.SYSTEM,
      examples = Prompts.Proofread.EXAMPLES,
      usesThinking = false,
      isBuiltIn = true,
    )

  val FORMAL =
    TonePreset(
      id = FORMAL_ID,
      name = "Formal",
      systemPrompt = Prompts.Formal.SYSTEM,
      examples = Prompts.Formal.EXAMPLES,
      usesThinking = true,
      maxOutputTokens = 512,
      // Formal wording runs a little longer than the casual phrasing it replaces.
      outputGrowth = 1.15f,
      isBuiltIn = true,
    )

  val ELABORATE =
    TonePreset(
      id = ELABORATE_ID,
      name = "Elaborate",
      systemPrompt = Prompts.Elaborate.SYSTEM,
      examples = Prompts.Elaborate.EXAMPLES,
      usesThinking = true,
      maxOutputTokens = 768,
      // The prompt no longer asks for a multiple of the original — a length target is what pushed
      // the model into inventing what it needed to fill (#35). The budget stays generous anyway:
      // it only decides how much input fits, and an answer cut off mid-sentence is worse than a
      // smaller allowance.
      outputGrowth = 3f,
      isBuiltIn = true,
    )

  val SHORTEN =
    TonePreset(
      id = SHORTEN_ID,
      name = "Shorten",
      systemPrompt = Prompts.Shorten.SYSTEM,
      examples = Prompts.Shorten.EXAMPLES,
      usesThinking = true,
      maxOutputTokens = 384,
      // Condensing never returns more than it was given; budget as if it returned all of it.
      outputGrowth = 1f,
      isBuiltIn = true,
    )

  val REPHRASE =
    TonePreset(
      id = REPHRASE_ID,
      name = "Rephrase",
      systemPrompt = Prompts.Rephrase.SYSTEM,
      examples = Prompts.Rephrase.EXAMPLES,
      usesThinking = true,
      maxOutputTokens = 384,
      // Rewording at the same length and register: the answer runs about as long as the input.
      outputGrowth = 1f,
      isBuiltIn = true,
    )

  val FRIENDLY =
    TonePreset(
      id = FRIENDLY_ID,
      name = "Friendly",
      systemPrompt = Prompts.Friendly.SYSTEM,
      examples = Prompts.Friendly.EXAMPLES,
      usesThinking = true,
      maxOutputTokens = 512,
      // A warmer phrasing adds a word here and there, not sentences.
      outputGrowth = 1f,
      isBuiltIn = true,
    )

  val all: List<TonePreset> = listOf(POLISH, REPHRASE, SHORTEN, FRIENDLY, FORMAL, ELABORATE)

  fun byId(id: String): TonePreset? = all.firstOrNull { it.id == id }

  /**
   * A one-off tone for an [instruction] the user typed into the overlay's rewrite panel.
   *
   * Transient: it is never added to [TonePresetRepository], and its id is the fixed
   * [INSTRUCTION_ID] rather than anything derived from the words, so analytics — which stores only
   * the id — cannot carry the instruction into the database. It goes through the same engine path
   * and the same prompt constants as every other tone ([Prompts.Instruction]).
   */
  fun forInstruction(instruction: String): TonePreset =
    TonePreset(
      id = INSTRUCTION_ID,
      name = instruction.trim(),
      systemPrompt =
        Prompts.Instruction.SYSTEM_TEMPLATE.replace(
          Prompts.Instruction.INSTRUCTION_PLACEHOLDER,
          instruction.trim(),
        ),
      examples = emptyList(),
      usesThinking = true,
      // An instruction may ask for anything, so give the answer the room a custom tone gets.
      maxOutputTokens = 512,
      outputGrowth = 2f,
      isBuiltIn = false,
    )
}

/**
 * Whether the model should skip its reasoning step for this tone.
 *
 * [fastMode] is the user's global preference; a tone that restructures a sentence declares
 * [TonePreset.usesThinking] and keeps the reasoning step whatever that preference is.
 */
fun TonePreset.enforceNoThink(fastMode: Boolean): Boolean = fastMode && !usesThinking

/** True when this is a tone we ship whose system prompt the user has reworded. */
val TonePreset.hasEditedPrompt: Boolean
  get() = isBuiltIn && DefaultTonePresets.byId(id)?.systemPrompt != systemPrompt
