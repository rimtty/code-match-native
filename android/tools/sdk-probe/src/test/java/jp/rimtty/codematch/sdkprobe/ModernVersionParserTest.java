package jp.rimtty.codematch.sdkprobe;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModernVersionParserTest {
    @Test public void comparesBytesWithoutDisplayingThem() {
        assertEquals("取得コマンド：両ライブラリで一致", ModernVersionParser.compareCommands("{\"status\":0,\"data\":[-1,2]}", "{\"status\":0,\"data\":[255,2]}"));
        assertEquals("取得コマンド生成失敗／形式不正", ModernVersionParser.compareCommands("{\"status\":0,\"data\":[1.5]}", "{}"));
        assertEquals("取得コマンド：両ライブラリで相違", ModernVersionParser.compareCommands("{\"status\":0,\"data\":[1]}", "{\"status\":0,\"data\":[2]}"));
    }
    @Test public void classifiesNotificationWithoutReturningData() {
        assertEquals("通知種別：不完全（後続待ち）", ModernVersionParser.notificationKind("{\"notify_status\":0}"));
        assertEquals("通知種別：設定応答（内容は非表示）", ModernVersionParser.notificationKind("{\"notify_status\":1,\"notify_type\":1,\"notify_data\":[42]}"));
        assertEquals("通知種別：判定不可", ModernVersionParser.notificationKind("{\"notify_status\":\"1\"}"));
    }
    @Test public void displaysOnlyValidatedSuccessfulVersion() {
        assertEquals("比較：公式解析成功 TEST V1.0", ModernVersionParser.describe("{\"status\":0,\"data\":\"TEST V1.0\"}"));
        assertEquals("比較：公式解析ライブラリも拒否", ModernVersionParser.describe("{\"status\":1,\"data\":\"hidden\"}"));
    }
    @Test public void rejectsMalformedAndUnsafeOutput() {
        for (String value : new String[] {null, "bad", "{}", "[]", "{\"status\":\"0\"}", "{\"status\":0,\"data\":5}"}) {
            assertEquals("比較：応答形式不正", ModernVersionParser.describe(value));
        }
        assertEquals("比較：バージョン表示形式不正", ModernVersionParser.describe("{\"status\":0,\"data\":\"a\\n\"}"));
    }
}
