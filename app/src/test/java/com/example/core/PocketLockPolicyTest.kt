package com.example.core

import com.example.core.io.ConsumedKeyPresses
import com.example.core.io.LockKeyDomain
import com.example.core.io.PocketLockPolicy
import org.junit.Assert.*
import org.junit.Test

class PocketLockPolicyTest {
    @Test fun `key domains are independent and no profile claims system power or home`() {
        for (mask in 0 until 16) {
            val policy = PocketLockPolicy(true, mask and 1 != 0, mask and 2 != 0,
                mask and 4 != 0, mask and 8 != 0)
            assertEquals(mask and 1 != 0, policy.blocks(LockKeyDomain.VOLUME))
            assertEquals(mask and 2 != 0, policy.blocks(LockKeyDomain.MEDIA))
            assertEquals(mask and 4 != 0, policy.blocks(LockKeyDomain.NAVIGATION))
            assertEquals(mask and 8 != 0, policy.blocks(LockKeyDomain.OTHER))
            assertFalse(policy.blocks(LockKeyDomain.SYSTEM))
        }
    }

    @Test fun `a profile cannot trap touch input after disabling both exits`() {
        val blocking = PocketLockPolicy(true, false, false, false, false)
        assertEquals(2, blocking.effectiveUnlockFingers(0, hasKeyEscape = false))
        assertEquals(0, blocking.effectiveUnlockFingers(0, hasKeyEscape = true))
        assertEquals(3, blocking.effectiveUnlockFingers(3, hasKeyEscape = false))
        assertEquals(0, blocking.copy(blockTouch = false).effectiveUnlockFingers(3, false))
    }

    @Test fun `unlocking on a key down still consumes that keys release`() {
        val presses = ConsumedKeyPresses()
        presses.down(24)
        assertTrue(presses.pending)
        // Clearing the lock itself must not clear this independent ledger.
        assertFalse(presses.up(25))
        assertTrue(presses.contains(24))
        assertTrue(presses.up(24))
        assertFalse(presses.pending)
        assertFalse(presses.up(24))
    }
}
