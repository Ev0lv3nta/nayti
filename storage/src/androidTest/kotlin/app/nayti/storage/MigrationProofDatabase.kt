package app.nayti.storage

import androidx.room3.ColumnInfo
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Entity(tableName = "migration_probe")
data class MigrationProbeV1(
    @PrimaryKey val id: Long,
    val value: String,
)

@Entity(tableName = "migration_probe")
data class MigrationProbeV2(
    @PrimaryKey val id: Long,
    val value: String,
    @ColumnInfo(defaultValue = "0") val revision: Long,
)

@Database(entities = [MigrationProbeV1::class], version = 1, exportSchema = false)
abstract class MigrationProofV1Database : RoomDatabase()

@Database(entities = [MigrationProbeV2::class], version = 2, exportSchema = false)
abstract class MigrationProofV2Database : RoomDatabase()

val migrationProof1To2: Migration =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE migration_probe " +
                    "ADD COLUMN revision INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
