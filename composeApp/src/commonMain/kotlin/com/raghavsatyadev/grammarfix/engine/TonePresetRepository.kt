package com.raghavsatyadev.grammarfix.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Where the user's tone choices survive a restart.
 *
 * Suspending because the real store is now a database: Room 3 DAOs suspend unless they return an
 * observable type, and none of this is worth blocking a UI thread for.
 */
interface TonePresetStore {
  suspend fun load(): TonePresetSettings

  suspend fun save(settings: TonePresetSettings)
}

const val TONE_PRESETS_FILE_NAME = "tone_presets.json"

fun defaultTonePresetsPath(): String = "${modelStorageDir()}/$TONE_PRESETS_FILE_NAME"

/**
 * One JSON file next to the model. Unreadable or outdated content falls back to the defaults.
 *
 * Superseded by [RoomTonePresetStore], which migrates this file on first run. It stays because that
 * migration reads through it, and because a release that has to fall back wants it intact.
 */
class FileTonePresetStore(private val path: String = defaultTonePresetsPath()) : TonePresetStore {

  override suspend fun load(): TonePresetSettings {
    val raw = readTextFileOrNull(path) ?: return TonePresetSettings()
    return try {
      json.decodeFromString<TonePresetSettings>(raw)
    } catch (e: Exception) {
      e.printStackTrace()
      TonePresetSettings()
    }
  }

  override suspend fun save(settings: TonePresetSettings) {
    writeTextFile(path, json.encodeToString(settings))
  }

  private companion object {
    val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }
  }
}

/**
 * The tones the user can pick from, and the edits they have made to them.
 *
 * Built-in tones are never stored whole: only a changed system prompt is kept, so a later release
 * can improve the shipped examples, the token budget or the wording of a prompt the user left
 * alone. Custom tones are stored in full, because nothing else defines them.
 */
class TonePresetRepository(
  private val store: TonePresetStore,
  scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

  private val mutex = Mutex()

  private var settings: TonePresetSettings = TonePresetSettings()

  private val _presets = MutableStateFlow(presetsOf(settings))
  val presets: StateFlow<List<TonePreset>> = _presets.asStateFlow()

  private val _selected = MutableStateFlow(selectedOf(settings, _presets.value))
  val selected: StateFlow<TonePreset> = _selected.asStateFlow()

  /**
   * The first read from storage, which the flows start out ahead of: until it lands, the tones are
   * the ones the app ships with. Every change waits on it, so an edit made while the database is
   * still opening can never be written on top of those defaults.
   */
  private val initialLoad: Job = scope.launch {
    mutex.withLock {
      try {
        publish(store.load())
      } catch (e: Exception) {
        // A store that cannot be read must not take the tone picker down with it; the user gets
        // the shipped tones, the same fallback an unreadable `tone_presets.json` always had.
        e.printStackTrace()
      }
    }
  }

  /** Suspends until the stored tones have replaced the shipped defaults. */
  suspend fun awaitLoaded() = initialLoad.join()

  suspend fun select(id: String) = update { it.copy(selectedPresetId = id) }

  /** Replaces the system prompt of any tone; for a built-in one that is stored as an override. */
  suspend fun editPrompt(id: String, systemPrompt: String) = update { current ->
    val prompt = systemPrompt.trim()
    if (DefaultTonePresets.byId(id) != null) {
      current.copy(builtInPrompts = current.builtInPrompts + (id to prompt))
    } else {
      current.copy(
        customPresets =
          current.customPresets.map { if (it.id == id) it.copy(systemPrompt = prompt) else it }
      )
    }
  }

  /** Puts a built-in tone's system prompt back to the one the app ships with. */
  suspend fun resetPrompt(id: String) = update { it.copy(builtInPrompts = it.builtInPrompts - id) }

  /** Adds a user-defined tone and returns it, already selected. */
  suspend fun addCustom(
    name: String,
    systemPrompt: String,
    usesThinking: Boolean = true,
  ): TonePreset {
    lateinit var preset: TonePreset
    update { current ->
      preset =
        TonePreset(
          id = nextCustomId(current.customPresets),
          name = name.trim(),
          systemPrompt = systemPrompt.trim(),
          usesThinking = usesThinking,
          // A user-written tone may ask for anything, so give its answer room to finish.
          maxOutputTokens = CUSTOM_MAX_OUTPUT_TOKENS,
          outputGrowth = CUSTOM_OUTPUT_GROWTH,
          isBuiltIn = false,
        )
      current.copy(customPresets = current.customPresets + preset, selectedPresetId = preset.id)
    }
    return preset
  }

  /** Saves a user-defined tone's name, prompt and reasoning switch. Built-in tones are ignored. */
  suspend fun editCustom(id: String, name: String, systemPrompt: String, usesThinking: Boolean) =
    update { current ->
      current.copy(
        customPresets =
          current.customPresets.map {
            if (it.id == id)
              it.copy(
                name = name.trim(),
                systemPrompt = systemPrompt.trim(),
                usesThinking = usesThinking,
              )
            else it
          }
      )
    }

  /** Removes a user-defined tone. Built-in tones cannot be deleted, only reset. */
  suspend fun deleteCustom(id: String) = update {
    it.copy(customPresets = it.customPresets.filter { p -> p.id != id })
  }

  private suspend fun update(transform: (TonePresetSettings) -> TonePresetSettings) {
    initialLoad.join()
    mutex.withLock {
      val next = transform(settings)
      if (next == settings) return
      publish(next)
      store.save(next)
    }
  }

  private fun publish(next: TonePresetSettings) {
    settings = next
    val presets = presetsOf(next)
    _presets.value = presets
    _selected.value = selectedOf(next, presets)
  }

  private companion object {
    const val CUSTOM_ID_PREFIX = "custom-"
    const val CUSTOM_MAX_OUTPUT_TOKENS = 512

    /** Nothing says what a user-written tone does to the length, so assume it expands. */
    const val CUSTOM_OUTPUT_GROWTH = 2f

    fun presetsOf(settings: TonePresetSettings): List<TonePreset> =
      DefaultTonePresets.all.map { builtIn ->
        settings.builtInPrompts[builtIn.id]?.let { builtIn.copy(systemPrompt = it) } ?: builtIn
      } + settings.customPresets.map { it.copy(outputGrowth = CUSTOM_OUTPUT_GROWTH) }

    /** A tone deleted (or renamed away) since it was chosen falls back to the default. */
    fun selectedOf(settings: TonePresetSettings, presets: List<TonePreset>): TonePreset =
      presets.firstOrNull { it.id == settings.selectedPresetId }
        ?: presets.first { it.id == DefaultTonePresets.POLISH_ID }

    fun nextCustomId(existing: List<TonePreset>): String {
      var index = existing.size + 1
      while (existing.any { it.id == "$CUSTOM_ID_PREFIX$index" }) index++
      return "$CUSTOM_ID_PREFIX$index"
    }
  }
}
