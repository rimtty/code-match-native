package jp.rimtty.codematch.sdkprobe;

import com.google.gson.JsonParser;
import com.sun.jna.Library;
import com.sun.jna.Native;

/** Read-only comparison using the separately shipped official command library. */
public final class ModernVersionParser {
    public interface Api extends Library {
        String inateck_scanner_cmd_software_result(byte[] data, long length);
    }
    private static Api api;

    public static synchronized String inspect(byte[] response) {
        if (response == null || response.length == 0 || response.length > 8192) return "比較：入力なし／範囲外";
        try {
            if (api == null) api = Native.load("inateck_scanner_cmd", Api.class);
            // uintptr_t is 64-bit on the probe's arm64-only ABI. Never mutate SDK input.
            return describe(api.inateck_scanner_cmd_software_result(response.clone(), (long) response.length));
        } catch (RuntimeException | LinkageError ignored) {
            return "比較：解析ライブラリ呼び出し失敗";
        }
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
