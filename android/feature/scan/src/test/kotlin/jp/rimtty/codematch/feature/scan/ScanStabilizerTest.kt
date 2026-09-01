package jp.rimtty.codematch.feature.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStabilizerTest {
    @Test
    fun identicalCameraCode128CallbacksNeedLessThan1500Millis() {
        val stabilizer = ScanStabilizer()

        assertEquals(ScanStabilizationResult.Pending, stabilizer.submit("ABC", 0L))
        assertEquals(
            ScanStabilizationResult.Accepted("ABC"),
            stabilizer.submit("ABC\r\n", 1_499L),
        )
    }

    @Test
    fun exactly1500MillisRejectsTheOldCandidateAndStartsANewOne() {
        val stabilizer = ScanStabilizer()

        assertEquals(ScanStabilizationResult.Pending, stabilizer.submit("ABC", 0L))
        assertEquals(ScanStabilizationResult.Pending, stabilizer.submit("ABC", 1_500L))
        assertEquals("ABC", stabilizer.candidate)
        assertEquals(1_500L, stabilizer.candidateAt)
        assertEquals(ScanStabilizationResult.Accepted("ABC"), stabilizer.submit("ABC", 2_999L))
    }

    @Test
    fun differentValueReplacesCandidate() {
        val stabilizer = ScanStabilizer()

        assertEquals(ScanStabilizationResult.Pending, stabilizer.submit("ABC", 10L))
        assertEquals(ScanStabilizationResult.Pending, stabilizer.submit("XYZ", 20L))
        assertEquals("XYZ", stabilizer.candidate)
        assertEquals(ScanStabilizationResult.Accepted("XYZ"), stabilizer.submit("XYZ", 1_519L))
    }

    @Test
    fun acceptanceLockUsesStrict250MillisBoundary() {
        val lock = ScanAcceptanceLock()

        assertTrue(lock.acquire(1_000L))
        assertTrue(lock.isLocked(1_000L))
        assertTrue(lock.isLocked(1_249L))
        assertFalse(lock.isLocked(1_250L))
        assertTrue(lock.acquire(1_250L))
    }

    @Test
    fun acceptedValueIsLockedUntil250MillisThenNeedsANewPair() {
        val stabilizer = ScanStabilizer()

        assertEquals(ScanStabilizationResult.Pending, stabilizer.submit("ABC", 0L))
        assertEquals(ScanStabilizationResult.Accepted("ABC"), stabilizer.submit("ABC", 100L))
        assertEquals(ScanStabilizationResult.Locked, stabilizer.submit("ABC", 349L))
        assertEquals(ScanStabilizationResult.Pending, stabilizer.submit("ABC", 350L))
    }

    @Test
    fun emptyValuesAreRejectedWithoutCreatingCandidate() {
        val stabilizer = ScanStabilizer()

        assertEquals(ScanStabilizationResult.Rejected, stabilizer.submit("\r\n", 0L))
        assertEquals(null, stabilizer.candidate)
    }
}
