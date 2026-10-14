package com.raghavsatyadev.grammarfix.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

/**
 * A tone the user can pick.
 *
 * Built-in rows are seeded from [com.raghavsatyadev.grammarfix.engine.DefaultTonePresets] on every
 * open, so a release that improves a shipped tone reaches users who never touched it. Rows with
 * [isBuiltIn] false are the user's own tones, and nothing but this table defines them.
 */
@Entity(tableName = "tone_preset")
data class TonePresetEntity(
  @PrimaryKey val id: String,
  val name: String,
  @ColumnInfo(name = "system_prompt") val systemPrompt: String,
  @ColumnInfo(name = "uses_thinking") val usesThinking: Boolean,
  @ColumnInfo(name = "max_output_tokens") val maxOutputTokens: Int,
  @ColumnInfo(name = "output_growth") val outputGrowth: Float,
  @ColumnInfo(name = "is_built_in") val isBuiltIn: Boolean,
)

/**
 * The one thing a user may change about a built-in tone: its system prompt.
 *
 * Kept apart from [TonePresetEntity] on purpose. A built-in tone stores only the prompt that was
 * actually reworded, so its name, worked examples and token budget still come from the app and can
 * still be improved later.
 */
@Entity(
  tableName = "tone_preset_override",
  foreignKeys =
    [
      ForeignKey(
        entity = TonePresetEntity::class,
        parentColumns = ["id"],
        childColumns = ["preset_id"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
)
data class TonePresetOverrideEntity(
  @PrimaryKey @ColumnInfo(name = "preset_id") val presetId: String,
  @ColumnInfo(name = "system_prompt") val systemPrompt: String,
)

/** Small key/value settings: the selected tone today, engine and fast-mode choices later. */
@Entity(tableName = "app_setting")
data class AppSettingEntity(@PrimaryKey val key: String, val value: String)
