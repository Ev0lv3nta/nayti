package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction

@Entity(tableName = "proof_manifest")
data class ProofManifestEntity(
    @PrimaryKey val revision: String,
    val segmentPath: String,
    val segmentLength: Long,
    val segmentSha256: String,
    val manifestPath: String,
    val manifestLength: Long,
    val manifestSha256: String,
)

@Entity(tableName = "proof_snapshot")
data class ProofSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val parentSnapshotId: String?,
    val manifestRevision: String,
)

@Entity(tableName = "proof_active_pointer")
data class ProofActivePointerEntity(
    @PrimaryKey val singletonId: Int = 1,
    val snapshotId: String?,
)

@Entity(tableName = "proof_publication")
data class ProofPublicationEntity(
    @PrimaryKey val token: String,
    val state: String,
)

@Entity(tableName = "proof_query_lease")
data class ProofQueryLeaseEntity(
    @PrimaryKey val token: String,
    val snapshotId: String,
    val expiresAtMillis: Long,
)

@Entity(tableName = "proof_delete_intent")
data class ProofDeleteIntentEntity(
    @PrimaryKey val path: String,
    val ownerSnapshotId: String,
    val expectedSha256: String,
    val state: String,
)

@Dao
interface PublicationProofDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManifest(manifest: ProofManifestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: ProofSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceActivePointer(pointer: ProofActivePointerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPublication(publication: ProofPublicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceLease(lease: ProofQueryLeaseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceDeleteIntents(intents: List<ProofDeleteIntentEntity>)

    @Query("SELECT state FROM proof_publication WHERE token = :token")
    suspend fun publicationState(token: String): String?

    @Query("UPDATE proof_publication SET state = :state WHERE token = :token")
    suspend fun updatePublicationState(token: String, state: String): Int

    @Query("UPDATE proof_publication SET state = 'ABANDONED' WHERE state = 'STAGED'")
    suspend fun abandonStagedPublications(): Int

    @Query("SELECT snapshotId FROM proof_active_pointer WHERE singletonId = 1")
    suspend fun activeSnapshotId(): String?

    @Query("SELECT * FROM proof_snapshot WHERE snapshotId = :snapshotId")
    suspend fun snapshot(snapshotId: String): ProofSnapshotEntity?

    @Query("SELECT * FROM proof_snapshot")
    suspend fun snapshots(): List<ProofSnapshotEntity>

    @Query("SELECT * FROM proof_manifest WHERE revision = :revision")
    suspend fun manifest(revision: String): ProofManifestEntity?

    @Query("SELECT * FROM proof_manifest")
    suspend fun manifests(): List<ProofManifestEntity>

    @Query("SELECT * FROM proof_query_lease")
    suspend fun leases(): List<ProofQueryLeaseEntity>

    @Query("DELETE FROM proof_query_lease WHERE expiresAtMillis <= :nowMillis")
    suspend fun deleteExpiredLeases(nowMillis: Long): Int

    @Query("DELETE FROM proof_query_lease WHERE token = :token")
    suspend fun deleteLease(token: String): Int

    @Query("SELECT * FROM proof_delete_intent WHERE ownerSnapshotId = :snapshotId")
    suspend fun deleteIntents(snapshotId: String): List<ProofDeleteIntentEntity>

    @Query(
        "SELECT DISTINCT intent.ownerSnapshotId FROM proof_delete_intent AS intent " +
            "INNER JOIN proof_snapshot AS snapshot " +
            "ON snapshot.snapshotId = intent.ownerSnapshotId",
    )
    suspend fun deleteIntentOwnersWithSnapshot(): List<String>

    @Query("UPDATE proof_delete_intent SET state = 'CONFIRMED' WHERE path = :path")
    suspend fun confirmDeleteIntent(path: String): Int

    @Query("DELETE FROM proof_delete_intent WHERE ownerSnapshotId = :snapshotId")
    suspend fun deleteDeleteIntents(snapshotId: String): Int

    @Query(
        "SELECT count(*) FROM proof_manifest " +
            "WHERE segmentPath = :segmentPath AND revision != :excludingRevision",
    )
    suspend fun otherSegmentReferenceCount(segmentPath: String, excludingRevision: String): Int

    @Query("DELETE FROM proof_snapshot WHERE snapshotId = :snapshotId")
    suspend fun deleteSnapshot(snapshotId: String): Int

    @Query("DELETE FROM proof_manifest WHERE revision = :revision")
    suspend fun deleteManifest(revision: String): Int

    @Query("SELECT count(*) FROM proof_query_lease WHERE snapshotId = :snapshotId AND expiresAtMillis > :nowMillis")
    suspend fun liveLeaseCount(snapshotId: String, nowMillis: Long): Int

    @Transaction
    suspend fun commitPublication(
        token: String,
        manifest: ProofManifestEntity,
        snapshot: ProofSnapshotEntity,
    ) {
        check(publicationState(token) == PUBLICATION_STAGED)
        insertManifest(manifest)
        insertSnapshot(snapshot)
        replaceActivePointer(ProofActivePointerEntity(snapshotId = snapshot.snapshotId))
        check(updatePublicationState(token, PUBLICATION_DONE) == 1)
    }

    @Transaction
    suspend fun failInsidePublicationTransaction(
        token: String,
        manifest: ProofManifestEntity,
        snapshot: ProofSnapshotEntity,
    ) {
        check(publicationState(token) == PUBLICATION_STAGED)
        insertManifest(manifest)
        insertSnapshot(snapshot)
        throw SimulatedProcessDeath(PublicationFailpoint.INSIDE_DB_TRANSACTION)
    }

    @Transaction
    suspend fun finalizeSnapshotDeletion(snapshotId: String, manifestRevision: String) {
        check(deleteSnapshot(snapshotId) == 1)
        check(deleteManifest(manifestRevision) == 1)
        deleteDeleteIntents(snapshotId)
    }

    companion object {
        const val PUBLICATION_STAGED = "STAGED"
        const val PUBLICATION_DONE = "DONE"
        const val PUBLICATION_ABANDONED = "ABANDONED"
    }
}

@Database(
    entities = [
        ProofManifestEntity::class,
        ProofSnapshotEntity::class,
        ProofActivePointerEntity::class,
        ProofPublicationEntity::class,
        ProofQueryLeaseEntity::class,
        ProofDeleteIntentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PublicationProofDatabase : RoomDatabase() {
    abstract fun publicationDao(): PublicationProofDao
}
