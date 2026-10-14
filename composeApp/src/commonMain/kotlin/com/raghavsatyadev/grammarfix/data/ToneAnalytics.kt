package com.raghavsatyadev.grammarfix.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * One tone reading the user was shown (#32), as labels only.
 *
 * **No user text is stored here, ever** (#19) — not the message, and not the model's one-line
 * summary of it, which describes the message and can repeat its words. [labels] holds only ids from
 * [com.raghavsatyadev.grammarfix.engine.ToneDetection.LABELS], our own closed vocabulary, written
 * through [com.raghavsatyadev.grammarfix.engine.ToneDetection.encodeLabels], which drops anything
 * outside it. The column whitelist is pinned by `ToneEventDaoTest`, like `correction_event`'s.
 *
 * Only readings that were delivered are written: one the user typed past was never seen.
 */
@Entity(tableName = "tone_event", indices = [Index(value = ["at"])])
data class ToneEventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  /** When the reading finished, epoch milliseconds UTC. */
  val at: Long,
  /** Vocabulary ids, strongest first, comma-separated: `direct,frustrated`. */
  val labels: String,
  /** A [com.raghavsatyadev.grammarfix.engine.EngineMode] name. */
  val engine: String,
  @ColumnInfo(name = "latency_ms") val latencyMs: Long,
)

@Dao
interface ToneEventDao {

  /** `IGNORE` for the same reason as [CorrectionEventDao.insert]: `@Upsert` is broken here. */
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(event: ToneEventEntity): Long

  @Query("SELECT * FROM tone_event ORDER BY at DESC, id DESC LIMIT 1")
  suspend fun latest(): ToneEventEntity?

  @Query("SELECT * FROM tone_event ORDER BY at, id") suspend fun all(): List<ToneEventEntity>
}
