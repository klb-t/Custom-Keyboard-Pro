package com.example.core.capture

import org.junit.Test

class ConversationCaptureTest {
    private fun frame(vararg text: String) = CaptureFrame(0, "collecting", text.mapIndexed { i, s -> CapturedNode("0/$i", "0", text = s) })
    private fun archive(options: CaptureOptions = CaptureOptions()) = ConversationArchive("test.chat", 7, "2026-09-29T00:00:00Z", options)
    private class Driver(var current: CaptureFrame) : CaptureDriver {
        var reads = 0; var scrolls = 0; var expansions = 0
        var fail = false; var canScroll = false; var canExpand = false
        override fun frame(elapsed: Long, phase: String): CaptureFrame { reads++; check(!fail) { "PRIVATE ERROR MUST NOT LEAK" }; return current.copy(elapsedMillis = elapsed, phase = phase) }
        override fun scroll(forward: Boolean): Boolean { scrolls++; return canScroll }
        override fun expand(attempted: MutableSet<String>): Boolean {
            expansions++
            return canExpand && attempted.add("disclosure-1")
        }
    }
    @Test fun unknownAndDestructiveControlsNeverClicked() {
        for (label in listOf("Delete", "Send", "Retry", "Share", "Buy", "Next conversation"))
            check(DisclosurePolicy.action(DisclosurePolicy.Control(label, "collapsed", expand = true, clickable = true)) == null)
        for (label in listOf("Show all accounts", "More options", "Mystery widget"))
            check(DisclosurePolicy.action(DisclosurePolicy.Control(label, "collapsed", clickable = true)) == null)
    }
    @Test fun toggleRequiresExplicitCollapsedState() {
        check(DisclosurePolicy.action(DisclosurePolicy.Control("Show more", clickable = true)) == null)
        check(DisclosurePolicy.action(DisclosurePolicy.Control("Show more", "collapsed", clickable = true)) == DisclosurePolicy.Action.CLICK)
        check(DisclosurePolicy.action(DisclosurePolicy.Control("Show more", "expanded", clickable = true)) == null)
    }
    @Test fun semanticExpandDoesNotRequireToggleGuessing() {
        check(DisclosurePolicy.action(DisclosurePolicy.Control("Thought for 13s", expand = true)) == DisclosurePolicy.Action.EXPAND)
    }
    @Test fun collapsedActionNeverUsedAsExpand() {
        check(DisclosurePolicy.action(DisclosurePolicy.Control("Show details", "collapsed", collapse = true, clickable = true)) == null)
    }
    @Test fun privateInputsAndLinksRejected() {
        val c = DisclosurePolicy.Control("Show details", "collapsed", expand = true, clickable = true)
        for (v in listOf(c.copy(password = true), c.copy(editable = true), c.copy(checkable = true), c.copy(link = true))) check(DisclosurePolicy.action(v) == null)
    }
    @Test fun polishAndDutchLabelsAreProfiles() {
        for (label in listOf("Pokaż więcej", "Rozwiń szczegóły", "Wywołania narzędzi", "Toon meer", "Gedachten"))
            check(DisclosurePolicy.action(DisclosurePolicy.Control(label, expand = true)) != null)
    }
    @Test fun repetitionsAreNotGloballyRemoved() {
        val a = archive(); a.append(frame("yes", "yes")); a.append(frame("yes", "yes"))
        check(a.frames.sumOf { it.nodes.size } == 4)
        check(a.text().split("yes").size - 1 == 4)
    }
    @Test fun shortOverlapIsNotTrusted() {
        check(ConversationArchive.overlap(listOf("OK", "yes"), listOf("OK", "yes")) == 0)
        check(ConversationArchive.overlap(listOf("A".repeat(80)), listOf("A".repeat(80))) == 0)
    }
    @Test fun adjacentLongOverlapIsOnlyAView() {
        val x = "First distinct content ".repeat(4); val y = "Second distinct content ".repeat(4)
        check(ConversationArchive.overlap(listOf("earlier", x, y), listOf(x, y, "later")) == 2)
        val a = archive(); a.append(frame(x, y)); a.append(frame(x, y, "later"))
        check(a.frames.sumOf { it.nodes.size } == 5)
        check(a.json().split(x).size - 1 == 2)
    }
    @Test fun hierarchyAndRevisionsSurvive() {
        val a = archive(); a.append(frame("collapsed")); a.append(frame("expanded content"))
        check(a.json().contains("\"parent\":\"0\"")); check(a.json().contains("expanded content")); check(a.json().contains("collapsed"))
    }
    @Test fun jsonEscapesControlsAndSurrogates() {
        check(ConversationArchive.quote("\"\\\n\t\u0000😀") == "\"\\\"\\\\\\n\\t\\u0000\\ud83d\\ude00\"")
    }
    @Test fun characterLimitIsExplicit() {
        val a = archive(CaptureOptions(maxChars = 1000)); a.append(frame("x".repeat(1500)))
        check(a.full && a.truncated); check(a.frames.single().clipped); check(a.text().contains("WARNING"))
    }
    @Test fun frameLimitDoesNotGrowBuffer() {
        val a = archive(CaptureOptions(maxFrames = 1)); check(a.append(frame("first"))); check(!a.append(frame("second"))); check(a.frames.size == 1)
    }
    @Test fun optionsValidateResourceLimits() {
        for (f in listOf<() -> Unit>({ CaptureOptions(maxFrames = 0) }, { CaptureOptions(maxChars = 1) }, { CaptureOptions(maxMillis = 0) }, { CaptureOptions(settleMillis = 1) })) {
            check(runCatching { f() }.isFailure)
        }
    }
    @Test fun stopCancelsEveryFutureOperation() {
        val d = Driver(frame("text")); val s = CaptureSession(archive(CaptureOptions(seekStart = false)), d)
        s.step(0); s.stop(); val before = Triple(d.reads, d.scrolls, d.expansions)
        repeat(10) { s.step(1000) }; check(before == Triple(d.reads, d.scrolls, d.expansions)); check(s.archive.reason == "user_stopped")
    }
    @Test fun targetFailureStopsWithoutLeakingExceptionText() {
        val d = Driver(frame("safe")); val s = CaptureSession(archive(CaptureOptions(seekStart = false)), d)
        s.step(0); d.fail = true; s.step(700)
        check(s.state == CaptureSession.State.FINISHED); check(d.scrolls == 0 && d.expansions == 0)
        check(!s.archive.json().contains("PRIVATE ERROR"))
    }
    @Test fun actionsWaitForStableObservation() {
        val d = Driver(frame("a")); val s = CaptureSession(archive(CaptureOptions(seekStart = false)), d)
        s.step(0); check(d.scrolls == 0 && d.expansions == 0)
        d.current = frame("b"); s.step(700); check(d.scrolls == 0 && d.expansions == 0)
        s.step(1400); check(d.scrolls == 1)
    }
    @Test fun timeoutRetainsLastSafeObservation() {
        val d = Driver(frame("safe")); val s = CaptureSession(archive(CaptureOptions(seekStart = false, maxMillis = 1000)), d)
        s.step(0); s.step(1000); check(s.archive.reason == "time_limit"); check(s.archive.frames.single().textLines() == listOf("safe"))
    }
    @Test fun reachingLocalBoundaryIsNotCompletenessClaim() {
        val d = Driver(frame("safe")); val s = CaptureSession(archive(), d)
        s.step(0); check(s.archive.startStatus.contains("not_proof_of_start"))
        s.step(700); s.step(1400)
        check(s.state == CaptureSession.State.FINISHED); check(s.archive.json().contains("\"completeness\":\"unverified\""))
    }
    @Test fun noProgressIsBoundedEvenWhenAppAcceptsScroll() {
        val d = Driver(frame("same")); d.canScroll = true
        val s = CaptureSession(archive(CaptureOptions(seekStart = false, expandDetails = false)), d)
        repeat(20) { s.step(it * 700L) }
        check(s.state == CaptureSession.State.FINISHED); check(d.scrolls == 3)
        check(s.archive.frames.size == 4); check(s.archive.reason.contains("unverified"))
    }
    @Test fun manualModeDoesNotScrollAndRetainsNewFrames() {
        val d = Driver(frame("first")); val s = CaptureSession(archive(CaptureOptions(autoScroll = false, expandDetails = false)), d)
        s.step(0); s.step(700); d.current = frame("second"); s.step(1400); s.step(2100)
        check(d.scrolls == 0 && d.expansions == 0); check(s.archive.frames.size == 2)
    }
    @Test fun expansionIsOnePerStepAndNotRepeatedInSameEpoch() {
        val d = Driver(frame("Show more")); d.canExpand = true
        val s = CaptureSession(archive(CaptureOptions(autoScroll = false)), d)
        repeat(8) { s.step(it * 700L) }
        check(s.archive.expanded == 1); check(d.scrolls == 0)
    }
    @Test fun continuousStreamingIsCapturedWithoutActions() {
        val d = Driver(frame("0")); val s = CaptureSession(archive(CaptureOptions(seekStart = false)), d)
        repeat(6) { d.current = frame("stream $it"); s.step(it * 700L) }
        check(s.archive.frames.single().phase == "changing_content_no_actions"); check(d.scrolls == 0 && d.expansions == 0)
    }
    @Test fun clippedSourceCannotBeCalledComplete() {
        val d = Driver(frame("partial").copy(clipped = true)); val s = CaptureSession(archive(CaptureOptions(seekStart = false)), d)
        s.step(0); s.step(700); check(s.archive.reason == "content_limit"); check(s.archive.truncated)
    }
}
