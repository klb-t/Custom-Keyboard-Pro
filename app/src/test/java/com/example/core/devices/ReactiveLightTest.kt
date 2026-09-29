package com.example.core.devices

import java.io.ByteArrayInputStream
import org.junit.Test

class ReactiveLightTest {
    private val p = ReactiveLightPolicy()
    private val a = LightValue(0xff0000, 10)
    private val b = LightValue(0x00ff00, 20)
    private fun fail(f: () -> Unit) { check(runCatching(f).isFailure) }
    private fun discovery(host: String = "192.168.1.20") = "HTTP/1.1 200 OK\r\nLocation: yeelight://$host:55443\r\nid: 0x1234abcd\r\nmodel: color\r\nsupport: get_prop set_music set_scene set_power\r\n\r\n"
    private fun read(value: String) = YeelightProtocol.readFrame(ByteArrayInputStream(value.toByteArray()))
    @Test fun silentPcmIsZeroNotANonFiniteLevel() { check(PcmEnvelope.rms(ShortArray(320)) == 0f) }
    @Test fun constantPcmRmsIsNormalized() { check(kotlin.math.abs(PcmEnvelope.rms(ShortArray(320) { 16384 }) - .5f) < 1e-6) }
    @Test fun fullScaleNegativePcmDoesNotOverflow() { check(PcmEnvelope.rms(ShortArray(320) { Short.MIN_VALUE }) == 1f) }
    @Test fun onlyReadSamplesAreUsed() { check(PcmEnvelope.rms(shortArrayOf(0, 0, Short.MAX_VALUE), 2) == 0f) }
    @Test fun invalidPcmFramesAreRejected() { fail { PcmEnvelope.rms(shortArrayOf()) }; fail { PcmEnvelope.rms(ShortArray(10), 11) }; fail { PcmEnvelope.rms(ShortArray(48001)) } }
    @Test fun levelMappingIsBoundedByUserPolicy() {
        for (i in 0..1000) { val v = LevelToColour.map(i / 100f, p); check(v.rgb in 0..0xffffff && v.brightness in 1..30) }
    }
    @Test fun silenceDoesNotClaimAnOffCommand() { check(LevelToColour.map(0f,p) == LightValue(0x0000ff, 1)) }
    @Test fun strongLevelSaturatesAtConfiguredMaximum() { check(LevelToColour.map(1f,p) == LightValue(0xff0000, 30)) }
    @Test fun invalidLevelsDoNotReachTheDevice() { listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach { fail { LevelToColour.map(it,p) } } }
    @Test fun invalidPolicyDoesNotCreateARun() {
        fail { p.copy(gain = Float.NaN) }; fail { p.copy(maximumBrightness = 101) }
        fail { p.copy(hz = 0) }; fail { p.copy(durationSeconds = 601) }
    }
    @Test fun invalidLightEffectsAreRejected() { fail { LightValue(-1, 10) }; fail { LightValue(0,0) }; fail { LightValue(0x1000000,10) } }
    @Test fun mailboxCoalescesRatherThanQueues() { val q=LatestLight(10);q.offer(0,a);q.offer(1,b);check(q.take(1)==b);check(q.take(200)==null) }
    @Test fun mailboxEnforcesRateWithoutDroppingNewestPending() {
        val q=LatestLight(10);q.offer(0,a);check(q.take(0)==a);q.offer(30,b);check(q.take(30)==null);check(q.take(100)==b)
    }
    @Test fun mailboxDropsStaleAndFutureSamples() { val q=LatestLight(10);q.offer(100,a);check(q.take(99)==null);q.offer(101,b);check(q.take(402)==null) }
    @Test fun mailboxDoesNotResendIdenticalValues() { val q=LatestLight(10);q.offer(0,a);q.take(0);q.offer(200,a);check(q.take(200)==null) }
    @Test fun stopDropsEveryPendingFrameAndRefusesNewOnes() { val q=LatestLight(10);q.offer(0,a);q.close();check(q.take(10)==null && !q.offer(11,b));q.close() }
    @Test fun backwardsSourceTimeIsRejected() { val q=LatestLight(10);check(q.offer(100,a));check(!q.offer(99,b));check(q.take(100)==a) }
    @Test fun permittedPrivateIpv4IsNumericAndExact() { for (ip in listOf("10.0.0.1","172.16.0.1","172.31.4.254","192.168.1.20")) check(YeelightProtocol.privateIpv4(ip).hostAddress==ip) }
    @Test fun publicLoopbackHostnamesIpv6AndAmbiguousAddressesAreRejected() {
        for (ip in listOf("127.0.0.1","8.8.8.8","172.15.0.1","172.32.0.1","192.168.1.0","192.168.1.255","192.168.001.2","example.com","::1","10.1.1.999","10.1.1.2:55443","10.1.1.2\r\n")) fail { YeelightProtocol.privateIpv4(ip) }
    }
    @Test fun discoveryChecksIdentityAndCapabilities() { val d=YeelightProtocol.discovery(discovery(),"192.168.1.20");check(d.id=="0x1234abcd" && d.methods.containsAll(YeelightProtocol.required)) }
    @Test fun deviceCannotRedirectToAnotherTargetOrPort() {
        fail { YeelightProtocol.discovery(discovery("192.168.1.21"),"192.168.1.20") }
        fail { YeelightProtocol.discovery(discovery().replace(":55443",":80"),"192.168.1.20") }
    }
    @Test fun unsupportedMusicOrColourIsExplicitlyRejected() {
        for (method in YeelightProtocol.required) fail { YeelightProtocol.discovery(discovery().replace(method,""),"192.168.1.20") }
    }
    @Test fun duplicateDiscoveryHeadersAreRejected() { fail { YeelightProtocol.discovery(discovery()+"location: yeelight://192.168.1.20:55443\r\n","192.168.1.20") } }
    @Test fun discoveryCannotCarryCredentialsPathsOrMalformedIdentity() {
        for (raw in listOf(discovery().replace("yeelight://","yeelight://user:secret@"),discovery().replace(":55443",":55443/path"),discovery().replace("0x1234abcd","wrong"))) fail { YeelightProtocol.discovery(raw,"192.168.1.20") }
    }
    @Test fun outgoingProtocolIsFixedTypedAndCrLfFramed() {
        check(YeelightProtocol.properties(1)=="{\"id\":1,\"method\":\"get_prop\",\"params\":[\"power\",\"bright\",\"color_mode\",\"rgb\"]}\r\n")
        check(YeelightProtocol.music(2,"192.168.1.2",45000)=="{\"id\":2,\"method\":\"set_music\",\"params\":[1,\"192.168.1.2\",45000]}\r\n")
        check(YeelightProtocol.colour(3,a)=="{\"id\":3,\"method\":\"set_scene\",\"params\":[\"color\",16711680,10]}\r\n")
    }
    @Test fun callbackArgumentsCannotInjectProtocolText() { fail { YeelightProtocol.music(2,"192.168.1.2\"",45000) };fail { YeelightProtocol.music(2,"192.168.1.2",80) };fail { YeelightProtocol.colour(0,a) } }
    @Test fun boundedFrameReaderReturnsOneResponseNotTheNext() {
        val input=ByteArrayInputStream("{\"id\":1,\"result\":[\"ok\"]}\r\n{\"id\":2}\r\n".toByteArray())
        check(YeelightProtocol.readFrame(input)=="{\"id\":1,\"result\":[\"ok\"]}");check(YeelightProtocol.readFrame(input)=="{\"id\":2}")
    }
    @Test fun truncatedOversizedOrDeepRepliesAreRejectedBeforeJsonParsing() {
        fail { read("{\"id\":1}") };fail { read("{\"x\":\""+"a".repeat(8192)+"\"}\r\n") }
        fail { read("{\"x\":"+"[".repeat(9)+"0"+"]".repeat(9)+"}\r\n") }
    }
    @Test fun bracesInsideStringsDoNotConfuseResourceBounds() { check(read("{\"x\":\"[[[[[[[[[[{\\\"\"}\r\n").contains("[[[[")) }
    @Test fun rawFramePreflightDoesNotPretendToBeJsonParser() { check(read("{not-valid-json}\r\n")=="{not-valid-json}") /* The injected platform decoder must reject this. */ }
    @Test fun closedConnectionCannotDiscoverOrSend() {
        val sink=YeelightConnection("192.168.1.20") { error("Not reached") };sink.close()
        fail { sink.connect() };fail { sink.send(a) };sink.close()
    }
    @Test fun connectionWithoutHandshakeCannotSend() { val sink=YeelightConnection("192.168.1.20") { error("Not reached") };fail { sink.send(a) };sink.close() }
}
