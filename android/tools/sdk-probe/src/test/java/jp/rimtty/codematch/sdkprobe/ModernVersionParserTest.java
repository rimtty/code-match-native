package jp.rimtty.codematch.sdkprobe;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModernVersionParserTest {
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
