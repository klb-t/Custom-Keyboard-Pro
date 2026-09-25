package com.example.core

import com.example.core.matrix.Change
import com.example.core.matrix.ChangeKind
import com.example.core.matrix.Interpret
import com.example.core.matrix.Mapping
import com.example.core.matrix.Origin
import com.example.core.matrix.Provenance
import com.example.core.matrix.Record
import com.example.core.matrix.Site
import com.example.core.matrix.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The history a result carries: what it keeps apart, and that it survives a file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvenanceTest {

    private val read = Record(
        transform = "read_field", implementation = "read_field.app", site = Site.APP,
        mapping = Mapping.INFERENTIAL,
        changes = listOf(Change(ChangeKind.DISCARDED, "colour")),
        parameters = mapOf("time" to "down", "seconds" to "10"),
        assumed = listOf("seconds"),
        assumptions = listOf("the picture is a spectrogram")
    )
    private val notes = Record("extract_notes", "extract_notes.app", Site.APP, Mapping.INFERENTIAL, emptyList())
    private val encode = Record("synth", "synth.app", Site.APP, Mapping.DETERMINISTIC, listOf(Change(ChangeKind.SYNTHESIZED, "timbre")))

    private val history = Provenance(Source("clipboard", "picture", "jpeg"))
        .then(read).then(notes).then(encode)

    @Test
    fun `an inference early on is never hidden by calculations after it`() {
        assertEquals(setOf(Origin.OBSERVED, Origin.INFERRED, Origin.DERIVED), history.origins)
        assertFalse(history.leftDevice)
        assertEquals(listOf("timbre"), history.changes(ChangeKind.SYNTHESIZED))
        assertEquals(listOf("read_field: seconds"), history.assumed)
    }

    @Test
    fun `a step at a provider is remembered as having left the phone`() {
        val sent = history.then(Record("transcribe", "transcribe.provider", Site.PROVIDER, Mapping.INFERENTIAL, emptyList()))
        assertTrue(sent.leftDevice)
        assertTrue(sent.summary().contains("sent away"))
    }

    @Test
    fun `a history survives being written into a file and read back`() {
        val carried = Provenance(Source("clipboard", "picture", "png"), earlier = history)
        assertEquals(carried, Provenance.fromJson(carried.toJson().toString()))
    }

    @Test
    fun `anything that is not a history is not taken for one`() {
        assertNull(Provenance.fromJson("{\"hello\":1}"))
        assertNull(Provenance.fromJson("not json"))
        assertNull(Provenance.fromJson(null))
        assertNull(Provenance.fromJson("{\"io\":1,\"source\":{},\"records\":[{\"transform\":\"x\",\"mapping\":\"guessing\"}]}"))
    }

    @Test
    fun `unknown confidence stays unknown`() {
        val back = Provenance.fromJson(history.toJson().toString())!!
        assertTrue(back.records.all { it.confidence == null })
        val sure = Provenance.fromJson(history.then(notes.copy(confidence = 0.4)).toJson().toString())!!
        assertEquals(0.4, sure.records.last().confidence!!, 1e-9)
    }

    @Test
    fun `the summary says what was inferred and what was assumed`() {
        val s = history.summary()
        assertTrue(s, s.startsWith("clipboard (jpeg)"))
        assertTrue(s, s.contains("read_field [inferred; assumed seconds]"))
        assertTrue(history.details().contains("assumed (not given): seconds"))
    }

    @Test
    fun `a picture's own history says it is a spectrogram and how it was drawn`() {
        val drawn = Provenance(Source("clipboard", "audio", "wav"))
            .then(Record("stft", "stft.app", Site.APP, Mapping.DETERMINISTIC, emptyList()))
            .then(Record("render_field", "render_field.app", Site.APP, Mapping.DETERMINISTIC, emptyList(), mapOf("time" to "up")))
        assertEquals(setOf("spectrogram"), Interpret.factsFrom(drawn))
        assertEquals(mapOf("time" to "up"), Interpret.carried(drawn, "render_field"))
        assertEquals(mapOf("time" to "up"), Interpret.carried(Provenance(Source("file", "picture", "png"), earlier = drawn), "render_field"))
    }
}
