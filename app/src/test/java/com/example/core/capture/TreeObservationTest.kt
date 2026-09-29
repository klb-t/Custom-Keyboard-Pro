package com.example.core.capture

import org.junit.Test

class TreeObservationTest {
    private class FakeNode(val value: String = "", override val visible: Boolean = true,
                           override val privateSubtree: Boolean = false,
                           val children: List<FakeNode?> = emptyList(), val facts: NodeSemantics = NodeSemantics()) : CaptureTreeNode {
        var closed = 0
        var failDescribe = false
        override val childCount get() = children.size
        override fun child(index: Int): CaptureTreeNode? = children[index]
        override fun describe(path: String, parent: String?): CapturedNode {
            check(!failDescribe) { "DO NOT LOG PRIVATE FAILURE" }
            return CapturedNode(path, parent, text = value, description = value, state = value, semantics = facts)
        }
        override fun close() { closed++ }
    }
    private fun read(root: FakeNode, include: Boolean = true, limits: CaptureTreeReader.Limits = CaptureTreeReader.Limits()) =
        CaptureTreeReader.read(root, 0, "fixture", include, { 0L }, limits)
    private fun json(f: CaptureFrame): String = ConversationArchive("test", 1, "now", CaptureOptions()).apply { append(f) }.json()
    private val ids = DetailActionIds(1, 2, 3, 4)
    private fun node(path: String = "0/0", visible: Boolean = true, expand: Boolean = true, show: Boolean = false,
                     text: String = "Analysis panel", state: Int? = null, click: Boolean = false, scroll: Boolean = false): CapturedNode {
        val actions = mutableListOf<NodeAction>()
        if (expand) actions += NodeAction(ids.expand)
        if (show) actions += NodeAction(ids.show)
        if (click) actions += NodeAction(ids.click)
        return CapturedNode(path, path.substringBeforeLast('/', ""), text = text, semantics = NodeSemantics(
            flags = mapOf("visibleToUser" to visible, "enabled" to true, "clickable" to click, "scrollable" to scroll),
            numbers = state?.let { mapOf("expandedState" to it) }.orEmpty(), actions = actions))
    }
    private fun plan(vararg nodes: CapturedNode, options: CaptureOptions = CaptureOptions()) =
        DetailPlanner.candidates(CaptureFrame(0, "fixture", nodes.toList()), options, ids)

