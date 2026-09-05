package jp.rimtty.codematch.sdkprobe;

import com.google.gson.JsonParser;
import com.sun.jna.Library;
import com.sun.jna.Native;

/** Read-only comparison using the separately shipped official command library. */
public final class ModernVersionParser {
    public interface Api extends Library {
        String inateck_scanner_cmd_software_result(byte[] data, long length);
        String inateck_scanner_cmd_software_version();
        String inateck_scanner_cmd_notify_data_result(byte[] data, long length);
    }
    private static Api api;

    public static synchronized String inspect(byte[] response) {
        if (response == null || response.length == 0 || response.length > 8192) return "比較：入力なし／範囲外";
        try {
            if (api == null) api = Native.load("inateck_scanner_cmd", Api.class);
            // uintptr_t is 64-bit on the probe's arm64-only ABI. Never mutate SDK input.
            return describe(api.inateck_scanner_cmd_software_result(response.clone(), (long) response.length))
                + "\n" + notificationKind(api.inateck_scanner_cmd_notify_data_result(response.clone(), (long) response.length));
        } catch (RuntimeException | LinkageError ignored) {
            return "比較：解析ライブラリ呼び出し失敗";
        }
    }

    public static synchronized String commandComparison() {
        try {
            if (api == null) api = Native.load("inateck_scanner_cmd", Api.class);
            return compareCommands(com.inateck.scanner.ble.CmdJNA.Companion.getInstance().get_version_cmd(),
                api.inateck_scanner_cmd_software_version());
        } catch (RuntimeException | LinkageError ignored) {
            return "取得コマンド比較不可";
        }
    }

    static String compareCommands(String oldJson, String newJson) {
        var oldBytes = commandBytes(oldJson);
        var newBytes = commandBytes(newJson);
        if (oldBytes == null || newBytes == null) return "取得コマンド生成失敗／形式不正";
        return java.util.Arrays.equals(oldBytes, newBytes) ? "取得コマンド：両ライブラリで一致" : "取得コマンド：両ライブラリで相違";
    }

    private static byte[] commandBytes(String json) {
        try {
            if (json == null || json.length() > 4096) return null;
            var root = JsonParser.parseString(json).getAsJsonObject();
            var status = root.getAsJsonPrimitive("status");
            if (!status.isNumber() || !"0".equals(status.getAsString())) return null;
            var data = root.getAsJsonArray("data");
            if (data.isEmpty() || data.size() > 1024) return null;
            var bytes = new byte[data.size()];
            for (int i = 0; i < bytes.length; i++) {
                var item = data.get(i).getAsJsonPrimitive();
                if (!item.isNumber() || !item.getAsString().matches("-?(0|[1-9][0-9]*)")) return null;
                int value = item.getAsBigInteger().intValueExact();
                if (value < -128 || value > 255) return null;
                bytes[i] = (byte) value;
            }
            return bytes;
        } catch (RuntimeException ignored) { return null; }
    }

    static String notificationKind(String json) {
        try {
            if (json == null || json.length() > 65536) return "通知種別：判定不可";
            var root = JsonParser.parseString(json).getAsJsonObject();
            var status = root.getAsJsonPrimitive("notify_status");
            if (status == null || !status.isNumber()) return "通知種別：判定不可";
            if ("0".equals(status.getAsString())) return "通知種別：不完全（後続待ち）";
            if ("2".equals(status.getAsString())) return "通知種別：フレームエラー";
            if (!"1".equals(status.getAsString())) return "通知種別：判定不可";
            var type = root.getAsJsonPrimitive("notify_type");
            if (type == null || !type.isNumber()) return "通知種別：判定不可";
            return switch (type.getAsString()) {
                case "0" -> "通知種別：読取データ（内容は非表示）";
                case "1" -> "通知種別：設定応答（内容は非表示）";
                default -> "通知種別：判定不可";
            };
        } catch (RuntimeException ignored) { return "通知種別：判定不可"; }
    }

    static String describe(String json) {
        if (json == null || json.length() > 1024) return "比較：応答形式不正";
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            var status = root.getAsJsonPrimitive("status");
            if (status == null || !status.isNumber()) return "比較：応答形式不正";
            if (!status.getAsString().equals("0")) return "比較：公式解析ライブラリも拒否";
            var data = root.getAsJsonPrimitive("data");
            if (data == null || !data.isString()) return "比較：応答形式不正";
            var value = data.getAsString();
            if (value.isBlank() || value.length() > 128 || !value.chars().allMatch(c -> c >= 32 && c <= 126)) {
                return "比較：バージョン表示形式不正";
            }
            return "比較：公式解析成功 " + value;
        } catch (RuntimeException ignored) {
            return "比較：応答形式不正";
        }
    }
}
