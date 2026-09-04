package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScanFormat

/** Latest intent per kind, ordered by its most recent submission. */
internal class InateckDeferredSettings {
    sealed interface Action {
        data class Format(val value: ScanFormat?) : Action
        data class ApplicationActive(val value: Boolean) : Action
    }

    private val pending = mutableListOf<Action>()

    fun offer(action: Action) {
        pending.removeAll { it::class == action::class }
        pending.add(action)
    }

    fun drain(): List<Action> = pending.toList().also { pending.clear() }
    fun clear() = pending.clear()
}
