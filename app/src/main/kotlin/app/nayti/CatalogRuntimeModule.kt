package app.nayti

import android.content.Context
import app.nayti.indexer.CatalogRuntime
import app.nayti.indexer.ModelPackInstallCoordinator
import app.nayti.indexer.ModelPackRuntime
import app.nayti.indexer.NeuralExecutionLane
import app.nayti.indexer.InstalledOcrPackResolver
import app.nayti.indexer.IndexResourceGovernor
import app.nayti.indexer.OcrIndexingRuntime
import app.nayti.indexer.InstalledUser2QuerySessionFactory
import app.nayti.indexer.InstalledSiglip2TextQuerySessionFactory
import app.nayti.indexer.OcrHybridSearch
import app.nayti.indexer.OcrSemanticSearch
import app.nayti.indexer.PerceptualHashSearch
import app.nayti.indexer.UnifiedSearch
import app.nayti.indexer.VisualSimilaritySearch
import app.nayti.indexer.VisualTextSearch
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
    fun provideOcrIndexingRuntime(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
        resourceGovernor: IndexResourceGovernor,
        neuralLane: NeuralExecutionLane,
    ): OcrIndexingRuntime {
        val root = context.noBackupFilesDir.toPath().resolve(StorageContract.ModelPackDirectory)
        val mediaStore = AndroidMediaStoreGateway(context)
        return OcrIndexingRuntime(
            storage = storage,
            packResolver = InstalledOcrPackResolver(storage.modelPackDao, root),
            decoder = BoundedMediaDecoder(context.contentResolver, mediaStore),
            vectorRoot = context.noBackupFilesDir.resolve(StorageContract.VectorIndexDirectory),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            resourceGovernor = resourceGovernor,
            neuralLane = neuralLane,
        )
    }

    @Provides
    @Singleton
    fun provideOcrHybridSearch(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
        neuralLane: NeuralExecutionLane,
    ): OcrHybridSearch {
        val modelRoot = context.noBackupFilesDir.toPath().resolve(StorageContract.ModelPackDirectory)
        val resolver = InstalledOcrPackResolver(storage.modelPackDao, modelRoot)
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
        PerceptualHashSearch(storage.perceptualHashDao)

    @Provides
    @Singleton
    fun provideVisualTextSearch(
        @ApplicationContext context: Context,
        storage: CatalogStorage,
        similarity: VisualSimilaritySearch,
        neuralLane: NeuralExecutionLane,
    ): VisualTextSearch {
        val modelRoot = context.noBackupFilesDir.toPath().resolve(StorageContract.ModelPackDirectory)
        return VisualTextSearch(
            similarity = similarity,
            sessions =
                InstalledSiglip2TextQuerySessionFactory(
                    InstalledOcrPackResolver(storage.modelPackDao, modelRoot),
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
