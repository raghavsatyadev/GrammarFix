package com.raghavsatyadev.grammarfix.engine

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.raghavsatyadev.grammarfix.data.GrammarFixDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Room store, against a real database file.
 *
 * These are the behaviours `TonePresetRepositoryTest` pins with a fake store, re-run against the
 * thing that actually persists them, plus the one behaviour only this class has: reading a user's
 * `tone_presets.json` into the database without losing anything they wrote.
 */
class RoomTonePresetStoreTest {

  private lateinit var directory: File
  private lateinit var legacyFile: File
  private var database: GrammarFixDatabase? = null

  @Before
  fun setUp() {
    directory = File.createTempFile("grammarfix-store", "").apply { delete() }
    directory.mkdirs()
    legacyFile = File(directory, TONE_PRESETS_FILE_NAME)
  }

  @After
  fun tearDown() {
    database?.close()
    directory.deleteRecursively()
  }

  /**
   * Reopening returns a new [GrammarFixDatabase] over the same file, which is what a restart is.
   */
  private fun openDatabase(): GrammarFixDatabase {
    database?.close()
    return Room.databaseBuilder<GrammarFixDatabase>(
        name = File(directory, "grammarfix.db").absolutePath
      )
      .setDriver(BundledSQLiteDriver())
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()
      .also { database = it }
  }

  private fun openStore() = RoomTonePresetStore(openDatabase(), legacyFile.absolutePath)

  private suspend fun repositoryOver(store: TonePresetStore) =
    TonePresetRepository(store, CoroutineScope(Dispatchers.Unconfined)).also { it.awaitLoaded() }

  /** Written through the old store, so the test reads exactly what the old app left behind. */
  private suspend fun writeLegacyJson(settings: TonePresetSettings) {
    FileTonePresetStore(legacyFile.absolutePath).save(settings)
  }

  // --- migration off tone_presets.json ---------------------------------------------------------

  /**
   * The upgrade path. A user who reworded a built-in prompt and wrote their own tone has real data
   * in that file, and the release that adds the database must not be the one that drops it.
   */
  @Test
  fun readsAnExistingJsonFileIntoTheDatabaseAndKeepsTheFile() = runBlocking {
    val custom =
      TonePreset(
        id = "custom-1",
        name = "Plain English",
        systemPrompt = "Rewrite it so anyone can follow it.",
        usesThinking = true,
        maxOutputTokens = 512,
        outputGrowth = 2f,
      )
    writeLegacyJson(
      TonePresetSettings(
        selectedPresetId = "custom-1",
        builtInPrompts = mapOf(DefaultTonePresets.FORMAL_ID to "Be crisp and formal."),
        customPresets = listOf(custom),
      )
    )

    val migrated = openStore().load()

    assertEquals("custom-1", migrated.selectedPresetId)
    assertEquals(
      mapOf(DefaultTonePresets.FORMAL_ID to "Be crisp and formal."),
      migrated.builtInPrompts,
    )
    assertEquals(listOf(custom), migrated.customPresets)
    // Renamed, not deleted: the first bug report about this migration stays recoverable.
    assertFalse(legacyFile.exists())
    assertTrue(File(directory, "${TONE_PRESETS_FILE_NAME}.migrated").exists())
  }

  /** Nothing to migrate is not an error; a fresh install just gets the shipped tones. */
  @Test
  fun withoutAJsonFileTheShippedTonesAreWhatLoads() = runBlocking {
    val loaded = openStore().load()

    assertEquals(DefaultTonePresets.POLISH_ID, loaded.selectedPresetId)
    assertTrue(loaded.builtInPrompts.isEmpty())
    assertTrue(loaded.customPresets.isEmpty())
  }

  /**
   * The file is read exactly once.
   *
   * A user who restores an old backup, or a crash that renamed nothing, must not have stale tones
   * written back over the ones they have since changed.
   */
  @Test
  fun aJsonFileThatComesBackIsNotReadASecondTime() = runBlocking {
    writeLegacyJson(TonePresetSettings(builtInPrompts = mapOf("formal" to "From the file.")))
    openStore().load()

    val store = openStore()
    store.save(TonePresetSettings(builtInPrompts = mapOf("formal" to "Changed since.")))
    writeLegacyJson(TonePresetSettings(builtInPrompts = mapOf("formal" to "From the file.")))

    assertEquals(mapOf("formal" to "Changed since."), openStore().load().builtInPrompts)
  }

  // --- what the database keeps ----------------------------------------------------------------

