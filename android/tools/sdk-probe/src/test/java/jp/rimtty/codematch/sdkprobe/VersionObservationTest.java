package jp.rimtty.codematch.sdkprobe;

import com.inateck.scanner.ble.BleParseEvent;
import com.inateck.scanner.ble.BleTask;
import com.inateck.scanner.ble.BleTaskMethod;
import org.junit.Test;
import static org.junit.Assert.*;

public class VersionObservationTest {
    @Test public void forwardsOriginalInputAndResultExactlyOnce() {
        var task = new BleTask("service", "characteristic", new byte[0], BleTaskMethod.Write);
        var bytes = new byte[] {1, 2, 3};
        var expected = new BleParseEvent.Success(bytes);
        int[] calls = {0};
        task.setParseHandler(input -> { calls[0]++; assertSame(bytes, input); return expected; });
        var observer = new VersionObservation();
        assertTrue(observer.attachTask(task));
        assertEquals("SDKへの応答通知なし", observer.summary());
        assertSame(expected, task.getParseHandler().invoke(bytes));
        assertEquals(1, calls[0]);
        assertEquals("応答あり／SDKが受信完了扱い", observer.summary());
    }

    @Test public void distinguishesNullResponseAndMissingTask() {
        var observer = new VersionObservation();
        assertFalse(observer.attachTask(null));
        assertEquals("観測フック未接続", observer.summary());
        var task = new BleTask("service", "characteristic", new byte[0], BleTaskMethod.Write);
        var expected = new BleParseEvent.Error(new IllegalStateException("synthetic"));
        task.setParseHandler(input -> expected);
        assertTrue(observer.attachTask(task));
        assertSame(expected, task.getParseHandler().invoke(null));
        assertEquals("SDKへnull応答", observer.summary());
    }
}
