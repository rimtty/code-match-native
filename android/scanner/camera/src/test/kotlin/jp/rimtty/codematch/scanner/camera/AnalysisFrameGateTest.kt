package jp.rimtty.codematch.scanner.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisFrameGateTest {
    @Test
    fun `only one frame can be in flight`() {
        val gate = AnalysisFrameGate()

        assertTrue(gate.tryAcquire())
        assertTrue(gate.isBusy)
        assertEquals(false, gate.tryAcquire())

        gate.release()

        assertEquals(false, gate.isBusy)
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `concurrent frame delivery admits one frame`() {
        val gate = AnalysisFrameGate()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        var acquired = 0
        val lock = Any()

        repeat(8) {
            executor.execute {
                start.await()
                if (gate.tryAcquire()) {
                    synchronized(lock) { acquired += 1 }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, acquired)
        gate.release()
        executor.shutdownNow()
    }
}
