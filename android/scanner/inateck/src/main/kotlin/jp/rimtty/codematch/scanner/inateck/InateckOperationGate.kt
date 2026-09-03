package jp.rimtty.codematch.scanner.inateck

/** One-operation gate whose tokens become permanently stale after invalidation. */
internal class InateckOperationGate {
    private var generation = 0L
    private var active: Long? = null

    fun begin(): Long? {
        if (active != null) return null
        return (++generation).also { active = it }
    }

    fun isCurrent(token: Long): Boolean = active == token

    fun finish(token: Long) {
        if (active == token) active = null
    }

    fun invalidate() {
        generation++
        active = null
    }
}