  /** Only the reworded prompt is stored, so the rest of a built-in tone stays ours to improve. */
  @Test
  fun aBuiltInToneStoresOnlyItsPrompt() = runBlocking {
    val store = openStore()
    val repository = repositoryOver(store)

    repository.editPrompt(DefaultTonePresets.SHORTEN_ID, "Cut it in half.")

    val dao = requireNotNull(database).tonePresetDao()
    assertEquals(
      listOf(DefaultTonePresets.SHORTEN_ID to "Cut it in half."),
      dao.overrides().map { it.presetId to it.systemPrompt },
    )
    assertTrue(dao.customPresets().isEmpty())
    val shorten = repository.presets.value.first { it.id == DefaultTonePresets.SHORTEN_ID }
    assertEquals(DefaultTonePresets.SHORTEN.examples, shorten.examples)
    assertEquals(DefaultTonePresets.SHORTEN.maxOutputTokens, shorten.maxOutputTokens)
  }

  /**
   * A release that improves a shipped prompt reaches a user who never touched that tone, because
   * the built-in rows are written from the app on every open rather than kept from last time.
   */
  @Test
  fun aShippedPromptIsRefreshedFromTheAppOnEveryOpen() = runBlocking {
    val store = openStore()
    store.load()
    val dao = requireNotNull(database).tonePresetDao()
    dao.refreshPresets(
      listOf(
        com.raghavsatyadev.grammarfix.data.TonePresetEntity(
          id = DefaultTonePresets.FORMAL_ID,
          name = "Formal",
          systemPrompt = "A prompt from an older release.",
          usesThinking = true,
          maxOutputTokens = 512,
          outputGrowth = 1.15f,
          isBuiltIn = true,
        )
      )
    )

    openStore().load()

    val formal = requireNotNull(database).tonePresetDao().overrides()
    assertTrue("an untouched tone has no override", formal.isEmpty())
    assertEquals(
      DefaultTonePresets.FORMAL.systemPrompt,
      repositoryOver(openStore())
        .presets
        .value
        .first { it.id == DefaultTonePresets.FORMAL_ID }
        .systemPrompt,
    )
  }

  /** Everything the repository promises, across a close and reopen of the database file. */
  @Test
  fun tonesSurviveAReopenOfTheDatabase() = runBlocking {
    val repository = repositoryOver(openStore())
    repository.editPrompt(DefaultTonePresets.FORMAL_ID, "Be crisp and formal.")
    val first = repository.addCustom("One", "Prompt one.")
    val second = repository.addCustom("Technical", "Rewrite it for engineers.")
    repository.deleteCustom(first.id)

    val reopened = repositoryOver(openStore())

    assertEquals(second.id, reopened.selected.value.id)
    assertEquals(
      "Be crisp and formal.",
      reopened.presets.value.first { it.id == DefaultTonePresets.FORMAL_ID }.systemPrompt,
    )
    assertEquals(
      listOf("Technical"),
      reopened.presets.value.filterNot { it.isBuiltIn }.map { it.name },
    )
    assertNull(reopened.presets.value.firstOrNull { it.id == first.id })
    // A tone added after a delete must not reuse the id the deleted one had.
    assertTrue(reopened.addCustom("Three", "Prompt three.").id != second.id)
  }

  /** Deleting the tone that was selected leaves the picker on Polish, not on nothing. */
  @Test
  fun deletingTheSelectedToneFallsBackToPolishAcrossAReopen() = runBlocking {
    val repository = repositoryOver(openStore())
    val created = repository.addCustom("Technical", "Rewrite it for engineers.")
    repository.deleteCustom(created.id)

    assertEquals(DefaultTonePresets.POLISH_ID, repositoryOver(openStore()).selected.value.id)
  }

  /** Resetting a built-in prompt drops its row rather than storing the shipped wording again. */
  @Test
  fun resettingAPromptRemovesTheOverride() = runBlocking {
    val repository = repositoryOver(openStore())
    repository.editPrompt(DefaultTonePresets.ELABORATE_ID, "Ramble.")

    repository.resetPrompt(DefaultTonePresets.ELABORATE_ID)

    assertTrue(requireNotNull(database).tonePresetDao().overrides().isEmpty())
    val elaborate =
      repositoryOver(openStore()).presets.value.first { it.id == DefaultTonePresets.ELABORATE_ID }
    assertEquals(DefaultTonePresets.ELABORATE.systemPrompt, elaborate.systemPrompt)
    assertFalse(elaborate.hasEditedPrompt)
  }
}
