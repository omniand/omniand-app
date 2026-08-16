package dev.omniand.launcher.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSendReducerTest {
    @Test fun multipartCompletesOnlyAfterEveryPart() {
        val (first, firstOutcome) = SmsSendReducer.record(SmsSendProgress(2), 0, true)
        assertEquals(SmsSendOutcome.PENDING, firstOutcome)
        val (complete, finalOutcome) = SmsSendReducer.record(first, 1, true)
        assertEquals(SmsSendOutcome.SENT, finalOutcome)
        assertEquals(setOf(0, 1), complete.completedParts)
    }

    @Test fun anyFailedPartMakesWholeMessageFailed() {
        val (first, _) = SmsSendReducer.record(SmsSendProgress(2), 0, false)
        val (complete, outcome) = SmsSendReducer.record(first, 1, true)
        assertTrue(complete.failed)
        assertEquals(SmsSendOutcome.FAILED, outcome)
    }

    @Test fun duplicateCallbackDoesNotAdvanceState() {
        val (first, _) = SmsSendReducer.record(SmsSendProgress(2), 0, true)
        val (unchanged, outcome) = SmsSendReducer.record(first, 0, false)
        assertEquals(first, unchanged)
        assertFalse(unchanged.failed)
        assertEquals(SmsSendOutcome.DUPLICATE, outcome)
    }
}
