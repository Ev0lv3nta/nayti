package app.nayti

import android.content.Context
import app.nayti.indexer.ActivationCandidateVerifier
import app.nayti.indexer.AtomicSnapshotActivator
import app.nayti.indexer.CandidateActivationCoordinator
import app.nayti.indexer.CandidateBuildControl
import app.nayti.indexer.CandidateChannelContractResolver
import app.nayti.indexer.CatalogRuntime
import app.nayti.indexer.IndexResourceGovernor
import app.nayti.indexer.IndexExecutionGate
import app.nayti.indexer.InstalledOcrPackResolver
import app.nayti.indexer.InstalledSiglip2TextQuerySessionFactory
import app.nayti.indexer.InstalledUser2QuerySessionFactory
import app.nayti.indexer.ModelPackActivationRuntime
import app.nayti.indexer.ModelPackInstallCoordinator
import app.nayti.indexer.ModelPackRuntime
import app.nayti.indexer.NeuralExecutionLane
import app.nayti.indexer.OcrHybridSearch
import app.nayti.indexer.OcrIndexingRuntime
import app.nayti.indexer.OcrSemanticSearch
import app.nayti.indexer.PerceptualHashSearch
import app.nayti.indexer.ProductionCandidateCanaryVerifier
import app.nayti.indexer.ProductionCandidateShadowBuilder
import app.nayti.indexer.QuarantineGarbageCollector
import app.nayti.indexer.UnifiedSearch
import app.nayti.indexer.VisualSimilaritySearch
import app.nayti.indexer.VisualTextSearch
import app.nayti.indexer.VectorSnapshotIntegrityVerifier
import app.nayti.indexing.AndroidIndexResourceGovernor
import app.nayti.ml.runtime.pack.AlphaModelPackTrust
import app.nayti.ml.runtime.pack.AndroidModelPackPolicy
import app.nayti.ml.runtime.pack.AndroidModelPackStorageBudget
import app.nayti.ml.runtime.pack.ModelPackInstaller
import app.nayti.ml.runtime.pack.OrtKnownAnswerPayloadValidator
import app.nayti.platform.media.AndroidMediaStoreGateway
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.storage.CatalogStorage
import app.nayti.storage.StorageContract
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object CatalogRuntimeModule {
    @Provides
    @Singleton
    fun provideStorage(@ApplicationContext context: Context): CatalogStorage = CatalogStorage.open(context)

    @Provides
    @Singleton
    fun provideCatalogRuntime(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
    ): CatalogRuntime = CatalogRuntime.create(context, storage).also(CatalogRuntime::start)

    @Provides
    @Singleton
    fun provideModelPackRuntime(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
    ): ModelPackRuntime {
        val root = context.noBackupFilesDir.toPath().resolve(StorageContract.ModelPackDirectory)
        val installer =
            ModelPackInstallCoordinator(
                installer =
                    ModelPackInstaller(
                        root = root,
                        trustedKeys = AlphaModelPackTrust.keys,
                        policy = AndroidModelPackPolicy.current(appVersionCode = BuildConfig.VERSION_CODE.toLong()),
                        storageBudget = AndroidModelPackStorageBudget(context),
                        payloadValidator = OrtKnownAnswerPayloadValidator(),
                    ),
                registry = storage.modelPackDao,
                modelPackRoot = root,
                nowMillis = System::currentTimeMillis,
            )
        return ModelPackRuntime(
            installer = installer,
            registry = storage.modelPackDao,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            activePack = {
                val snapshotId = storage.vectorIndexDao.activeSnapshotId()
                val snapshot = snapshotId?.let { id -> storage.vectorIndexDao.snapshot(id) }
                snapshot?.let { active -> storage.modelPackDao.pack(active.packId, active.packVersion) }
            },
        ).also(ModelPackRuntime::start)
    }

    @Provides
    @Singleton
    fun provideIndexResourceGovernor(
        @ApplicationContext context: Context,
    ): IndexResourceGovernor = AndroidIndexResourceGovernor(context)

    @Provides
    @Singleton
    fun provideNeuralExecutionLane(): NeuralExecutionLane = NeuralExecutionLane()

    @Provides
    @Singleton
    fun provideIndexExecutionGate(): IndexExecutionGate = IndexExecutionGate()

    @Provides
    @Singleton
    fun provideQuarantineGarbageCollector(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
        executionGate: IndexExecutionGate,
    ): QuarantineGarbageCollector =
        QuarantineGarbageCollector(
            storage = storage,
            vectorRoot = context.noBackupFilesDir.resolve(StorageContract.VectorIndexDirectory),
            executionGate = executionGate,
        )

    @Provides
    @Singleton
    fun provideInstalledOcrPackResolver(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
    ): InstalledOcrPackResolver =
        InstalledOcrPackResolver(
            storage.modelPackDao,
            context.noBackupFilesDir.toPath().resolve(StorageContract.ModelPackDirectory),
        )

    @Provides
    @Singleton
    fun provideMediaDecoder(@ApplicationContext context: Context): BoundedMediaDecoder =
        BoundedMediaDecoder(context.contentResolver, AndroidMediaStoreGateway(context))

    @Provides
    @Singleton
    fun provideModelPackActivationRuntime(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
        packResolver: InstalledOcrPackResolver,
        decoder: BoundedMediaDecoder,
        neuralLane: NeuralExecutionLane,
        executionGate: IndexExecutionGate,
    ): ModelPackActivationRuntime {
        val vectorRoot = context.noBackupFilesDir.resolve(StorageContract.VectorIndexDirectory)
        val continueExecution = AtomicBoolean(false)
        val integrityVerifier = VectorSnapshotIntegrityVerifier(vectorRoot, storage.vectorIndexDao)
        val activation =
            AtomicSnapshotActivator(
                vectors = storage.vectorIndexDao,
                verifier = ActivationCandidateVerifier { snapshot, channels ->
                    check(
                        integrityVerifier.verify(
                            snapshot,
                            deepVerifySegments = true,
                            candidateChannels = channels,
                        ),
                    )
                },
            )
        val builder =
            ProductionCandidateShadowBuilder(
                storage = storage,
                packResolver = packResolver,
                decoder = decoder,
                vectorRoot = vectorRoot,
                activation = activation,
                neuralLane = neuralLane,
                control = CandidateBuildControl(continueExecution::get),
            )
        return ModelPackActivationRuntime(
            vectors = storage.vectorIndexDao,
            contracts = CandidateChannelContractResolver(storage.vectorIndexDao, packResolver),
            coordinator =
                CandidateActivationCoordinator(
                    activation = activation,
                    builder = builder,
                    canary = ProductionCandidateCanaryVerifier(storage, packResolver, vectorRoot),
                ),
            continueExecution = continueExecution,
            executionGate = executionGate,
            rollbackAction = rollback@{
                val pointer = storage.vectorIndexDao.activePointer() ?: return@rollback null
                val rollbackId = pointer.rollbackSnapshotId ?: return@rollback null
                val rollbackSnapshot = storage.vectorIndexDao.snapshot(rollbackId) ?: return@rollback null
                check(integrityVerifier.verify(rollbackSnapshot, deepVerifySegments = true))
                activation.rollback()
            },
        )
    }

    @Provides
    @Singleton
    fun provideOcrIndexingRuntime(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
        resourceGovernor: IndexResourceGovernor,
        neuralLane: NeuralExecutionLane,
        executionGate: IndexExecutionGate,
        packResolver: InstalledOcrPackResolver,
        decoder: BoundedMediaDecoder,
    ): OcrIndexingRuntime {
        return OcrIndexingRuntime(
            storage = storage,
            packResolver = packResolver,
            decoder = decoder,
            vectorRoot = context.noBackupFilesDir.resolve(StorageContract.VectorIndexDirectory),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            resourceGovernor = resourceGovernor,
            neuralLane = neuralLane,
            executionGate = executionGate,
        )
    }

    @Provides
    @Singleton
    fun provideOcrHybridSearch(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
        neuralLane: NeuralExecutionLane,
        resolver: InstalledOcrPackResolver,
    ): OcrHybridSearch {
        val semantic =
            OcrSemanticSearch(
                vectors = storage.vectorIndexDao,
                semantic = storage.ocrSemanticDao,
                vectorRoot = context.noBackupFilesDir.resolve(StorageContract.VectorIndexDirectory),
                sessions = InstalledUser2QuerySessionFactory(resolver, neuralLane),
            )
        return OcrHybridSearch(
            ocr = storage.ocrDao,
            vectors = storage.vectorIndexDao,
            semantic = semantic,
        )
    }

    @Provides
    @Singleton
    fun provideVisualSimilaritySearch(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
    ): VisualSimilaritySearch =
        VisualSimilaritySearch(
            vectors = storage.vectorIndexDao,
            vectorRoot = context.noBackupFilesDir.resolve(StorageContract.VectorIndexDirectory),
        )

    @Provides
    @Singleton
    fun providePerceptualHashSearch(storage: CatalogStorage): PerceptualHashSearch =
        PerceptualHashSearch(storage.perceptualHashDao, storage.vectorIndexDao)

    @Provides
    @Singleton
    fun provideVisualTextSearch(
        similarity: VisualSimilaritySearch,
        neuralLane: NeuralExecutionLane,
        resolver: InstalledOcrPackResolver,
    ): VisualTextSearch {
        return VisualTextSearch(
            similarity = similarity,
            sessions =
                InstalledSiglip2TextQuerySessionFactory(
                    resolver,
                    neuralLane,
                ),
        )
    }

    @Provides
    @Singleton
    fun provideUnifiedSearch(
        storage: CatalogStorage,
        text: OcrHybridSearch,
        visual: VisualTextSearch,
    ): UnifiedSearch = UnifiedSearch(storage.vectorIndexDao, text, visual)
}
