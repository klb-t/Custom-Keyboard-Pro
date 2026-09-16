package com.example.core

import com.example.core.discovery.JsonPath
import com.example.core.discovery.Templates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two pure halves of a described call: finding the answer in a reply, and putting
 * the request together. Both are worth testing on their own because both are where a
 * provider added as data succeeds or fails, and neither needs a network to be wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSpecTest {

    // -----------------------------------------------------------------------
    // Reading a reply
    // -----------------------------------------------------------------------

    private val reply = JSONObject(
        """
        {
          "text": "hello",
          "results": {"channels": [{"alternatives": [{"transcript": "ala ma kota"}]}]},
          "data": [{"b64_json": "QUJD", "url": "https://x/y.png"}],
          "output": ["https://x/v.mp4"],
          "empty": null,
          "n": 7,
          "ok": true
        }
        """.trimIndent()
    )

    @Test
    fun `the paths the catalogue actually uses all resolve`() {
        assertEquals("hello", JsonPath.string(reply, "text"))
        assertEquals(
            "ala ma kota",
            JsonPath.string(reply, "results.channels[0].alternatives[0].transcript")
        )
        assertEquals("QUJD", JsonPath.string(reply, "data[0].b64_json"))
        assertEquals("https://x/v.mp4", JsonPath.string(reply, "output[0]"))
    }

    @Test
    fun `an empty path is the whole reply`() {
        assertEquals(reply, JsonPath.get(reply, ""))
    }

    @Test
    fun `a path that is not there is nothing, not an exception`() {
        assertNull(JsonPath.get(reply, "nope"))
        assertNull(JsonPath.get(reply, "text.deeper"))
        assertNull(JsonPath.get(reply, "data[9].b64_json"))
        assertNull(JsonPath.get(reply, "results.channels[0].missing[0].x"))
        // A provider that answered with an error object should produce "nothing was
        // found there", so the caller can show the error it actually got.
        assertNull(JsonPath.get(reply, "empty"))
    }

    @Test
    fun `numbers and booleans come back as text when asked for text`() {
        assertEquals("7", JsonPath.string(reply, "n"))
        assertEquals("true", JsonPath.string(reply, "ok"))
    }

    @Test
    fun `a malformed path degrades instead of throwing`() {
        assertNull(JsonPath.get(reply, "data[0"))
        assertNull(JsonPath.get(reply, "data[x].b64_json"))
        assertEquals(reply, JsonPath.get(reply, "."))
    }

    // -----------------------------------------------------------------------
    // Building a request
    // -----------------------------------------------------------------------

    @Test
    fun `placeholders are filled and unknown ones vanish`() {
        assertEquals(
            "/listen?model=nova-3&language=pl",
            Templates.fill("/listen?model={{model}}&language={{language}}",
                mapOf("model" to "nova-3", "language" to "pl"))
        )
        assertEquals(
            "/listen?model=",
            Templates.fill("/listen?model={{model}}", emptyMap())
        )
    }

    @Test
    fun `a placeholder a template asks for can be listed`() {
        assertEquals(
            setOf("model", "prompt"),
            Templates.placeholdersIn("""{"model":"{{model}}","p":"say {{prompt}} now"}""")
        )
    }

    @Test
    fun `a prompt with quotes and newlines cannot break out of its string`() {
        // Substituting into JSON *text* would work until the first person dictated a
        // sentence with a quotation mark in it, so the body is walked as JSON instead.
        val nasty = "she said \"no\",\nthen left \\ the room"
        val body = Templates.fillJson(
            """{"model":"{{model}}","prompt":"{{prompt}}"}""",
            mapOf("model" to "m", "prompt" to nasty)
        )
        assertEquals(nasty, body.getString("prompt"))
        // And it survives being written out and read back, which is what is actually sent.
        assertEquals(nasty, JSONObject(body.toString()).getString("prompt"))
    }

    @Test
    fun `a whole-string placeholder keeps the type of what it resolves to`() {
        val body = Templates.fillJson(
            """{"model":"{{model}}","temperature":"{{temperature}}","stream":"{{stream}}","n":"{{n}}"}""",
            mapOf("model" to "m", "temperature" to "0.7", "stream" to "false", "n" to "4")
        )
        // Several providers reject "0.7" where they expect 0.7.
        assertEquals(0.7, body.getDouble("temperature"), 0.0001)
        assertFalse(body.getBoolean("stream"))
        assertEquals(4, body.getInt("n"))
        assertEquals("m", body.getString("model"))
    }

    @Test
    fun `a placeholder with text around it stays text`() {
        val body = Templates.fillJson(
            """{"q":"data:{{imageMime}};base64,{{imageBase64}}"}""",
            mapOf("imageMime" to "image/png", "imageBase64" to "QUJD")
        )
        assertEquals("data:image/png;base64,QUJD", body.getString("q"))
    }

    @Test
    fun `a field nothing supplies is dropped rather than sent empty`() {
        val body = Templates.fillJson(
            """{"model":"{{model}}","language":"{{language}}"}""",
            mapOf("model" to "m")
        )
        assertEquals("m", body.getString("model"))
        // Providers reject `"language": ""` more often than they reject its absence.
        assertFalse("an unsupplied field was sent anyway", body.has("language"))
    }

    @Test
    fun `nesting is substituted all the way down`() {
        val body = Templates.fillJson(
            """{"instances":[{"prompt":"{{prompt}}"}],"parameters":{"sampleCount":1}}""",
            mapOf("prompt" to "a cat")
        )
        assertEquals(
            "a cat",
            body.getJSONArray("instances").getJSONObject(0).getString("prompt")
        )
        assertEquals(1, body.getJSONObject("parameters").getInt("sampleCount"))
    }

    @Test
    fun `what is already a number in the template is left alone`() {
        val body = Templates.fillJson("""{"n":1,"on":true,"x":2.5}""", emptyMap())
        assertEquals(1, body.getInt("n"))
        assertTrue(body.getBoolean("on"))
        assertEquals(2.5, body.getDouble("x"), 0.0001)
    }
}
