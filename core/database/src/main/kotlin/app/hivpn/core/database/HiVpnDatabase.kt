package app.hivpn.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.hivpn.core.database.dao.FavoriteServerDao
import app.hivpn.core.database.dao.ImportedConfigDao
import app.hivpn.core.database.dao.ProfileDao
import app.hivpn.core.database.dao.ServerDao
import app.hivpn.core.database.dao.TrafficDao
import app.hivpn.core.database.entity.DailyTrafficEntity
import app.hivpn.core.database.entity.FavoriteServerEntity
import app.hivpn.core.database.entity.ImportedConfigEntity
import app.hivpn.core.database.entity.ProfileEntity
import app.hivpn.core.database.entity.ServerEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        ServerEntity::class,
        FavoriteServerEntity::class,
        ProfileEntity::class,
        ImportedConfigEntity::class,
        DailyTrafficEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HiVpnDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun favoriteServerDao(): FavoriteServerDao
    abstract fun profileDao(): ProfileDao
    abstract fun importedConfigDao(): ImportedConfigDao
    abstract fun trafficDao(): TrafficDao
}

internal class Converters {
    // Values are IP literals, CIDRs and package names, none of which can contain '\n'.
    @TypeConverter
    fun fromList(value: List<String>): String = value.joinToString("\n")

    @TypeConverter
    fun toList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\n")
}

/** v1 → v2: imported configs, daily traffic, profile route exclusions and keepalive. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN excludedRoutes TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE profiles ADD COLUMN persistentKeepalive INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS imported_configs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                interfaceAddresses TEXT NOT NULL,
                dnsServers TEXT NOT NULL,
                allowedIps TEXT NOT NULL,
                mtu INTEGER,
                persistentKeepalive INTEGER,
                peerPublicKey TEXT NOT NULL,
                hasPresharedKey INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_traffic (
                day TEXT PRIMARY KEY NOT NULL,
                rxBytes INTEGER NOT NULL,
                txBytes INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HiVpnDatabase =
        Room.databaseBuilder(context, HiVpnDatabase::class.java, "hivpn.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun serverDao(db: HiVpnDatabase): ServerDao = db.serverDao()
    @Provides fun favoriteServerDao(db: HiVpnDatabase): FavoriteServerDao = db.favoriteServerDao()
    @Provides fun profileDao(db: HiVpnDatabase): ProfileDao = db.profileDao()
    @Provides fun importedConfigDao(db: HiVpnDatabase): ImportedConfigDao = db.importedConfigDao()
    @Provides fun trafficDao(db: HiVpnDatabase): TrafficDao = db.trafficDao()
}
