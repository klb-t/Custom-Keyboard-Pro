package com.example.core.phone

import com.example.core.io.Command
import com.example.core.io.Verbs
import org.junit.Assert.*
import org.junit.Test

class PhoneCatalogueTest {
    private fun parse(line: String) = PhoneRequest.parse(Command.parse(line)!!)
    private fun rejects(line: String) { assertTrue("Accepted: $line", runCatching { parse(line) }.isFailure) }
    @Test fun everyPhoneBindingIsInTheCanonicalVerbCatalogueExactlyOnce() {
        PhoneCatalogue.verbs.forEach { spec -> assertEquals(1, Verbs.ALL.count { it.id == spec.id }); assertEquals(spec, Verbs.byId(spec.id)) }
        assertEquals(PhoneCatalogue.routes.size, PhoneCatalogue.routes.map { it.id }.distinct().size)
        assertTrue(PhoneCatalogue.routes.all { it.action.startsWith("android.settings.") && it.explanation.isNotBlank() && it.reference.startsWith("https://developer.android.com/") })
    }
    @Test fun defaultsNeverStartCaptureOrRequestPermission() {
        assertEquals(PhoneRequest.Tools("overview"), parse("phone_tools"))
        assertEquals(PhoneRequest.Info("device"), parse("phone_info"))
        assertEquals(PhoneRequest.Sensors, parse("sensors"))
        assertEquals(PhoneRequest.Monitor("s1.0.0", 10, 30), parse("sensor_monitor s1.0.0"))
    }
    @Test fun monitorBoundsAndInventoryKeysAreStrict() {
        assertEquals(PhoneRequest.Monitor("s42.-1.130", 20, 300), parse("sensor_monitor sensor=s42.-1.130 hz=20 seconds=300"))
        listOf("sensor_monitor", "sensor_monitor sensor=accelerometer", "sensor_monitor sensor=s1.0.0 hz=21", "sensor_monitor sensor=s1.0.0 hz=0", "sensor_monitor sensor=s1.0.0 seconds=301", "sensor_monitor sensor=s1.0.0 seconds=0", "sensor_monitor sensor=s1.0.0 hz=1.5").forEach(::rejects)
    }
    @Test fun unexpectedArgumentsAndArbitraryIntentsAreRejected() {
        listOf("sensors destination=cloud", "phone_info device secret", "phone_info section=all_private_data", "phone_tools shell=su", "special_access target=android.intent.action.DELETE", "special_access target=developer").forEach(::rejects)
        assertNull(PhoneCatalogue.route("android.intent.action.DELETE"))
        assertNotNull(PhoneCatalogue.route("wireless_debug"))
    }
    @Test fun appOperationsAcceptOnlyPackageIdentifiers() {
        assertEquals(PhoneRequest.AppInfo("com.android.chrome"), parse("app_info package=com.android.chrome"))
        listOf("app_settings package=chrome", "app_info package=package:com.android.chrome", "app_info package=https://example.org", "app_info package=com.example/foo", "app_info package=com.example extra=yes").forEach(::rejects)
    }
    @Test fun rotationAndTimeoutHaveTypedRanges() {
        assertEquals(PhoneRequest.Rotation(true, null), parse("rotation state=auto"))
        assertEquals(PhoneRequest.Rotation(false, 0), parse("rotation state=locked"))
        assertEquals(PhoneRequest.Rotation(false, 3), parse("rotation locked reverse_landscape"))
        assertEquals(PhoneRequest.Rotation(false, 1, true), parse("rotation locked quarter_turn"))
        assertEquals(PhoneRequest.Rotation(false, 0, true), parse("rotation locked natural"))
        assertEquals(PhoneRequest.Timeout(1800), parse("screen_timeout 1800"))
        listOf("rotation", "rotation auto portrait", "rotation state=locked orientation=random", "screen_timeout 14", "screen_timeout 1801", "screen_timeout 2147483647", "screen_timeout 15.5").forEach(::rejects)
    }
    @Test fun plannedSensitiveAdaptersRemainClearlyDescribedAsAbsent() {
        val allFiles = PhoneCatalogue.route("all_files")!!
        assertFalse(allFiles.packageScoped)
        assertTrue(allFiles.explanation.contains("does not request"))
        assertTrue(PhoneCatalogue.route("notifications")!!.explanation.contains("No notification-listener adapter"))
        assertTrue(PhoneCatalogue.route("developer")!!.explanation.contains("cannot enable"))
    }
}
