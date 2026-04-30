package com.aman.browser.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ── Entity ────────────────────────────────────────────────────────────────────
@Entity(tableName = "blur_events")
data class BlurEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "date")       val date: String,        // ISO-8601 yyyy-MM-dd
    @ColumnInfo(name = "category")   val category: String,    // NSFW | FACE | SKIN
    @ColumnInfo(name = "domain")     val domain: String,      // e.g. instagram.com
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
)

// ── DAO ───────────────────────────────────────────────────────────────────────
@Dao
interface BlurEventDao {

    @Insert
    suspend fun insert(event: BlurEvent)

    @Query("SELECT COUNT(*) FROM blur_events WHERE date = :date")
    fun countToday(date: String): Flow<Long>

    @Query("SELECT COUNT(*) FROM blur_events")
    fun countAll(): Flow<Long>

    @Query("""
        SELECT category, COUNT(*) as cnt
        FROM blur_events
        GROUP BY category
        ORDER BY cnt DESC
    """)
    fun categoryCounts(): Flow<List<CategoryCount>>

    @Query("""
        SELECT domain, COUNT(*) as cnt
        FROM blur_events
        GROUP BY domain
        ORDER BY cnt DESC
        LIMIT 1
    """)
    fun topDomain(): Flow<DomainCount?>

    @Query("""
        SELECT domain, COUNT(*) as cnt
        FROM blur_events
        GROUP BY domain
        ORDER BY cnt DESC
        LIMIT 10
    """)
    fun topDomains(): Flow<List<DomainCount>>

    @Query("SELECT AVG(elapsed_ms) FROM blur_events")
    fun avgElapsedMs(): Flow<Double?>

    @Query("DELETE FROM blur_events WHERE date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String)
}

// ── Helper query result classes ───────────────────────────────────────────────
data class CategoryCount(val category: String, val cnt: Long)
data class DomainCount(val domain: String, val cnt: Long)

// ── Database ──────────────────────────────────────────────────────────────────
@Database(entities = [BlurEvent::class], version = 1, exportSchema = false)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun blurEventDao(): BlurEventDao

    companion object {
        @Volatile private var instance: StatsDatabase? = null

        fun get(context: android.content.Context): StatsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StatsDatabase::class.java,
                    "aman_stats.db"
                ).build().also { instance = it }
            }
    }
}

// ── Repository ────────────────────────────────────────────────────────────────
class StatsRepository(context: android.content.Context) {

    private val dao = StatsDatabase.get(context).blurEventDao()
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun recordBlur(category: String, domain: String, elapsedMs: Long) {
        dao.insert(
            BlurEvent(
                date      = LocalDate.now().format(fmt),
                category  = category,
                domain    = domain,
                elapsedMs = elapsedMs,
            )
        )
    }

    fun todayCount():      Flow<Long>               = dao.countToday(LocalDate.now().format(fmt))
    fun totalCount():      Flow<Long>               = dao.countAll()
    fun categoryCounts():  Flow<List<CategoryCount>> = dao.categoryCounts()
    fun topDomain():       Flow<DomainCount?>        = dao.topDomain()
    fun topDomains():      Flow<List<DomainCount>>   = dao.topDomains()
    fun avgElapsedMs():    Flow<Double?>             = dao.avgElapsedMs()

    /** Clean up events older than 90 days to avoid unbounded DB growth. */
    suspend fun pruneOldEvents() {
        val cutoff = LocalDate.now().minusDays(90).format(fmt)
        dao.deleteOlderThan(cutoff)
    }
}
