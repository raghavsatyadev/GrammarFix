package com.raghavsatyadev.grammarfix.engine

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TonePresetRepositoryTest {

  /** Stands in for the database, so a "restart" is just a second repository over the same store. */
  private class FakeStore(private var settings: TonePresetSettings = TonePresetSettings()) :
    TonePresetStore {
    var saved: TonePresetSettings? = null

    override suspend fun load(): TonePresetSettings = settings

    override suspend fun save(settings: TonePresetSettings) {
      this.settings = settings
      saved = settings
    }
  }

  /**
   * The store is read on a coroutine now, so a repository is only worth asserting on once that read
   * has landed. [Dispatchers.Unconfined] makes it land before the constructor returns.
   */
  private suspend fun repositoryOver(store: TonePresetStore): TonePresetRepository =
    TonePresetRepository(store, CoroutineScope(Dispatchers.Unconfined)).also { it.awaitLoaded() }

  @Test
  fun shipsTheBuiltInTonesInTheOrderTheyAreListed() = runBlocking {
    val repository = repositoryOver(FakeStore())

    // Rephrase and Friendly joined the original four for the overlay's rewrite panel (#31).
    assertEquals(
      listOf("polish", "rephrase", "shorten", "friendly", "formal", "elaborate"),
      repository.presets.value.map { it.id },
    )
    assertTrue(repository.presets.value.all { it.isBuiltIn })
    assertEquals(DefaultTonePresets.POLISH_ID, repository.selected.value.id)
  }

  /** Selecting Polish must leave the live proofreading pass exactly as it was. */
  @Test
  fun polishIsTheProofreadingPassTheAppAlreadyRan() {
    assertEquals(Prompts.Proofread.SYSTEM, DefaultTonePresets.POLISH.systemPrompt)
    assertEquals(Prompts.Proofread.EXAMPLES, DefaultTonePresets.POLISH.examples)
    assertFalse(DefaultTonePresets.POLISH.usesThinking)
  }

  /** The issue asks for the rewrites to run with the model's reasoning mode on. */
  @Test
  fun rewriteTonesKeepTheModelsThinkingStep() {
    val rewrites =
      listOf(
        DefaultTonePresets.FORMAL,
        DefaultTonePresets.ELABORATE,
        DefaultTonePresets.SHORTEN,
        DefaultTonePresets.REPHRASE,
        DefaultTonePresets.FRIENDLY,
      )

    assertTrue(rewrites.all { it.usesThinking })
    // Reasoning tokens come out of the same budget, so a rewrite needs more room than a fix.
    assertTrue(rewrites.all { it.maxOutputTokens > TonePreset.DEFAULT_MAX_OUTPUT_TOKENS })
  }

  /** #31: Friendly reads about as long as what it was given; it adds warmth, not sentences. */
  @Test
  fun friendlyAndRephraseComeFromThePromptConstants() {
    assertEquals(Prompts.Friendly.SYSTEM, DefaultTonePresets.FRIENDLY.systemPrompt)
    assertEquals(Prompts.Friendly.EXAMPLES, DefaultTonePresets.FRIENDLY.examples)
    assertEquals(1f, DefaultTonePresets.FRIENDLY.outputGrowth)
    assertEquals(Prompts.Rephrase.SYSTEM, DefaultTonePresets.REPHRASE.systemPrompt)
    assertEquals(Prompts.Rephrase.EXAMPLES, DefaultTonePresets.REPHRASE.examples)
    assertTrue(DefaultTonePresets.FRIENDLY.examples.isNotEmpty())
    assertTrue(DefaultTonePresets.REPHRASE.examples.isNotEmpty())
  }

  /**
   * #31: a typed instruction is a transient tone — not built in, no examples, reasoning on — whose
   * id is fixed, so nothing derived from the user's words can reach analytics.
   */
  @Test
  fun anInstructionBecomesATransientToneWithAFixedId() {
    val tone = DefaultTonePresets.forInstruction("  make it sound professional ")

    assertEquals(DefaultTonePresets.INSTRUCTION_ID, tone.id)
    assertFalse(tone.isBuiltIn)
    assertTrue(tone.examples.isEmpty())
    assertTrue(tone.usesThinking)
    assertTrue(
      tone.systemPrompt.contains("following this instruction: make it sound professional\n")
    )
    assertFalse(tone.systemPrompt.contains(Prompts.Instruction.INSTRUCTION_PLACEHOLDER))
    assertEquals(null, DefaultTonePresets.byId(DefaultTonePresets.INSTRUCTION_ID))
  }

  @Test
  fun editingABuiltInPromptSurvivesARestart() = runBlocking {
    val store = FakeStore()
    repositoryOver(store).editPrompt(DefaultTonePresets.FORMAL_ID, "Be extremely terse.")

    val reopened = repositoryOver(store)
    val formal = reopened.presets.value.first { it.id == DefaultTonePresets.FORMAL_ID }

    assertEquals("Be extremely terse.", formal.systemPrompt)
    assertTrue(formal.hasEditedPrompt)
  }

  /** Only the prompt is stored, so a later release can still improve the examples and budgets. */
  @Test
  fun editingABuiltInToneStoresOnlyItsPrompt() = runBlocking {
    val store = FakeStore()
    val repository = repositoryOver(store)

    // The trailing space is what an on-device keyboard leaves behind; it must not be stored.
    repository.editPrompt(DefaultTonePresets.SHORTEN_ID, "Cut it in half. ")

    val saved = requireNotNull(store.saved)
    assertEquals(mapOf(DefaultTonePresets.SHORTEN_ID to "Cut it in half."), saved.builtInPrompts)
    assertTrue(saved.customPresets.isEmpty())
    val shorten = repository.presets.value.first { it.id == DefaultTonePresets.SHORTEN_ID }
    assertEquals(DefaultTonePresets.SHORTEN.examples, shorten.examples)
    assertEquals(DefaultTonePresets.SHORTEN.maxOutputTokens, shorten.maxOutputTokens)
  }

  @Test
  fun resetPromptRestoresTheShippedWording() = runBlocking {
    val repository = repositoryOver(FakeStore())
    repository.editPrompt(DefaultTonePresets.ELABORATE_ID, "Ramble.")

    repository.resetPrompt(DefaultTonePresets.ELABORATE_ID)

    val elaborate = repository.presets.value.first { it.id == DefaultTonePresets.ELABORATE_ID }
    assertEquals(DefaultTonePresets.ELABORATE.systemPrompt, elaborate.systemPrompt)
    assertFalse(elaborate.hasEditedPrompt)
  }

  @Test
  fun customTonesCanBeCreatedEditedAndDeleted() = runBlocking {
    val store = FakeStore()
    val repository = repositoryOver(store)

    val created = repository.addCustom("Casual", "Rewrite it like a text to a friend.")
    assertEquals(created.id, repository.selected.value.id)
    assertFalse(created.isBuiltIn)
    assertEquals(DefaultTonePresets.all.size + 1, repository.presets.value.size)

    repository.editCustom(created.id, "Chatty", "Rewrite it warmly.", usesThinking = false)
    val edited = repository.presets.value.first { it.id == created.id }
    assertEquals("Chatty", edited.name)
    assertEquals("Rewrite it warmly.", edited.systemPrompt)
    assertFalse(edited.usesThinking)

    repository.deleteCustom(created.id)
    assertEquals(DefaultTonePresets.all.size, repository.presets.value.size)
    assertNull(repository.presets.value.firstOrNull { it.id == created.id })
  }

  @Test
  fun deletingTheSelectedCustomToneFallsBackToPolish() = runBlocking {
    val repository = repositoryOver(FakeStore())
    val created = repository.addCustom("Technical", "Rewrite it for engineers.")

    repository.deleteCustom(created.id)

    assertEquals(DefaultTonePresets.POLISH_ID, repository.selected.value.id)
  }

  @Test
  fun customToneIdsStayUniqueAcrossDeletes() = runBlocking {
    val repository = repositoryOver(FakeStore())
    val first = repository.addCustom("One", "Prompt one.")
    val second = repository.addCustom("Two", "Prompt two.")
    repository.deleteCustom(first.id)

    val third = repository.addCustom("Three", "Prompt three.")

    assertEquals(2, repository.presets.value.count { !it.isBuiltIn })
    assertTrue(third.id != second.id)
  }

  /**
   * An edit made while the store is still being read must not be written on top of the defaults.
   *
   * The repository starts on the shipped tones and swaps them for the stored ones when the read
   * lands; a change that raced that read would save four untouched built-ins over the user's.
   */
  @Test
  fun anEditMadeBeforeTheStoreIsReadStillSeesWhatWasStored() = runBlocking {
    val store = FakeStore(TonePresetSettings(builtInPrompts = mapOf("formal" to "Stored wording.")))
    // A plain scope, not Unconfined: the read is genuinely still in flight here.
    val repository = TonePresetRepository(store, CoroutineScope(Dispatchers.Default))

    repository.select(DefaultTonePresets.SHORTEN_ID)

    assertEquals("Stored wording.", requireNotNull(store.saved).builtInPrompts["formal"])
    assertEquals(DefaultTonePresets.SHORTEN_ID, repository.selected.value.id)
  }

  /** The store the tones came from is one JSON file; it has to round trip too. */
  @Test
  fun fileStoreRoundTripsSelectionEditsAndCustomTones() = runBlocking {
    val file = File.createTempFile("tone_presets", ".json").apply { delete() }
    try {
      val repository = repositoryOver(FileTonePresetStore(file.absolutePath))
      repository.editPrompt(DefaultTonePresets.FORMAL_ID, "Be crisp and formal.")
      val custom = repository.addCustom("Technical", "Rewrite it for engineers.")

      val reopened = repositoryOver(FileTonePresetStore(file.absolutePath))

      assertEquals(custom.id, reopened.selected.value.id)
      assertEquals(
        "Be crisp and formal.",
        reopened.presets.value.first { it.id == DefaultTonePresets.FORMAL_ID }.systemPrompt,
      )
      assertEquals("Technical", reopened.presets.value.last().name)
    } finally {
      file.delete()
    }
  }

  /** A truncated or hand-edited file must not take the tone picker down with it. */
  @Test
  fun unreadableFileFallsBackToTheShippedTones() = runBlocking {
    val file = File.createTempFile("tone_presets", ".json")
    try {
      file.writeText("{ not json")

      val repository = repositoryOver(FileTonePresetStore(file.absolutePath))

      assertEquals(DefaultTonePresets.all.map { it.id }, repository.presets.value.map { it.id })
      assertEquals(DefaultTonePresets.POLISH_ID, repository.selected.value.id)
    } finally {
      file.delete()
    }
  }
}
