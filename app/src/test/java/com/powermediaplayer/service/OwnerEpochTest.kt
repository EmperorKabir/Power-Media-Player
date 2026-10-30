package com.powermediaplayer.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CLEAR_TOP replacement order: new instance claims, then the old one tries to disconnect. */
class OwnerEpochTest {
    @Test fun soleOwnerIsCurrent() {
        val o = OwnerEpoch()
        val a = o.claim()
        assertTrue(o.isCurrent(a))
    }

    @Test fun supersededOwnerIsNotCurrent() {
        val o = OwnerEpoch()
        val old = o.claim()      // first MainActivity instance
        val new = o.claim()      // CLEAR_TOP replacement instance (onCreate runs before old.onDestroy)
        assertFalse("old instance must not be able to tear the connection down", o.isCurrent(old))
        assertTrue(o.isCurrent(new))
    }

    /** The exact rule PlaybackConnection.disconnect(epoch) applies. */
    @Test fun disconnectRule() {
        val o = OwnerEpoch()
        val old = o.claim()
        assertTrue("sole owner may disconnect", o.mayDisconnect(old))
        assertTrue("no-epoch caller always may", o.mayDisconnect(-1))
        val new = o.claim()
        assertFalse("superseded owner may not", o.mayDisconnect(old))
        assertTrue("current owner may", o.mayDisconnect(new))
    }

    @Test fun reconnectByTheSameOwnerMovesTheEpochForward() {
        val o = OwnerEpoch()
        val onCreate = o.claim()
        val onStart = o.claim()  // MainActivity.onStart calls connect() again (idempotent path)
        assertFalse(o.isCurrent(onCreate))
        assertTrue(o.isCurrent(onStart))
    }
}
