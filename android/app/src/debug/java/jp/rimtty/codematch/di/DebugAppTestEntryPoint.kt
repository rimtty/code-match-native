package jp.rimtty.codematch.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jp.rimtty.codematch.core.data.HistoryRepository
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.scanner.api.ExternalScanner

/**
 * Debug-only access to the app's real graph for end-to-end instrumentation.
 *
 * The entry point deliberately exposes the platform-neutral scanner contract
 * rather than a Fake type. The debug test can arrange deterministic callbacks
 * while the release variant has no entry point, Fake dependency, or test hook.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugAppTestEntryPoint {
    fun externalScanner(): ExternalScanner

    fun historyRepository(): HistoryRepository

    fun settingsRepository(): SettingsRepository
}