    @Test fun offscreenExposedTextIsNotDiscarded() {
        val f = read(FakeNode(children = listOf(FakeNode("OUTSIDE", visible = false))))
        check(f.nodes.any { it.text == "OUTSIDE" && it.semantics.flags["visibleToUser"] == false })
        check(f.diagnostics["nonvisible_nodes_exposed"] == 1)
    }
    @Test fun visibleOnlyStillVisitsVisibleChildrenOfInvisibleContainers() {
        val f = read(FakeNode("OMIT", visible = false, children = listOf(FakeNode("KEEP"))), include = false)
        check(f.nodes.any { it.text == "KEEP" }); check(f.nodes.none { it.text == "OMIT" })
    }
    @Test fun invisibleParentCannotLeakFilteredChildAggregate() {
        val f = read(FakeNode("HIDDEN", children = listOf(FakeNode("HIDDEN", visible = false))), include = false)
        check(!json(f).contains("HIDDEN")); check(f.nodes.size == 2)
    }
    @Test fun privateOffscreenSubtreeAndAncestorLabelsAreRedacted() {
        val facts = NodeSemantics(uniqueId = "SECRET", actions = listOf(NodeAction(1, "SECRET")))
        val secret = FakeNode("SECRET", visible = false, privateSubtree = true)
        val f = read(FakeNode("SECRET", children = listOf(secret), facts = facts))
        check(!json(f).contains("SECRET")); check(secret.closed == 1)
        check(f.diagnostics["private_subtrees_excluded"] == 1)
        check(f.nodes.single().semantics.actions.single().id == 1)
    }
    @Test fun nullChildrenAreReportedAndAggregateTextIsOmitted() {
        val f = read(FakeNode("UNINSPECTED", children = listOf(null)))
        check(!json(f).contains("UNINSPECTED")); check(f.diagnostics["unavailable_children"] == 1)
    }
    @Test fun collapsedProviderDoesNotInventAbsentChildren() {
        val f = read(FakeNode("Collapsed control", facts = NodeSemantics(numbers = mapOf("expandedState" to 1))))
        check(f.nodes.size == 1); check(!json(f).contains("invented reasoning"))
    }
    @Test fun expandedSnapshotRetainsNewProviderChildren() {
        val before = read(FakeNode("Details"))
        val after = read(FakeNode("Details", children = listOf(FakeNode("EXPOSED RESULT"))))
        val a = ConversationArchive("test", 1, "now", CaptureOptions())
        a.append(before); a.append(after)
        check(a.frames.size == 2); check(a.frames.last().nodes.any { it.text == "EXPOSED RESULT" })
    }
    @Test fun everyAcquiredChildIsClosedEvenOnFailure() {
        val child = FakeNode().apply { failDescribe = true }
        val root = FakeNode(children = listOf(child))
        check(runCatching { read(root) }.isFailure); check(child.closed == 1); check(root.closed == 0)
    }
    @Test fun treeNodeLimitIsExplicit() {
        val f = read(FakeNode(children = listOf(FakeNode("one"), FakeNode("two"))), limits = CaptureTreeReader.Limits(nodes = 2))
        check(f.clipped); check(f.nodes.size <= 2); check(f.diagnostics.containsKey("traversal_limit"))
    }
    @Test fun treeDepthLimitIsExplicit() {
        val f = read(FakeNode(children = listOf(FakeNode(children = listOf(FakeNode("too deep"))))), limits = CaptureTreeReader.Limits(depth = 1))
        check(f.clipped); check(f.nodes.none { it.text == "too deep" })
    }
    @Test fun treeTimeLimitIsExplicit() {
        var tick = 0L
        val f = CaptureTreeReader.read(FakeNode(children = listOf(FakeNode("late"))), 0, "test", true, { tick++ }, CaptureTreeReader.Limits(millis = 1))
        check(f.clipped); check(f.nodes.isEmpty())
    }
    @Test fun actionMetadataIsBoundedAndUnicodeJsonIsValid() {
        val f = read(FakeNode(facts = NodeSemantics(actions = (1..40).map { NodeAction(it, "x".repeat(300)) })))
        check(f.clipped); check(f.nodes.single().semantics.actions.size == 32)
        check(f.nodes.single().semantics.actions.all { it.label.length <= 256 })
        check(NodeSemantics(uniqueId = "😀\n\"").json().contains("\\ud83d\\ude00"))
    }
    @Test fun structuredFactsAreDetachedAndCountTowardArchiveBudget() {
        val flags = mutableMapOf("visibleToUser" to false)
        val a = ConversationArchive("test", 1, "now", CaptureOptions())
        a.append(CaptureFrame(0, "test", listOf(CapturedNode("0", null, semantics = NodeSemantics(flags = flags)))))
        flags["visibleToUser"] = true
        check(a.frames.single().nodes.single().semantics.flags["visibleToUser"] == false)
        val small = ConversationArchive("test", 1, "now", CaptureOptions(maxChars = 1000))
        small.append(CaptureFrame(0, "test", listOf(CapturedNode("0", null, semantics = NodeSemantics(uniqueId = "x".repeat(1500))))))
        check(small.truncated)
    }
    @Test fun semanticExpandDoesNotNeedEnglishOrKnownLabels() {
        check(plan(node(text = "Analyse personnalisée")).single().actionId == ids.expand)
    }
    @Test fun explicitExpandedStateOverridesMisleadingCaption() {
        check(plan(node(text = "Show details", state = 3, click = true)).isEmpty())
        check(plan(node(text = "Show details", expand = false, state = 2, click = true)).isEmpty())
        check(plan(node(text = "Show details", expand = false, state = 1, click = true)).single().actionId == ids.click)
        check(plan(node(state = 2)).single().actionId == ids.expand)
    }
    @Test fun outsideNativeExpandPrecedesShowRequestAndNeverClicksOffscreen() {
        val p = plan(node(visible = false, show = true))
        check(p.map { it.progress } == listOf(DetailProgress.EXPAND_REQUESTED, DetailProgress.SHOW_REQUESTED))
        val c = plan(node(visible = false, expand = false, show = true, click = true, state = 1, text = "Show details"))
        check(c.single().actionId == ids.show)
    }
    @Test fun visibleDisclosuresTakePriorityOverOutsideNodes() {
        val p = plan(node("0/0", visible = false), node("0/1"))
        check(p.first().node.path == "0/1")
    }
    @Test fun manualModeRequestsNeitherRevealNorNestedScroll() {
        val p = plan(node(visible = false, show = true), node("0/1", expand = false, scroll = true), options = CaptureOptions(autoScroll = false))
        check(p.single().progress == DetailProgress.EXPAND_REQUESTED)
    }
    @Test fun visibleOnlyDisablesOffscreenActions() {
        check(plan(node(visible = false, show = true), options = CaptureOptions(includeOffscreenNodes = false)).isEmpty())
    }
    @Test fun disabledLinkAndRedactedNodesAreNotActuated() {
        for (flag in listOf("link", "redactedDescendant")) {
            val n = node(); check(plan(n.copy(semantics = n.semantics.copy(flags = n.semantics.flags + (flag to true)))).isEmpty())
        }
        val n = node(); check(plan(n.copy(semantics = n.semantics.copy(flags = n.semantics.flags + ("enabled" to false)))).isEmpty())
    }
    @Test fun advertisedClickIsRequiredForToggleFallback() {
        val n = node(expand = false, click = true, text = "Show details", state = 1)
        check(plan(n.copy(semantics = n.semantics.copy(actions = emptyList()))).isEmpty())
    }
    @Test fun nestedContainersAreVisitedDeepestFirstAndNotTheMainRoot() {
        val p = plan(node("0", expand = false, scroll = true), node("0/1", expand = false, scroll = true), node("0/1/2", expand = false, scroll = true))
        check(p.map { it.node.path } == listOf("0/1/2", "0/1"))
    }
    @Test fun disablingExpansionDoesNotDisableInnerScrolling() {
        val p = plan(node(), node("0/1", expand = false, scroll = true), options = CaptureOptions(expandDetails = false))
        check(p.single().progress == DetailProgress.NESTED_SCROLL_REQUESTED)
    }
    @Test fun innerScrollNoProgressTerminatesAndAllowsForwardAfterSeek() {
        val attempted = mutableSetOf<String>()
        check(InnerScrollPolicy.direction("panel", "same", true, attempted) == false)
        check(InnerScrollPolicy.direction("panel", "same", true, attempted) == true)
        check(InnerScrollPolicy.direction("panel", "same", true, attempted) == null)
    }
    @Test fun unavailableBackwardActionDoesNotLoseInnerForwardPass() {
        val a = mutableSetOf<String>()
        check(InnerScrollPolicy.direction("p", "a", true, a) == false)
        InnerScrollPolicy.backwardUnavailable("p", a)
        check(InnerScrollPolicy.direction("p", "a", true, a) == true)
        check(InnerScrollPolicy.direction("p", "b", true, a) == true)
    }
    @Test fun recycledPathsGetANewEpochRatherThanGlobalTextDedup() {
        val a = mutableSetOf<String>()
        check(InnerScrollPolicy.direction("reused/path", "same reply", false, a) == true)
        check(InnerScrollPolicy.direction("reused/path", "same reply", false, a) == null)
        a.clear()
        check(InnerScrollPolicy.direction("reused/path", "same reply", false, a) == true)
    }
    @Test fun sessionCountsRevealAndInnerScrollSeparatelyFromExpansion() {
        val requests = ArrayDeque(listOf(DetailProgress.SHOW_REQUESTED, DetailProgress.NESTED_SCROLL_REQUESTED, DetailProgress.EXPAND_REQUESTED))
        var calls = 0
        val driver = object : CaptureDriver {
            override fun frame(elapsed: Long, phase: String) = CaptureFrame(elapsed, phase, listOf(node()))
            override fun scroll(forward: Boolean) = false
            override fun expand(attempted: MutableSet<String>) = error("Use typed operations")
            override fun advanceDetails(attempted: MutableSet<String>, options: CaptureOptions): DetailProgress { calls++; return requests.removeFirstOrNull() ?: DetailProgress.NONE }
        }
        val archive = ConversationArchive("test", 1, "now", CaptureOptions(autoScroll = false))
        val s = CaptureSession(archive, driver)
        repeat(6) { s.step(it * 700L) }
        check(archive.expanded == 1 && archive.showOnScreenRequests == 1 && archive.nestedScrollRequests == 1)
        check(archive.text().contains("Effects are unverified")); s.stop(); val before = calls
        repeat(10) { s.step(5000) }; check(calls == before)
    }
}
