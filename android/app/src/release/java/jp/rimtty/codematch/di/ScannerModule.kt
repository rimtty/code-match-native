package jp.rimtty.codematch.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.inateck.InateckExternalScanner
import javax.inject.Singleton

/**
 * Release binds the official Inateck SDK adapter. Release builds are
 * side-loaded for personal use only (no store distribution); the SDK
 * binaries are fetched locally by `scripts/setup-inateck-sdk.sh`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {
    @Provides
    @Singleton
    fun provideExternalScanner(
        @ApplicationContext context: Context,
    ): ExternalScanner = InateckExternalScanner.create(context)
}
