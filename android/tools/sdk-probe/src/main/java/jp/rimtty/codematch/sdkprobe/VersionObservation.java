package jp.rimtty.codematch.sdkprobe;

import com.inateck.scanner.ble.BleMessager;
import com.inateck.scanner.ble.BleParseEvent;
import com.inateck.scanner.ble.BleTask;

/**
 * Probe-only transparent observer of SDK 2.0.0's existing task, not a replacement
 * parser. Never retains response bytes or exceptions. No reflection/private field
 * access: the Kotlin-internal accessor is public in the vendor JAR's Java ABI.
 * This implementation is intentionally not suitable for a production adapter.
 */
public final class VersionObservation {
    private volatile boolean attached;
    private volatile boolean replySeen;
    private volatile boolean nonNullReply;
    private volatile boolean parserCompleted;

    public boolean attach(BleMessager messager) {
        return attachTask(messager.getTaskManager$ble_release().getRunningTask());
    }

    boolean attachTask(BleTask task) {
        if (task == null || task.getParseHandler() == null) return false;
        var original = task.getParseHandler();
        task.setParseHandler(bytes -> {
            replySeen = true;
            nonNullReply = bytes != null;
            var result = original.invoke(bytes);
            parserCompleted = result instanceof BleParseEvent.Success;
            return result;
        });
        attached = true;
        return true;
    }

    public String summary() {
        if (!attached) return "観測フック未接続";
        if (!replySeen) return "SDKへの応答通知なし";
        if (!nonNullReply) return "SDKへnull応答";
        if (parserCompleted) return "応答あり／SDKが受信完了扱い";
        return "応答あり／SDK受信処理未完了";
    }
}
