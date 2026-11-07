package com.benton.izukijs.controller.hid

import org.junit.Assert.*
import org.junit.Test

class HidLinkStateTest {
    private fun tap(duration: Int = 30) = HidProtocol.tap(100, 200, duration)

    @Test fun oldSessionCannotEnqueueOrCompleteNewWrite() {
        val link = HidLinkState()
        val old = link.reset(0)
        assertTrue(link.enqueue(old, listOf(tap()), 0))
        val write = link.take(0)!!
        val current = link.reset(10)
        assertFalse(link.enqueue(old, listOf(tap()), 10))
        assertTrue(link.enqueue(current, listOf(tap()), 10))
        val fresh = link.take(10)!!
        assertFalse(link.complete(old, write.id, true))
        assertEquals(fresh.id, link.inFlight!!.id)
    }

    @Test fun failedWriteDropsQueueAndNeverReplays() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        link.enqueue(epoch, listOf(tap(), tap()), 0)
        val write = link.take(0)!!
        assertTrue(link.complete(epoch, write.id, false))
        assertEquals("write_failed", link.check(1))
        assertNull(link.take(1))
        assertFalse(link.enqueue(epoch, listOf(tap()), 1))
        link.reset(2)
        assertNull(link.take(2))
    }

    @Test fun lostCallbackTimesOutAndCancelsQueuedInput() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        link.enqueue(epoch, listOf(tap(), tap()), 0)
        link.take(0)
        assertNull(link.check(1999))
        assertEquals("write_timeout", link.check(2000))
        assertNull(link.inFlight)
        assertNull(link.take(2001))
    }

    @Test fun expiredQueueIsNotSentAfterDelayedScheduler() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        link.enqueue(epoch, listOf(tap()), 0)
        assertNull(link.take(2000))
        assertEquals("queued_command_expired", link.fault)
    }

    @Test fun batchAdmissionHasNoPartialKeyDownOnOverflow() {
        val link = HidLinkState(capacity = 2)
        val epoch = link.reset(0)
        link.enqueue(epoch, listOf(tap()), 0)
        assertFalse(link.enqueue(epoch, listOf(HidProtocol.key(4, 0, true), HidProtocol.key(4, 0, false)), 0))
        val write = link.take(0)!!
        link.complete(epoch, write.id, true)
        assertNull(link.take(0))
    }

    @Test fun payloadCannotBeMutatedAfterAdmission() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val frame = tap()
        link.enqueue(epoch, listOf(frame), 0)
        frame[0] = 0
        assertEquals(HidProtocol.CMD_TAP, HidProtocol.kind(link.take(0)!!.payload))
    }

    @Test fun heartbeatTimeoutRevokesSessionUntilReset() {
        val link = HidLinkState()
        link.reset(0)
        assertTrue(link.shouldProbe(3000, true))
        link.probeSent(3000, true)
        assertFalse(link.shouldProbe(6000, true))
        assertEquals("heartbeat_timeout", link.check(8000))
        link.pong(8001)
        assertEquals("heartbeat_timeout", link.check(8001))
        link.reset(8002)
        assertNull(link.check(8002))
    }

    @Test fun validPongAllowsNextProbeAfterInterval() {
        val link = HidLinkState()
        link.reset(0)
        link.probeSent(3000, true)
        link.pong(3100)
        assertNull(link.check(8000))
        assertFalse(link.shouldProbe(6099, true))
        assertTrue(link.shouldProbe(6100, true))
    }

    @Test fun legacyLongHoldDefersHeartbeatButNotWriteTimeout() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        link.enqueue(epoch, listOf(tap(60_000)), 0)
        val write = link.take(0)!!
        assertFalse(link.shouldProbe(3000, false))
        assertEquals("write_timeout", link.check(2000))
        assertFalse(link.complete(epoch, write.id, true))
    }

    @Test fun v3CanProbeDuringLongActionWhileLegacyWaits() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        link.enqueue(epoch, listOf(tap(60_000)), 0)
        val write = link.take(0)!!
        link.complete(epoch, write.id, true)
        assertNull(link.check(5000))
        assertFalse(link.shouldProbe(5000, false))
        assertTrue(link.shouldProbe(5000, true))
        assertTrue(link.shouldProbe(60_101, false))
    }

    @Test fun legacyGestureBudgetAccumulatesAllPointsAndFrames() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val frame = HidProtocol.gesture(0, List(42) { HidProtocol.GestureSample(10, 10, 65535) })
        link.enqueue(epoch, listOf(frame, frame), 0)
        repeat(2) { val w = link.take(0)!!; link.complete(epoch, w.id, true) }
        assertEquals(2 * (42L * 65535 + 250), link.busyUntil)
        assertFalse(link.shouldProbe(100_000, false))
    }

    @Test fun repeatControlIsNotMistakenForSixtyFiveSecondHold() {
        assertTrue(HidProtocol.executionBudgetMs(tap(65534)) < 1000)
        assertTrue(HidProtocol.executionBudgetMs(tap(65535)) < 1000)
        assertTrue(HidProtocol.executionBudgetMs(tap(65533)) > 65_000)
    }
}
