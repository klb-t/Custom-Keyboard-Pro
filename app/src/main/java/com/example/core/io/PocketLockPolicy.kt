package com.example.core.io

/** Independent lock domains. Android decides which physical events reach the app. */
enum class LockKeyDomain { VOLUME, MEDIA, NAVIGATION, OTHER, SYSTEM }

data class PocketLockPolicy(
    val blockTouch: Boolean,
    val blockVolume: Boolean,
    val blockMedia: Boolean,
    val blockNavigation: Boolean,
    val blockOther: Boolean
) {
    fun blocks(domain: LockKeyDomain): Boolean = when (domain) {
        LockKeyDomain.VOLUME -> blockVolume
        LockKeyDomain.MEDIA -> blockMedia
        LockKeyDomain.NAVIGATION -> blockNavigation
        LockKeyDomain.OTHER -> blockOther
        // Power, Home, Recents and system security controls are never promised.
        LockKeyDomain.SYSTEM -> false
    }

    val needsKeys: Boolean get() = blockVolume || blockMedia || blockNavigation || blockOther

    /** A touch-blocking lock always retains a local way out, even in a bad profile. */
    fun effectiveUnlockFingers(requested: Int, hasKeyEscape: Boolean): Int = when {
        !blockTouch -> 0
        requested > 0 -> requested
        !hasKeyEscape -> 2
        else -> 0
    }
}

/**
 * Once a down event has been consumed, its repeats and up event belong to that same
 * consumer, even if the down event unlocked the lock or settings changed meanwhile.
 */
class ConsumedKeyPresses {
    private val keys = mutableSetOf<Int>()
    val pending: Boolean get() = keys.isNotEmpty()
    fun contains(code: Int): Boolean = code in keys
    fun down(code: Int) { keys += code }
    fun up(code: Int): Boolean = keys.remove(code)
    fun clear() = keys.clear()
}
