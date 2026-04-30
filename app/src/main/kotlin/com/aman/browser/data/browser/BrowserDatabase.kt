package com.aman.browser.data.browser

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "history",
    indices = [Index(value = ["url"]), Index(value = ["visited_at"])],
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "url")        val url: String,
    @ColumnInfo(name = "title")      val title: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long,
    @ColumnInfo(name = "host")       val host: String,
)

@Entity(
    tableName = "downloads",
    indices = [Index(value = ["created_at"])],
)
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "url")          val url: String,
    @ColumnInfo(name = "filename")     val filename: String,
    @ColumnInfo(name = "mime")         val mime: String?,
    @ColumnInfo(name = "size_bytes")   val sizeBytes: Long,
    @ColumnInfo(name = "status")       val status: String,        // PENDING / COMPLETE / BLOCKED / FAILED
    @ColumnInfo(name = "block_reason") val blockReason: String?,  // populated when status=BLOCKED
    @ColumnInfo(name = "category")     val category: String?,     // APP_STORE / VPN / BROWSER / INSTALLABLE_PACKAGE / null
    @ColumnInfo(name = "local_path")   val localPath: String?,    // null for blocked
    @ColumnInfo(name = "created_at")   val createdAt: Long,
)

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry): Long

    @Query("UPDATE history SET title = :title WHERE url = :url AND visited_at = (SELECT MAX(visited_at) FROM history WHERE url = :url)")
    suspend fun updateLatestTitle(url: String, title: String)

    @Query("SELECT MAX(visited_at) FROM history WHERE url = :url")
    suspend fun lastVisitedAt(url: String): Long?

    @Query("SELECT * FROM history ORDER BY visited_at DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY visited_at DESC LIMIT 100")
    fun search(query: String): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE visited_at < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

@Dao
interface DownloadDao {

    @Insert
    suspend fun insert(entry: DownloadEntry): Long

    @Update
    suspend fun update(entry: DownloadEntry)

    @Query("UPDATE downloads SET status = :status, size_bytes = :size, local_path = :path WHERE id = :id")
    suspend fun markComplete(id: Long, status: String, size: Long, path: String?)

    @Query("SELECT * FROM downloads ORDER BY created_at DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<DownloadEntry>>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clear()
}

@Database(
    entities = [HistoryEntry::class, DownloadEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var instance: BrowserDatabase? = null

        fun get(context: android.content.Context): BrowserDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BrowserDatabase::class.java,
                    "aman_browser.db",
                ).build().also { instance = it }
            }
    }
}
