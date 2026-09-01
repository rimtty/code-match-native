package jp.rimtty.codematch.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jp.rimtty.codematch.core.data.CodeMatchDatabase
import jp.rimtty.codematch.core.data.CodeMatchDatabaseFactory
import jp.rimtty.codematch.core.data.HistoryRepository
import jp.rimtty.codematch.core.data.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): CodeMatchDatabase = CodeMatchDatabaseFactory.create(context)

    @Provides
    @Singleton
    fun provideHistoryRepository(
        database: CodeMatchDatabase,
    ): HistoryRepository = HistoryRepository(database)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository = SettingsRepository(context)
}
