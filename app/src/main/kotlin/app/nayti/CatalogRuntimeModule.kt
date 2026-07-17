package app.nayti

import android.content.Context
import app.nayti.indexer.CatalogRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CatalogRuntimeModule {
    @Provides
    @Singleton
    fun provideCatalogRuntime(@ApplicationContext context: Context): CatalogRuntime =
        CatalogRuntime.create(context).also(CatalogRuntime::start)
}
