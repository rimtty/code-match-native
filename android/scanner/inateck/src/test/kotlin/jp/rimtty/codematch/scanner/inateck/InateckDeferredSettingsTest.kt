package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.api.ScanFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckDeferredSettingsTest {
    @Test fun retainsLatestIntentsInSubmissionOrderAndDrainsOnce() {
        val queue = InateckDeferredSettings()
        val background = InateckDeferredSettings.Action.ApplicationActive(false)
        val stopped = InateckDeferredSettings.Action.Format(null)
        queue.offer(InateckDeferredSettings.Action.Format(ScanFormat.QR))
        queue.offer(background)
        queue.offer(stopped)
        assertEquals(listOf(background, stopped), queue.drain())
        assertTrue(queue.drain().isEmpty())
    }

    @Test fun repeatedRequestsRemainBoundedAndCloseDropsThem() {
        val queue = InateckDeferredSettings()
        repeat(100) {
            queue.offer(InateckDeferredSettings.Action.ApplicationActive(it % 2 == 0))
            queue.offer(InateckDeferredSettings.Action.Format(null))
        }
        assertEquals(2, queue.drain().size)
        queue.offer(InateckDeferredSettings.Action.Format(null))
        queue.clear()
        assertTrue(queue.drain().isEmpty())
    }
}
