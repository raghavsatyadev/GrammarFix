package com.raghavsatyadev.grammarfix.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * One fix the user accepted from the overlay, as a kind and a time.
 *
 * `correction_event` counts what the model *suggested*, once per inference, so the same sentence
 * checked three times counts its fixes three times and a fix the user dismissed counts as well.
 * "Fixes applied" is read from here instead: one row per fix that actually reached the field.
 *
 * **No user text is stored here, ever** (#19): [kind] is a
 * [com.raghavsatyadev.grammarfix.engine.DiffType] name, and the column whitelist is pinned by
 * `AcceptedFixDaoTest` like the other analytics tables.
 */
@Entity(tableName = "accepted_fix", indices = [Index(value = ["at"])])
data class AcceptedFixEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  /** When the fix was written into the field, epoch milliseconds UTC. */
  val at: Long,
  /** `REPLACED`, `INSERTED` or `DELETED`. */
  val kind: String,
)

@Dao
interface AcceptedFixDao {

  /** `IGNORE` for the same reason as [CorrectionEventDao.insert]: `@Upsert` is broken here. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(fixes: List<AcceptedFixEntity>)

  /** Takes back the newest [count] fixes, for an Undo of the action that applied them. */
  @Query(
    "DELETE FROM accepted_fix WHERE id IN " +
      "(SELECT id FROM accepted_fix ORDER BY id DESC LIMIT :count)"
  )
  suspend fun deleteLatest(count: Int): Int

  @Query("SELECT * FROM accepted_fix ORDER BY at, id") suspend fun all(): List<AcceptedFixEntity>

  @Query(
    """
    SELECT COUNT(*) AS fixes,
           COALESCE(SUM(kind = 'REPLACED'), 0) AS replaced,
           COALESCE(SUM(kind = 'INSERTED'), 0) AS inserted,
           COALESCE(SUM(kind = 'DELETED'), 0) AS deleted
    FROM accepted_fix
    WHERE at >= :from AND at < :until
    """
  )
  suspend fun totalsBetween(from: Long, until: Long): AcceptedTotals

  /** Accepted fixes per local day, keyed like [CorrectionEventDao.dailyBuckets]. */
  @Query(
    """
    SELECT CAST(
             julianday(date(:now / 1000, 'unixepoch', 'localtime'))
             - julianday(date(at / 1000, 'unixepoch', 'localtime'))
           AS INTEGER) AS daysAgo,
           COUNT(*) AS fixes
    FROM accepted_fix
    WHERE at >= :from AND at < :now
    GROUP BY daysAgo
    ORDER BY daysAgo
    """
  )
  suspend fun dailyBuckets(from: Long, now: Long): List<AcceptedDayBucket>
}

/** The fixes the user accepted over one window, split by kind. */
data class AcceptedTotals(val fixes: Int, val replaced: Int, val inserted: Int, val deleted: Int) {
  companion object {
    val None = AcceptedTotals(0, 0, 0, 0)
  }
}

/** One local day of accepted fixes, counted back from the day the query was run. */
data class AcceptedDayBucket(val daysAgo: Int, val fixes: Int)
