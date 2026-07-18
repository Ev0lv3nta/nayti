package app.nayti.storage

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        CatalogAssetEntity::class,
        CatalogVolumeEntity::class,
        CatalogInventoryRunEntity::class,
        CatalogAccessObservationEntity::class,
        CatalogWatermarkEntity::class,
        ModelPackEntity::class,
        IndexOperationEntity::class,
        IndexOperationChannelEntity::class,
        IndexOperationAssetEntity::class,
        IndexExecutionWindowEntity::class,
        IndexChannelWorkEntity::class,
        IndexChannelPublicationEntity::class,
        IndexPublicationClockEntity::class,
        IndexErrorLedgerEntity::class,
        OcrDocumentEntity::class,
        OcrRegionEntity::class,
        OcrLexicalFtsEntity::class,
        OcrTrigramFtsEntity::class,
        OcrSemanticChunkSetEntity::class,
        OcrSemanticChunkEntity::class,
        OcrSemanticChunkLineEntity::class,
        VectorGenerationEntity::class,
        VectorSegmentArtifactEntity::class,
        VectorSegmentRecordEntity::class,
        VectorManifestEntity::class,
        VectorManifestSegmentEntity::class,
        ActivationSnapshotEntity::class,
        ActiveSnapshotPointerEntity::class,
        QuerySnapshotLeaseEntity::class,
        VectorPublicationEntity::class,
        ArtifactDeleteIntentEntity::class,
        ActivationCandidateEntity::class,
        ActivationSnapshotChannelEntity::class,
        PerceptualHashEntity::class,
    ],
    version = StorageContract.CurrentSchemaVersion,
    exportSchema = true,
)
abstract class NaytiDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun modelPackDao(): ModelPackDao
    abstract fun indexStateDao(): IndexStateDao
    abstract fun ocrDao(): OcrDao
    abstract fun ocrSemanticDao(): OcrSemanticDao
    abstract fun vectorIndexDao(): VectorIndexDao
    abstract fun perceptualHashDao(): PerceptualHashDao

    companion object {
        fun open(context: Context): NaytiDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NaytiDatabase::class.java,
                StorageContract.DatabaseFileName,
            ).setDriver(BundledSQLiteDriver())
                .addMigrations(*StorageMigrations.All)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setMultipleConnectionPool(maxNumOfReaders = 4, maxNumOfWriters = 1)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}

class CatalogStorage private constructor(
    private val database: NaytiDatabase,
) : AutoCloseable {
    val catalogDao: CatalogDao = database.catalogDao()
    val modelPackDao: ModelPackDao = database.modelPackDao()
    val indexStateDao: IndexStateDao = database.indexStateDao()
    val ocrDao: OcrDao = database.ocrDao()
    val ocrSemanticDao: OcrSemanticDao = database.ocrSemanticDao()
    val vectorIndexDao: VectorIndexDao = database.vectorIndexDao()
    val perceptualHashDao: PerceptualHashDao = database.perceptualHashDao()

    override fun close() {
        database.close()
    }

    companion object {
        fun open(context: Context): CatalogStorage = CatalogStorage(NaytiDatabase.open(context))
    }
}
