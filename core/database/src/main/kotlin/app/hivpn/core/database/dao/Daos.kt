package app.hivpn.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.hivpn.core.database.entity.DailyTrafficEntity
import app.hivpn.core.database.entity.FavoriteServerEntity
import app.hivpn.core.database.entity.ImportedConfigEntity
import app.hivpn.core.database.entity.ProfileEntity
import app.hivpn.core.database.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY countryName, city, name")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observe(id: String): Flow<ServerEntity?>

    @Query("DELETE FROM servers")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(servers: List<ServerEntity>)

    /** Replaces the cached directory atomically. */
    @Transaction
    suspend fun replaceAll(servers: List<ServerEntity>) {
        clear()
        insertAll(servers)
    }
}

@Dao
interface FavoriteServerDao {
    @Query("SELECT serverId FROM favorite_servers")
    fun observeIds(): Flow<List<String>>

    @Upsert
    suspend fun add(favorite: FavoriteServerEntity)

    @Query("DELETE FROM favorite_servers WHERE serverId = :serverId")
    suspend fun remove(serverId: String)
}

@Dao
interface ImportedConfigDao {
    @Query("SELECT * FROM imported_configs ORDER BY createdAtEpochMs")
    fun observeAll(): Flow<List<ImportedConfigEntity>>

    @Query("SELECT * FROM imported_configs WHERE id = :id")
    fun observe(id: Long): Flow<ImportedConfigEntity?>

    @Insert
    suspend fun insert(config: ImportedConfigEntity): Long

    @Query("DELETE FROM imported_configs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TrafficDao {
    @Query("SELECT * FROM daily_traffic ORDER BY day DESC LIMIT :days")
    fun observeRecent(days: Int): Flow<List<DailyTrafficEntity>>

    // SQLite's "ON CONFLICT DO UPDATE" needs 3.24 (Android 11+); minSdk 26 ships 3.19,
    // so the upsert is done as insert-or-ignore + update in one transaction.
    @Query("INSERT OR IGNORE INTO daily_traffic(day, rxBytes, txBytes) VALUES(:day, 0, 0)")
    suspend fun ensureDay(day: String)

    @Query("UPDATE daily_traffic SET rxBytes = rxBytes + :rx, txBytes = txBytes + :tx WHERE day = :day")
    suspend fun increment(day: String, rx: Long, tx: Long)

    @Transaction
    suspend fun add(day: String, rx: Long, tx: Long) {
        ensureDay(day)
        increment(day, rx, tx)
    }

    @Query("DELETE FROM daily_traffic")
    suspend fun clear()
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAtEpochMs")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: Long): Flow<ProfileEntity?>

    @Query("SELECT id FROM profiles ORDER BY createdAtEpochMs LIMIT 1")
    suspend fun firstId(): Long?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}
