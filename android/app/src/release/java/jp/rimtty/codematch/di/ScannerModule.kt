package jp.rimtty.codematch.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import jp.rimtty.codematch.scanner.UnavailableExternalScanner
import jp.rimtty.codematch.scanner.api.ExternalScanner

@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {
    @Provides
    @Singleton
    fun provideExternalScanner(): ExternalScanner = UnavailableExternalScanner()
}
