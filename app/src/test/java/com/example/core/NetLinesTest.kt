package com.example.core

import com.example.core.matrix.NetLines
import com.example.core.matrix.NetMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The network's door: what gets in, and — mostly — what does not. */
class NetLinesTest {

    private val token = "s3cret-token"

    @Test
    fun `values on a channel get in with the right token`() {
        val m = NetLines.parse("$token io steer 0.25 -1", token, allowCommands = false) as NetMessage.Values
        assertEquals("steer", m.channel)
        assertArrayEquals(floatArrayOf(0.25f, -1f), m.values, 0f)
        val bare = NetLines.parse("$token io doorbell", token, allowCommands = false) as NetMessage.Values
        assertEquals(0, bare.values.size)
    }

    @Test
    fun `a wrong, missing or short token is silence`() {
        assertNull(NetLines.parse("wrong-token io steer 1", token, false))
        assertNull(NetLines.parse("io steer 1", token, false))
        assertNull(NetLines.parse("short io steer 1", "short", false))
        assertNull(NetLines.parse("$token io steer 1", "", false))
        assertFalse(NetLines.tokenUsable("has space in it"))
        assertTrue(NetLines.tokenUsable(token))
    }

    @Test
    fun `commands get in only when allowed`() {
        assertNull(NetLines.parse("$token do back", token, allowCommands = false))
        assertEquals(NetMessage.Do("tap 0.5 0.8"), NetLines.parse("$token do tap 0.5 0.8", token, allowCommands = true))
        assertNull(NetLines.parse("$token do", token, allowCommands = true))
    }

    @Test
    fun `malformed lines are refused whole`() {
        assertNull(NetLines.parse("$token io steer 0.5 lots", token, false))
        assertNull(NetLines.parse("$token io ../../etc 1", token, false))
        assertNull(NetLines.parse("$token io steer NaN", token, false))
        assertNull(NetLines.parse("$token launch rockets", token, false))
        assertNull(NetLines.parse("$token io steer " + "1 ".repeat(400), token, false))
    }

    @Test
    fun `tokens are compared whole`() {
        assertTrue(NetLines.sameToken("abcdefgh", "abcdefgh"))
        assertFalse(NetLines.sameToken("abcdefgh", "abcdefgi"))
        assertFalse(NetLines.sameToken("abcdefgh", "abcdefghi"))
        assertFalse(NetLines.sameToken("", "abcdefgh"))
    }
}
