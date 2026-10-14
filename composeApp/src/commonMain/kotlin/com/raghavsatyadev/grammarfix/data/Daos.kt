package com.raghavsatyadev.grammarfix.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

@Dao
interface TonePresetDao {

  /**
   * Writes the tones the app ships with, leaving anything the user owns alone.
   *
   * Insert-then-update rather than a replace: `INSERT OR REPLACE` deletes the row first, and the
   * delete cascades to `tone_preset_override` — seeding on every open would quietly drop the
   * prompts a user had reworded. Room 3.1.0-alpha01's `@Upsert` is not an option either; its update
   * path fails against the bundled driver.
   */
  @Transaction
  suspend fun seedBuiltIns(presets: List<TonePresetEntity>) {
    insertPresetsIfAbsent(presets)
    refreshPresets(presets)
  }

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertPresetsIfAbsent(presets: List<TonePresetEntity>)

  @Update suspend fun refreshPresets(presets: List<TonePresetEntity>)

  @Insert suspend fun insertPresets(presets: List<TonePresetEntity>)

  @Insert suspend fun insertOverrides(overrides: List<TonePresetOverrideEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun putSettings(settings: List<AppSettingEntity>)

  @Query("SELECT * FROM tone_preset WHERE is_built_in = 0 ORDER BY rowid")
  suspend fun customPresets(): List<TonePresetEntity>

  @Query("SELECT * FROM tone_preset_override")
  suspend fun overrides(): List<TonePresetOverrideEntity>

  @Query("DELETE FROM tone_preset WHERE is_built_in = 0") suspend fun deleteCustomPresets()

  @Query("DELETE FROM tone_preset_override") suspend fun deleteOverrides()

  /**
   * Replaces everything the user owns, in one transaction.
   *
   * This is the reason the tones left JSON: a kill mid-write now leaves the previous tones intact
   * instead of a half-written file that falls back to the defaults.
   */
  @Transaction
  suspend fun replaceUserTones(
    customPresets: List<TonePresetEntity>,
    overrides: List<TonePresetOverrideEntity>,
    settings: List<AppSettingEntity>,
  ) {
    deleteOverrides()
    deleteCustomPresets()
    insertPresets(customPresets)
    insertOverrides(overrides)
    putSettings(settings)
  }
}

@Dao
interface AppSettingDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(setting: AppSettingEntity)

  // `key` and `value` are SQL keywords, so they are quoted rather than renamed: the column names
  // are part of the committed schema and read the way the issue specifies them.
  @Query("SELECT `value` FROM app_setting WHERE `key` = :key") suspend fun get(key: String): String?
}
