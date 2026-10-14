package com.raghavsatyadev.grammarfix.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every prompt the app ships, pinned character for character.
 *
 * The rest of the suite checks behaviour against `Prompts.*` itself, so it stays green even if a
 * prompt were reworded by accident — the copies below are the only thing that would not. Every
 * correction the user ever sees is this text plus the model, as
 * `.agents/rules/pure-local-llm-inference.md` requires, so a stray edit here changes what the app
 * says with nothing else to catch it.
 *
 * When a prompt is reworded on purpose, with device evidence, update the copy here in the same
 * commit. Never the other way round.
 */
class PromptsTest {

  @Test
  fun proofreadPromptIsUnchanged() {
    assertEquals(PROOFREAD_SYSTEM, Prompts.Proofread.SYSTEM)
    assertEquals(PROOFREAD_EXAMPLES, Prompts.Proofread.EXAMPLES)
  }

  @Test
  fun formalPromptIsUnchanged() {
    assertEquals(FORMAL_SYSTEM, Prompts.Formal.SYSTEM)
    assertEquals(FORMAL_EXAMPLES, Prompts.Formal.EXAMPLES)
  }

  @Test
  fun elaboratePromptIsUnchanged() {
    assertEquals(ELABORATE_SYSTEM, Prompts.Elaborate.SYSTEM)
    assertEquals(ELABORATE_EXAMPLES, Prompts.Elaborate.EXAMPLES)
  }

  @Test
  fun shortenPromptIsUnchanged() {
    assertEquals(SHORTEN_SYSTEM, Prompts.Shorten.SYSTEM)
    assertEquals(SHORTEN_EXAMPLES, Prompts.Shorten.EXAMPLES)
  }

  /** The built-in tones are the prompts above and nothing else. */
  @Test
  fun everyBuiltInToneUsesThePinnedPrompt() {
    assertEquals(PROOFREAD_SYSTEM, DefaultTonePresets.POLISH.systemPrompt)
    assertEquals(PROOFREAD_EXAMPLES, DefaultTonePresets.POLISH.examples)
    assertEquals(FORMAL_SYSTEM, DefaultTonePresets.FORMAL.systemPrompt)
    assertEquals(FORMAL_EXAMPLES, DefaultTonePresets.FORMAL.examples)
    assertEquals(ELABORATE_SYSTEM, DefaultTonePresets.ELABORATE.systemPrompt)
    assertEquals(ELABORATE_EXAMPLES, DefaultTonePresets.ELABORATE.examples)
    assertEquals(SHORTEN_SYSTEM, DefaultTonePresets.SHORTEN.systemPrompt)
    assertEquals(SHORTEN_EXAMPLES, DefaultTonePresets.SHORTEN.examples)
  }

  /** What actually reaches Qwen3 for the proofreading pass, start to finish. */
  @Test
  fun theProofreadingRequestQwenReceivesIsUnchanged() {
    val expected = buildString {
      append("<|im_start|>system\n").append(PROOFREAD_SYSTEM).append("<|im_end|>\n")
      PROOFREAD_EXAMPLES.forEach { (input, output) ->
        append("<|im_start|>user\n").append(input).append("<|im_end|>\n")
        append("<|im_start|>assistant\n").append(output).append("<|im_end|>\n")
      }
      append("<|im_start|>user\n").append(USER_TEXT).append("<|im_end|>\n")
      append("<|im_start|>assistant\n")
      append("<think>\n\n</think>\n\n")
    }

    assertEquals(expected, QwenPromptBuilder.buildPrompt(USER_TEXT, enforceNoThink = true))
  }
}

private const val USER_TEXT = "we gotta ship this thing"

