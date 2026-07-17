package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface ModelPackDao {
    @Query("SELECT * FROM model_pack WHERE packId = :packId AND packVersion = :packVersion")
    suspend fun pack(packId: String, packVersion: String): ModelPackEntity?

    @Query("SELECT * FROM model_pack ORDER BY installedAtMillis, packId, packVersion")
    suspend fun packs(): List<ModelPackEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(pack: ModelPackEntity): Long

    @Transaction
    suspend fun registerInstalledCandidate(candidate: ModelPackEntity): ModelPackEntity {
        require(candidate.status == ModelPackStatus.INSTALLED_CANDIDATE)
        insertIfAbsent(candidate)
        val stored = checkNotNull(pack(candidate.packId, candidate.packVersion))
        check(stored == candidate) {
            "Pack identity already points to different immutable content"
        }
        return stored
    }
}
