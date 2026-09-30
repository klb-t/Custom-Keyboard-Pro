package com.example.assistant

import org.junit.Test

class AssistantSessionVisibilityTest {
    @Test fun hiddenSessionInvalidatesAndNotifiesOnce() {
        val lease = AssistantSessionVisibility(); val token = lease.show(); var stopped = 0
        check(lease.attach(token) { stopped++ })
        lease.hide(token); lease.hide(token)
        check(!lease.isVisible(token) && stopped == 1 && !lease.attach(token) { stopped++ })
    }
    @Test fun aNewInvocationCancelsOldWorkAndIgnoresLateHideFromOldSession() {
        val lease = AssistantSessionVisibility(); val old = lease.show(); var oldStops = 0; var newStops = 0
        lease.attach(old) { oldStops++ }
        val fresh = lease.show(); lease.attach(fresh) { newStops++ }
        lease.hide(old)
        check(oldStops == 1 && newStops == 0 && lease.isVisible(fresh) && old != fresh)
        lease.hide(fresh); check(newStops == 1)
    }
    @Test fun externalOrMissingTokenCannotAttachToAVisibleSession() {
        val lease = AssistantSessionVisibility(); val token = lease.show(); var calls = 0
        check(!lease.attach(null) { calls++ } && !lease.attach("external") { calls++ })
        lease.hide(null); lease.hide("external")
        check(lease.isVisible(token) && calls == 0)
    }
    @Test fun oldActivityDetachCannotRemoveCurrentInvocationsCancellation() {
        val lease = AssistantSessionVisibility(); val old = lease.show(); val fresh = lease.show(); var stops = 0
        lease.attach(fresh) { stops++ }; lease.detach(old); lease.hide(fresh)
        check(stops == 1)
    }
}
