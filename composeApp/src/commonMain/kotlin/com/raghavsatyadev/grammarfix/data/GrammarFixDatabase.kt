package com.raghavsatyadev.grammarfix.data

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * The app's one local database, shared by every target.
 *
 * Version 1 holds what used to live in `tone_presets.json`. Version 2 adds `correction_event`, the
 * on-device record of what the model has done for its user — counts only, never their writing, and
 * never leaving the device.
 *
 * Version 3 adds `correction_event.delivered`, which separates the corrections the user received
 * from the inferences that merely ran — see [CorrectionEventEntity.delivered].
 *
 * Version 4 adds `tone_event`: the labels of each tone reading the user was shown (#32), never the
 * text they were read from — see [ToneEventEntity].
 *
 * Each step adds only new structure and touches nothing that exists, so Room writes the migrations
 * itself. Every exported schema is committed: they are what makes the next migration reviewable,
 * and what the migration test builds an older database from.
 */
@Database(
  entities =
    [
      TonePresetEntity::class,
      TonePresetOverrideEntity::class,
      AppSettingEntity::class,
      CorrectionEventEntity::class,
      ToneEventEntity::class,
    ],
  version = 4,
  exportSchema = true,
  autoMigrations =
    [
      AutoMigration(from = 1, to = 2),
      AutoMigration(from = 2, to = 3),
      AutoMigration(from = 3, to = 4),
    ],
)
@ConstructedBy(GrammarFixDatabaseConstructor::class)
abstract class GrammarFixDatabase : RoomDatabase() {
  abstract fun tonePresetDao(): TonePresetDao

  abstract fun appSettingDao(): AppSettingDao

  abstract fun correctionEventDao(): CorrectionEventDao

  abstract fun toneEventDao(): ToneEventDao
}

/**
 * Room's generated `actual` per target. There is deliberately no hand-written actual: KSP writes
 * one into each platform's source set, which is what the suppression is for.
 */
@Suppress("KotlinNoActualForExpect")
expect object GrammarFixDatabaseConstructor : RoomDatabaseConstructor<GrammarFixDatabase> {
  override fun initialize(): GrammarFixDatabase
}
