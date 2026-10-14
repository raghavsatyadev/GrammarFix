package com.raghavsatyadev.grammarfix.engine

import com.raghavsatyadev.grammarfix.data.AppSettingEntity
import com.raghavsatyadev.grammarfix.data.GrammarFixDatabase
import com.raghavsatyadev.grammarfix.data.TonePresetEntity
import com.raghavsatyadev.grammarfix.data.TonePresetOverrideEntity

/** `app_setting` key holding the tone the user last picked. */
const val SELECTED_TONE_SETTING = "selected_tone_id"

/** Set once `tone_presets.json` has been read into the database, so it is never read twice. */
const val TONE_JSON_MIGRATED_SETTING = "tone_presets_migrated"

/**
 * The tone settings, kept in Room.
 *
 * Drops in behind [TonePresetStore] with no change to [TonePresetRepository]'s rules: built-in
 * tones still give up only their system prompt, custom tones are still stored whole.
 *
 * The first open of a database that has never seen `tone_presets.json` migrates it. The file is
 * renamed rather than deleted — a user who reworded a prompt or wrote their own tones must not lose
 * them to a bug in this class, and a renamed file makes the first bug report recoverable.
 */
class RoomTonePresetStore(
  private val database: GrammarFixDatabase,
  private val legacyFilePath: String = defaultTonePresetsPath(),
) : TonePresetStore {

  override suspend fun load(): TonePresetSettings {
    seedBuiltInPresets()
    migrateLegacyFile()
    return read()
  }

  override suspend fun save(settings: TonePresetSettings) {
    seedBuiltInPresets()
    write(settings)
  }

  /**
   * Writes the shipped tones into `tone_preset`.
   *
   * Run on every open, so a release that improves a built-in tone reaches a user who never edited
   * it. Nothing a user owns lives in these rows — their edits are in `tone_preset_override` — so
   * overwriting them loses nothing.
   */
  private suspend fun seedBuiltInPresets() {
    database.tonePresetDao().seedBuiltIns(DefaultTonePresets.all.map { it.toEntity() })
  }

  private suspend fun migrateLegacyFile() {
    if (database.appSettingDao().get(TONE_JSON_MIGRATED_SETTING) != null) return
    if (readTextFileOrNull(legacyFilePath) == null) return

    // The write carries the "migrated" marker in its own transaction, so a crash between here
    // and the rename replays cleanly rather than reading the file over newer tones.
    write(FileTonePresetStore(legacyFilePath).load())
    renameFile(legacyFilePath, "$legacyFilePath.migrated")
  }

  private suspend fun read(): TonePresetSettings {
    val dao = database.tonePresetDao()
    val builtInIds = DefaultTonePresets.all.map { it.id }.toSet()
    return TonePresetSettings(
      selectedPresetId =
        database.appSettingDao().get(SELECTED_TONE_SETTING) ?: DefaultTonePresets.POLISH_ID,
      builtInPrompts =
        dao
          .overrides()
          .filter { it.presetId in builtInIds }
          .associate {
            it.presetId to it.systemPrompt
          },
      customPresets = dao.customPresets().map { it.toPreset() },
    )
  }

  private suspend fun write(settings: TonePresetSettings) {
    val builtInIds = DefaultTonePresets.all.map { it.id }.toSet()
    database
      .tonePresetDao()
      .replaceUserTones(
        customPresets = settings.customPresets.map { it.toEntity() },
        overrides =
          settings.builtInPrompts
            .filterKeys { it in builtInIds }
            .map { (id, prompt) -> TonePresetOverrideEntity(presetId = id, systemPrompt = prompt) },
        settings =
          listOf(
            AppSettingEntity(SELECTED_TONE_SETTING, settings.selectedPresetId),
            AppSettingEntity(TONE_JSON_MIGRATED_SETTING, "1"),
          ),
      )
  }
}

private fun TonePreset.toEntity() =
  TonePresetEntity(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    usesThinking = usesThinking,
    maxOutputTokens = maxOutputTokens,
    outputGrowth = outputGrowth,
    isBuiltIn = isBuiltIn,
  )

/** Custom tones have no worked examples, so nothing is lost by not storing a column for them. */
private fun TonePresetEntity.toPreset() =
  TonePreset(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    usesThinking = usesThinking,
    maxOutputTokens = maxOutputTokens,
    outputGrowth = outputGrowth,
    isBuiltIn = isBuiltIn,
  )
