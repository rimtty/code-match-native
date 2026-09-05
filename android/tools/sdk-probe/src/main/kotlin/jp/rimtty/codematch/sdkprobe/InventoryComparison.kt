package jp.rimtty.codematch.sdkprobe

/** Process-memory-only witness. Never expose settings, identifiers, or hashes in UI/logs. */
internal class InventoryComparison {
    private var deviceId: String? = null
    private var baseline: Map<Pair<String, String>, Map<String, String>>? = null

    fun capture(id: String, items: List<Map<String, String>>): String {
        if (baseline != null) return "基準は既にあります。再取得する場合は先に破棄してください。"
        val parsed = validate(items) ?: return "基準取得失敗：設定形式不正"
        if (id.isBlank()) return "基準取得失敗：接続先不明"
        deviceId = id
        baseline = parsed
        return "比較基準をメモリ内に保持しました（設定値は非表示）。"
    }

    fun compare(id: String, items: List<Map<String, String>>): String {
        val expected = baseline ?: return "比較不可：基準がありません（プロセス終了時は消失します）。"
        if (deviceId != id) return "比較不可：基準とは別のスキャナーです。"
        val actual = validate(items) ?: return "比較不可：設定形式不正"
        return if (expected == actual) "一致：返却された全設定・全項目が基準と同一です。"
        else "不一致：返却された設定が基準と異なります。完全復元は未確認です。"
    }
    fun clear() { baseline = null; deviceId = null }

    private fun validate(items: List<Map<String, String>>): Map<Pair<String, String>, Map<String, String>>? {
        if (items.isEmpty() || items.size > 512) return null
        val result = linkedMapOf<Pair<String, String>, Map<String, String>>()
        for (item in items) {
            if (item.size > 32 || item.any { it.key.length > 256 || it.value.length > 2048 }) return null
            val area = item["area"]?.takeIf(String::isNotBlank) ?: return null
            val name = item["name"]?.takeIf(String::isNotBlank) ?: return null
            if (!item.containsKey("value") || result.put(area to name, item.toMap()) != null) return null
        }
        return result
    }
}

/** Survives activity switching/recreation only while this diagnostic process remains alive. */
internal object ProbeInventoryWitness { val comparison = InventoryComparison() }
