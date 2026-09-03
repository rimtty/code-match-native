package jp.rimtty.codematch.scanner.inateck

/** Narrow seam that keeps notification accumulation independent of JNA/Android. */
fun interface InateckNotificationNativeParser {
    /** Returns the official parser's JSON result, or null when native parsing fails. */
    fun parse(data: ByteArray): String?
}

/** Adapter from the pure notification accumulator to the official JNA library. */
internal class InateckJnaNotificationNativeParser(
    private val api: InateckScannerCmdJna.Api = InateckScannerCmdJna.load(),
) : InateckNotificationNativeParser {
    override fun parse(data: ByteArray): String? =
        InateckScannerCmdJna.notifyDataResult(api, data)
}