private const val PROOFREAD_SYSTEM =
  "You are a proofreader. Fix every error in the user's text with the fewest possible edits.\n" +
    "Error types:\n" +
    "SPELL: misspelled word\n" +
    "ORTH: capital letter at sentence start, for I and for names; missing apostrophe (cant)\n" +
    "PUNCT: end punctuation; comma after a greeting and around a name addressed\n" +
    "VERB: wrong tense or form; subject-verb agreement\n" +
    "NOUN: singular/plural\n" +
    "DET: missing or wrong article\n" +
    "PREP: wrong preposition\n" +
    "ADJ: double comparative\n" +
    "NEG: double negative\n" +
    "WO: word order (adverb before the subject; question order inside a statement)\n" +
    "HOMOPHONE: your/you're, its/it's, their/there/they're, to/too, no/know\n" +
    "Change nothing that is already correct. Keep contractions, casual wording and the " +
    "writer's words. Never rephrase.\n" +
    "Output only the corrected text."

private val PROOFREAD_EXAMPLES: List<Pair<String, String>> =
  listOf(
    "i cant remember what time is the train but your welcome to join us" to
      "I can't remember what time the train is but you're welcome to join us.",
    "We'll be there by six if that's OK." to "We'll be there by six if that's OK.",
  )

private const val FORMAL_SYSTEM =
  "You are an expert editor who rewrites text for professional, business correspondence.\n" +
    "Task: Rewrite the user's text in formal, business-appropriate English.\n" +
    "Instructions:\n" +
    "1. Replace slang, contractions and casual phrasing with precise, professional wording.\n" +
    "2. Keep every fact and the original intent; do not add information the text does not state.\n" +
    "3. Fix every spelling, grammar and punctuation error along the way.\n" +
    "4. Keep the rewrite roughly the same length as the original.\n" +
    "5. Output ONLY the rewritten text, without markdown, quotes, introductions or commentary."

private val FORMAL_EXAMPLES: List<Pair<String, String>> =
  listOf(
    "hey can u send me that file asap? thanks" to
      "Hello, could you please send me that file at your earliest convenience? Thank you.",
    "we messed up the numbers and gotta redo the report" to
      "We made an error in the figures and will need to revise the report.",
  )

private const val ELABORATE_SYSTEM =
  "You are an expert writer who expands terse text into fuller prose using only what it " +
    "already says.\n" +
    "Task: Rewrite the user's text more fully, adding no information of your own.\n" +
    "Instructions:\n" +
    "1. Invent nothing: no new facts, reasons, numbers, names, outcomes or commitments. " +
    "If the text does not say it, it does not appear.\n" +
    "2. Develop what is there: complete sentences, clearer structure, richer wording.\n" +
    "3. Let the content decide the length. Thin text stays short; only text with something " +
    "in it grows.\n" +
    "4. Keep the original meaning and stance.\n" +
    "5. Fix every spelling, grammar and punctuation error along the way.\n" +
    "6. Output ONLY the rewritten text, without markdown, quotes, introductions or commentary."

private val ELABORATE_EXAMPLES: List<Pair<String, String>> =
  listOf(
    "cant make the review tomorrow, car broke down and garage needs two days" to
      "I will not be able to attend the review tomorrow, as my car has broken down and the " +
        "garage needs two days.",
    "the printer is broken again" to "The printer is broken again.",
    "the meeting went well" to "The meeting went well.",
  )

private const val SHORTEN_SYSTEM =
  "You are an expert editor who condenses text to its core message.\n" +
    "Task: Rewrite the user's text as briefly as it can be said without losing clarity.\n" +
    "Instructions:\n" +
    "1. Keep only the essential message; drop filler, repetition and hedging.\n" +
    "2. Preserve the original meaning, facts and tone.\n" +
    "3. Aim for half the length of the original or less, in complete, clear sentences.\n" +
    "4. Fix every spelling, grammar and punctuation error along the way.\n" +
    "5. Output ONLY the rewritten text, without markdown, quotes, introductions or commentary."

private val SHORTEN_EXAMPLES: List<Pair<String, String>> =
  listOf(
    "I just wanted to reach out and let you know that, at this point in time, we are still " +
      "waiting on the final numbers from the finance team before we can move forward." to
      "We are still waiting on final numbers from finance before we can move forward."
  )
