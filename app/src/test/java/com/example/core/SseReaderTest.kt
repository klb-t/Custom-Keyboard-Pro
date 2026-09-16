package com.example.core

import com.example.core.discovery.CallSpec
import com.example.core.discovery.Chunk
import com.example.core.discovery.SseReader
import com.example.core.predict.GenerationCut
import com.example.core.predict.StopCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ln

/**
 * Reading a stream, without one.
 *
 * Everything that can go wrong in parsing server-sent events can go wrong with no
 * network involved, which is the only way to find it out on a machine with no phone
 * attached. The transport above this is a dozen lines and holds no judgement; all the
 * judgement is here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SseReaderTest {

    private fun openAiReader() = SseReader(
        textPath = "choices[0].delta.content",
        logProbPath = "choices[0].logprobs.content[0].logprob"
    )

    @Test
    fun `a token arrives as a token`() {
        val chunk = openAiReader().feed(
            """data: {"choices":[{"delta":{"content":" keyboard"}}]}"""
        )
        assertEquals(Chunk.Token(" keyboard"), chunk)
    }

    @Test
    fun `a log-probability comes with it when the provider reports one`() {
        val chunk = openAiReader().feed(
            """data: {"choices":[{"delta":{"content":" the"},""" +
                """"logprobs":{"content":[{"logprob":-0.105}]}}]}"""
        ) as Chunk.Token
        assertEquals(" the", chunk.text)
        assertEquals(-0.105, chunk.logP!!, 1e-9)
    }

    @Test
    fun `no log-probability is null, not zero`() {
        // Zero is a log-probability of 1.0 — perfect certainty — so reading a missing
        // one as zero would tell the stopping rules the opposite of the truth.
        val chunk = openAiReader().feed(
            """data: {"choices":[{"delta":{"content":"x"}}]}"""
        ) as Chunk.Token
        assertNull(chunk.logP)
    }

    @Test
    fun `the opening event carries a role and no text, and is not the end`() {
        // An OpenAI stream opens with an empty content field. Reading that as "the
        // stream produced nothing" would cut every completion before its first word.
        assertNull(
            openAiReader().feed("""data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""")
        )
        assertNull(openAiReader().feed("""data: {"choices":[{"delta":{}}]}"""))
    }

    @Test
    fun `the done marker ends it`() {
        assertEquals(Chunk.Done, openAiReader().feed("data: [DONE]"))
    }

    @Test
    fun `a keep-alive comment is not text`() {
        // Providers send these to hold the connection open. Reading one as data puts
        // a colon in the middle of somebody's sentence.
        assertNull(openAiReader().feed(": ping"))
        assertNull(openAiReader().feed(":"))
    }

    @Test
    fun `the other event fields are ignored`() {
        assertNull(openAiReader().feed("event: content_block_delta"))
        assertNull(openAiReader().feed("id: 42"))
        assertNull(openAiReader().feed("retry: 1000"))
        assertNull(openAiReader().feed(""))
        assertNull(openAiReader().feed("   "))
    }

    @Test
    fun `a carriage return does not become part of the text`() {
        val chunk = openAiReader().feed(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\r"
        )
        assertEquals(Chunk.Token("hi"), chunk)
        assertEquals(Chunk.Done, openAiReader().feed("data: [DONE]\r"))
    }

    @Test
    fun `the space after the colon is optional`() {
        assertEquals(
            Chunk.Token("x"),
            openAiReader().feed("""data:{"choices":[{"delta":{"content":"x"}}]}""")
        )
    }

    @Test
    fun `a failure mid-stream is reported rather than read as text`() {
        val chunk = openAiReader().feed("""data: {"error":{"message":"rate limited"}}""")
        assertEquals(Chunk.Failed("rate limited"), chunk)
    }

    @Test
    fun `something that is not json is skipped rather than thrown`() {
        assertNull(openAiReader().feed("data: not json at all"))
        assertNull(openAiReader().feed("data:"))
        assertNull(openAiReader().feed("nonsense entirely"))
    }

    @Test
    fun `a provider that puts the token somewhere else is a different path, not a branch`() {
        // The whole reason these are data: this is the Anthropic arrangement, and it
        // needs no code that knows the word "Anthropic".
        val reader = SseReader(textPath = "delta.text", doneMarker = "")
        assertEquals(
            Chunk.Token(" Dzięki"),
            reader.feed("""data: {"type":"content_block_delta","delta":{"text":" Dzięki"}}""")
        )
    }

    @Test
    fun `a reader built from a call spec uses what the spec says`() {
        val spec = CallSpec(
            stream = true,
            streamTextPath = "delta.text",
            streamLogProbPath = "delta.logprob",
            streamDone = "END"
        )
        val reader = SseReader(spec)
        val chunk = reader.feed("""data: {"delta":{"text":"a","logprob":-1.5}}""") as Chunk.Token
        assertEquals("a", chunk.text)
        assertEquals(-1.5, chunk.logP!!, 1e-9)
        assertEquals(Chunk.Done, reader.feed("data: END"))
    }

    @Test
    fun `streaming fields survive a round trip, because they are data`() {
        val spec = CallSpec(
            path = "/chat", stream = true,
            streamTextPath = "delta.text",
            streamLogProbPath = "delta.logprob",
            streamDone = "END",
            streamErrorPath = "err.msg"
        )
        assertEquals(spec, CallSpec.fromJson(spec.toJson()))
        // And a spec that says nothing about streaming still reads back as not streaming.
        assertEquals(false, CallSpec.fromJson(CallSpec(path = "/x").toJson()).stream)
    }

    // -----------------------------------------------------------------------
    // Reader and stopping rule together, which is the pair that matters
    // -----------------------------------------------------------------------

    @Test
    fun `a stream is cut where the stopping rule says, not where it ends`() {
        val reader = openAiReader()
        val cut = GenerationCut(StopCondition(tokenFloor = ln(0.1)), startedAt = 0L)
        val lines = listOf(
            """data: {"choices":[{"delta":{"content":" the"},"logprobs":{"content":[{"logprob":-0.1}]}}]}""",
            """data: {"choices":[{"delta":{"content":" cat"},"logprobs":{"content":[{"logprob":-0.2}]}}]}""",
            """data: {"choices":[{"delta":{"content":" Zbigniew"},"logprobs":{"content":[{"logprob":-4.6}]}}]}""",
            """data: {"choices":[{"delta":{"content":" more"},"logprobs":{"content":[{"logprob":-0.1}]}}]}""",
            "data: [DONE]"
        )
        var stopped = false
        lines.forEach { line ->
            if (stopped) return@forEach
            when (val chunk = reader.feed(line)) {
                is Chunk.Token -> if (!cut.accept(chunk.text, chunk.logP, now = 1)) stopped = true
                is Chunk.Done -> stopped = true
                else -> Unit
            }
        }
        assertEquals(" the cat", cut.text)
        assertEquals("it stopped being sure", cut.stoppedBecause)
    }

    @Test
    fun `a stream with no probabilities still stops on everything else`() {
        // Most providers will not report logprobs. The budget and the floor simply do
        // not fire, and the rest must still work — otherwise streaming is only
        // available to the providers that happen to be generous.
        val reader = openAiReader()
        val cut = GenerationCut(StopCondition(stopStrings = listOf("\n\n")), startedAt = 0L)
        listOf(
            """data: {"choices":[{"delta":{"content":"one"}}]}""",
            """data: {"choices":[{"delta":{"content":"\n\ntwo"}}]}"""
        ).forEach { line ->
            (reader.feed(line) as? Chunk.Token)?.let { cut.accept(it.text, it.logP, now = 1) }
        }
        assertEquals("one", cut.text)
        assertNotNull(cut.stoppedBecause)
    }
}
