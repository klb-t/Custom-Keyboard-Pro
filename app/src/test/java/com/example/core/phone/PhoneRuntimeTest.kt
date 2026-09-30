package com.example.core.phone

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.core.io.Command
import com.example.io.Performer
import com.example.io.PerformerHost
import com.example.phone.PhoneRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhoneRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun missingWriteAccessDoesNotProduceASuccessfulReceipt() {
        Shadows.shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.WRITE_SETTINGS)
        val operations = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        Shadows.shadowOf(operations).setMode(android.app.AppOpsManager.OPSTR_WRITE_SETTINGS, android.os.Process.myUid(), context.packageName, android.app.AppOpsManager.MODE_ERRORED)
        assertFalse(android.provider.Settings.System.canWrite(context))
        val result = PhoneRuntime.execute(context, Command("rotation", named = mapOf("state" to "auto")))
        assertEquals(PhoneOutcome.NEEDS_ACCESS, result.outcome)
        assertEquals("rotation", result.command.verb)
    }
    @Test fun oldApiCannotDispatchNewerSpecialAccessPages() {
        val result = PhoneRuntime.execute(context, Command("special_access", named = mapOf("target" to "exact_alarm")))
        assertEquals(PhoneOutcome.UNAVAILABLE, result.outcome)
    }
    @Test fun openedDeveloperPageDoesNotClaimDeveloperModeWasChanged() {
        val result = PhoneRuntime.openRoute(context, Command("system_settings", named = mapOf("page" to "developer")), "developer")
        assertEquals(PhoneOutcome.OPENED, result.outcome)
        assertTrue(result.detail.contains("cannot enable"))
        assertEquals("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", Shadows.shadowOf(context as android.app.Application).nextStartedActivity.action)
    }
    @Test fun canonicalPerformerDeliversTheSameInvocationReceiptWithoutASecondExecution() {
        val results = mutableListOf<PhoneReceipt>()
        val notices = mutableListOf<String>()
        val host = object : PerformerHost {
            override val context: Context get() = this@PhoneRuntimeTest.context
            override fun sendKey(keyCode: Int) = Unit
            override fun nearbyText() = ""
            override fun commit(text: String) = Unit
            override fun copy(text: String, label: String) = Unit
            override fun offerToAi(text: String) = Unit
            override fun notice(text: String, actionLabel: String?, action: (() -> Unit)?) { notices += text }
            override fun record(state: String, name: String) = Unit
            override fun play(name: String, times: Int) = Unit
            override fun dismissKeyboard() = Unit
            override fun phoneReceipt(receipt: PhoneReceipt) { results += receipt }
        }
        val command = Command("phone_tools", named = mapOf("tab" to "access"))
        Performer(host).run(command)
        assertEquals(1, results.size)
        assertEquals(command, results.single().command)
        assertEquals(PhoneOutcome.OPENED, results.single().outcome)
        assertEquals(1, notices.size)
        val shadow = Shadows.shadowOf(context as android.app.Application)
        val started = shadow.nextStartedActivity
        assertEquals("access", started.getStringExtra("tab"))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadow.nextStartedActivity)
    }
    @Test fun invalidRequestCannotOpenAnActivity() {
        val result = PhoneRuntime.execute(context, Command("app_settings", named = mapOf("package" to "https://example.org")))
        assertEquals(PhoneOutcome.FAILED, result.outcome)
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedActivity)
    }
    @Test fun navigationNamesRemainSeparateFromSpecialAccessGrants() {
        PhoneRuntime.openRoute(context, Command("system_settings", named = mapOf("page" to "notifications")), "notifications")
        val shadow = Shadows.shadowOf(context as android.app.Application)
        assertEquals("android.settings.ALL_APPS_NOTIFICATION_SETTINGS", shadow.nextStartedActivity.action)
        PhoneRuntime.execute(context, Command("special_access", named = mapOf("target" to "notifications")))
        assertEquals("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", shadow.nextStartedActivity.action)
    }
}
