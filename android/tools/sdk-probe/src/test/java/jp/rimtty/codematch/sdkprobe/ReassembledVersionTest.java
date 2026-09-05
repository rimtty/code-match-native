package jp.rimtty.codematch.sdkprobe;
import com.inateck.scanner.ble.BleParseEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReassembledVersionTest {
    @Test public void keepsNativePrefixAndWaitsBeforeVersionParsing() {
        int[] calls = {0};
        var probe = new ReassembledVersion(bytes -> {
            if (++calls[0] == 1) return "{\"notify_status\":0,\"notify_data\":[9]}";
            assertArrayEquals(new byte[]{9,2}, bytes);
            return "{\"notify_status\":1,\"notify_data\":[7]}";
        }, bytes -> bytes.length == 1 && bytes[0] == 7);
        assertSame(BleParseEvent.Loading.INSTANCE, probe.accept(new byte[]{1}));
        var result = probe.accept(new byte[]{2});
        assertTrue(result instanceof BleParseEvent.Success);
        assertArrayEquals(new byte[]{7}, ((BleParseEvent.Success) result).getData());
        assertEquals("再構成：分割結合して版解析成功", probe.summary());
        assertTrue(probe.accept(new byte[]{3}) instanceof BleParseEvent.Error);
    }
    @Test public void rejectsOversizeMalformedAndCancelledInput() {
        var probe = new ReassembledVersion(bytes -> "{}", bytes -> true);
        assertTrue(probe.accept(new byte[]{1}) instanceof BleParseEvent.Error);
        assertTrue(new ReassembledVersion(bytes -> "{}", bytes -> true).accept(new byte[8193]) instanceof BleParseEvent.Error);
        var cancelled = new ReassembledVersion(bytes -> { fail("must not parse"); return ""; }, bytes -> true);
        cancelled.cancel();
        assertTrue(cancelled.accept(new byte[]{1}) instanceof BleParseEvent.Error);
    }
    @Test public void completeNotificationIsNotEnoughWithoutVersionValidation() {
        var probe = new ReassembledVersion(bytes -> "{\"notify_status\":1,\"notify_data\":[7]}", bytes -> false);
        assertTrue(probe.accept(new byte[]{1}) instanceof BleParseEvent.Error);
        assertEquals("再構成：完了したが版解析は拒否", probe.summary());
    }
}
