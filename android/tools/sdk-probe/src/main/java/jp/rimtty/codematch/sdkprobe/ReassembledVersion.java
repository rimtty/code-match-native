package jp.rimtty.codematch.sdkprobe;

import com.google.gson.JsonParser;
import com.inateck.scanner.ble.*;
import com.sun.jna.Native;
import java.util.function.Function;

/** Probe-only bounded reconstruction. No new commands; original SDK completion stays in use. */
public final class ReassembledVersion {
    private final Function<byte[], String> notifyParser;
    private final Function<byte[], Boolean> versionParser;
    private byte[] pending = new byte[0];
    private boolean cancelled;
    private boolean incomplete;
    private String stage = "再構成：待機中";

    public ReassembledVersion() {
        this(data -> Native.load("inateck_scanner_cmd", ModernVersionParser.Api.class)
                    .inateck_scanner_cmd_notify_data_result(data, (long) data.length),
             data -> validVersion(CmdJNA.Companion.getInstance().get_version_result(data, data.length)));
    }
    ReassembledVersion(Function<byte[], String> notifyParser, Function<byte[], Boolean> versionParser) {
        this.notifyParser = notifyParser;
        this.versionParser = versionParser;
    }
    public boolean attach(BleMessager messager) {
        var task = messager.getTaskManager$ble_release().getRunningTask();
        if (task == null || task.getParseHandler() == null) { stage = "再構成：タスクなし"; return false; }
        var original = task.getParseHandler();
        task.setParseHandler(bytes -> {
            var result = accept(bytes);
            if (result instanceof BleParseEvent.Success success) return original.invoke(success.getData());
            return result;
        });
        return true;
    }
    synchronized BleParseEvent accept(byte[] bytes) {
        if (cancelled) return error("再構成：停止済み");
        if (bytes == null || bytes.length == 0 || bytes.length > 8192 || pending.length > 8192 - bytes.length) {
            return error("再構成：入力不正／上限超過");
        }
        var joined = java.util.Arrays.copyOf(pending, pending.length + bytes.length);
        System.arraycopy(bytes, 0, joined, pending.length, bytes.length);
        try {
            var json = notifyParser.apply(joined.clone());
            if (json == null || json.length() > 65536) return error("再構成：通知形式不正");
            var root = JsonParser.parseString(json).getAsJsonObject();
            var status = root.getAsJsonPrimitive("notify_status");
            if (!status.isNumber()) return error("再構成：通知形式不正");
            var array = root.getAsJsonArray("notify_data");
            if (array == null || array.size() > 8192) return error("再構成：通知形式不正");
            var data = new byte[array.size()];
            for (int i = 0; i < data.length; i++) {
                var item = array.get(i).getAsJsonPrimitive();
                if (!item.isNumber() || !item.getAsString().matches("0|[1-9][0-9]*")) return error("再構成：通知形式不正");
                int value = item.getAsBigInteger().intValueExact();
                if (value < 0 || value > 255) return error("再構成：通知形式不正");
                data[i] = (byte) value;
            }
            if (status.getAsString().equals("0")) {
                pending = data; // Official contract: keep native-returned prefix, not raw concatenation.
                incomplete = true;
                stage = "再構成：後続を待機中";
                return BleParseEvent.Loading.INSTANCE;
            }
            pending = new byte[0];
            if (!status.getAsString().equals("1")) return error("再構成：通知解析エラー");
            // Notification type varies across vendor generations. Never accept its text directly:
            // only the official firmware-result parser can validate a candidate.
            byte[] complete = versionParser.apply(joined.clone()) ? joined
                : versionParser.apply(data.clone()) ? data : null;
            if (complete == null) return error("再構成：完了したが版解析は拒否");
            stage = incomplete ? "再構成：分割結合して版解析成功" : "再構成：単一応答で版解析成功";
            cancelled = true;
            return new BleParseEvent.Success(complete);
        } catch (RuntimeException | LinkageError ignored) {
            return error("再構成：解析呼び出し失敗");
        }
    }
    private BleParseEvent error(String stage) {
        this.stage = stage;
        cancel();
        return new BleParseEvent.Error(new IllegalStateException("Probe response unavailable"));
    }
    static boolean validVersion(String json) {
        return ModernVersionParser.describe(json).startsWith("比較：公式解析成功 ");
    }
    public synchronized void cancel() { pending = new byte[0]; cancelled = true; }
    public synchronized String summary() { return stage; }
}
