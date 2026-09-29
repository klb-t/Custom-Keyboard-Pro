"""Compile/execute the Android adapter against tiny deterministic test doubles.
This checks adapter control flow and Kotlin syntax, NOT Android SDK/API compatibility,
Binder behaviour, UI integration, device permissions or real application acceptance.
The real Gradle build/lint/device gates remain mandatory.
"""
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
'Context.kt': '''package android.content
open class Context {
 companion object { const val KEYGUARD_SERVICE = "keyguard" }
 open fun getSystemService(name: String): Any? = null
}
''',
'Flags.kt': '''package android.accessibilityservice
class AccessibilityServiceInfo(var flags: Int = 0) {
 companion object {
  const val FLAG_REPORT_VIEW_IDS = 16
  const val FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 64
  const val FLAG_INCLUDE_NOT_IMPORTANT_VIEWS = 2
 }
}
''',
'Keyguard.kt': '''package android.app
class KeyguardManager(var isKeyguardLocked: Boolean = false)
''',
'Os.kt': '''package android.os
object Build { object VERSION { var SDK_INT = 36 } }
class Bundle { val values = mutableMapOf<String, Int>(); fun putInt(key: String, value: Int) { values[key] = value } }
object SystemClock { fun uptimeMillis(): Long = 0 }
''',
'Rect.kt': '''package android.graphics
class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 300, var bottom: Int = 400) {
 fun width() = right - left
 fun height() = bottom - top
 companion object { fun intersects(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom }
}
''',
'Spanned.kt': '''package android.text
interface Spanned : CharSequence { fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> }
''',
'Url.kt': '''package android.text.style
class URLSpan
''',
'Node.kt': '''package android.view.accessibility
import android.graphics.Rect
import android.os.Bundle
class NodeState(var text: CharSequence? = null) {
 var description: CharSequence? = null; var state: CharSequence? = null
 var pkg = "test.chat"; var window = 7; var type = "TextView"; var id: String? = null; var unique: String? = null
 var visible = true; var enabled = true; var editable = false; var password = false; var sensitive = false
 var scrollable = false; var clickable = false; var expanded = 0; var valid = true
 var bounds = Rect(); val children = mutableListOf<NodeState>(); var parent: NodeState? = null
 val actions = mutableListOf<Int>(); val labels = mutableMapOf<Int, String>()
 var collection: AccessibilityNodeInfo.CollectionInfo? = null
 var item: AccessibilityNodeInfo.CollectionItemInfo? = null
 var onAction: (Int, Bundle?) -> Boolean = { _, _ -> false }
}
class AccessibilityNodeInfo(val state: NodeState) {
 private var recycled = false
 init { handles++ }
 companion object {
  var handles = 0; val calls = mutableListOf<Int>()
  const val ACTION_CLICK = 16; const val ACTION_EXPAND = 262144; const val ACTION_COLLAPSE = 524288
  const val ACTION_SCROLL_FORWARD = 4096; const val ACTION_SCROLL_BACKWARD = 8192
  const val ACTION_SET_SELECTION = 131072; const val FOCUS_ACCESSIBILITY = 2
  const val ACTION_ARGUMENT_SELECTION_START_INT = "start"; const val ACTION_ARGUMENT_SELECTION_END_INT = "end"
  const val ACTION_ARGUMENT_ROW_INT = "row"; const val ACTION_ARGUMENT_COLUMN_INT = "column"
  fun obtain(n: AccessibilityNodeInfo) = AccessibilityNodeInfo(n.state)
 }
 // Fixture-specific IDs for non-legacy actions; these are not an Android ABI test.
 class AccessibilityAction(val id: Int, val label: CharSequence? = null) {
  companion object {
   val ACTION_SCROLL_DOWN = AccessibilityAction(1001); val ACTION_SCROLL_UP = AccessibilityAction(1002)
   val ACTION_SCROLL_TO_POSITION = AccessibilityAction(1003); val ACTION_SHOW_ON_SCREEN = AccessibilityAction(1004)
  }
 }
 class CollectionInfo(val rowCount: Int, val columnCount: Int, val isHierarchical: Boolean = false)
 class CollectionItemInfo(val rowIndex: Int, val columnIndex: Int)
 val text get() = state.text
 val contentDescription get() = state.description
 val stateDescription get() = state.state
 val packageName: CharSequence get() = state.pkg
 val windowId get() = state.window
 val className: CharSequence get() = state.type
 val viewIdResourceName get() = state.id
 val uniqueId get() = state.unique
 val isVisibleToUser get() = state.visible
 val isEnabled get() = state.enabled
 val isEditable get() = state.editable
 val isPassword get() = state.password
 val isAccessibilityDataSensitive get() = state.sensitive
 val isScrollable get() = state.scrollable
 val isClickable get() = state.clickable
 val isSelected = false; val isCheckable = false; val isChecked = false; val checked = 0
 val isFocusable = false; val isAccessibilityFocused = false; val isImportantForAccessibility = true; val isHeading = false
 val expandedState get() = state.expanded
 val collectionInfo get() = state.collection
 val collectionItemInfo get() = state.item
 val childCount get() = state.children.size
 val parent get() = state.parent?.let { AccessibilityNodeInfo(it) }
 val actionList get() = state.actions.map { AccessibilityAction(it, state.labels[it]) }
 fun getChild(index: Int): AccessibilityNodeInfo? = state.children.getOrNull(index)?.let { it.parent = state; AccessibilityNodeInfo(it) }
 fun refresh(): Boolean { check(!recycled); return state.valid }
 fun recycle() { check(!recycled) { "Double recycle" }; recycled = true; handles-- }
 fun getBoundsInScreen(out: Rect) { out.left=state.bounds.left;out.top=state.bounds.top;out.right=state.bounds.right;out.bottom=state.bounds.bottom }
 fun performAction(id: Int, args: Bundle? = null): Boolean { check(!recycled); calls += id; return state.onAction(id, args) }
 fun findFocus(kind: Int): AccessibilityNodeInfo? = null
}
class AccessibilityWindowInfo(val id: Int, val type: Int) {
 companion object { const val TYPE_APPLICATION = 1 }
 fun recycle() = Unit
}
''',
'Service.kt': '''package com.example.io
import android.content.Context
import android.app.KeyguardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.*
class IoAccessibilityService(var tree: NodeState) : Context() {
 companion object { var instance: IoAccessibilityService? = null }
 val packageName = "io.matrix"
 val keyguard = KeyguardManager()
 var serviceInfo: AccessibilityServiceInfo? = AccessibilityServiceInfo(512)
 val rootInActiveWindow get() = AccessibilityNodeInfo(tree)
 val windows get() = listOf(AccessibilityWindowInfo(tree.window, AccessibilityWindowInfo.TYPE_APPLICATION))
 override fun getSystemService(name: String): Any? = keyguard
}
''',
'Tests.kt': '''package com.example.io.capture
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo as N
import android.view.accessibility.NodeState as S
import com.example.core.capture.*
import com.example.io.IoAccessibilityService

fun main() {
 var passed = 0
 fun test(name: String, block: () -> Unit) { N.calls.clear(); Build.VERSION.SDK_INT=36; block(); check(N.handles==0) { "Node leak: ${N.handles}" }; passed++; println("PASS $name") }
 fun fixture(child: S, options: CaptureOptions = CaptureOptions()): Triple<AccessibilityCaptureSource, AccessibilityCaptureSource.Target, IoAccessibilityService> {
  val root=S().apply { scrollable=true; id="conversation";unique="root";children+=child }
  val service=IoAccessibilityService(root);IoAccessibilityService.instance=service
  val source=AccessibilityCaptureSource(service);source.begin(options)
  val target=source.bind();source.frame(target,0,"fixture")
  return Triple(source,target,service)
 }
 fun disclosure(visible: Boolean = true, native: Boolean = true) = S("Show details").apply {
  this.visible=visible;expanded=1;id="details";unique="detail";clickable=true
  actions+=if(native) N.ACTION_EXPAND else N.ACTION_CLICK
 }
 test("adapter preserves and restores unrelated service flags") {
  val (source,_,service)=fixture(S(),CaptureOptions(includeNotImportantViews=true))
  check(service.serviceInfo!!.flags and 2 != 0)
  service.serviceInfo!!.flags=service.serviceInfo!!.flags or 2048
  source.close();check(service.serviceInfo!!.flags==512 or 2048)
 }
 test("adapter records non-visible provider text") {
  val (source,target,_)=fixture(S("OFFSCREEN").apply { visible=false })
  check(source.frame(target,0,"test").nodes.any { it.text=="OFFSCREEN" && it.semantics.flags["visibleToUser"]==false });source.close()
 }
 test("adapter excludes private and data-sensitive descendants") {
  for (kind in 0..2) {
   val secret=S("SECRET").apply { when(kind){0->editable=true;1->password=true;else->sensitive=true} }
   val (source,target,_)=fixture(secret)
   check(source.frame(target,0,"test").nodes.none { it.text.contains("SECRET") });source.close()
  }
 }
 test("offscreen semantic expand can reveal provider children") {
  val d=disclosure(false).apply { onAction={ id,_ -> if(id==N.ACTION_EXPAND){expanded=3;children+=S("REVEALED");true}else false} }
  val (source,target,_)=fixture(d)
  check(source.advanceDetails(target,mutableSetOf())==DetailProgress.EXPAND_REQUESTED)
  check(source.frame(target,1,"after").nodes.any { it.text=="REVEALED" });source.close()
 }
 test("show-on-screen and subsequent click use separately refreshed trees") {
  val d=disclosure(false,false).apply {
   actions+=N.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
   onAction={id,_->when(id){N.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id->{visible=true;true};N.ACTION_CLICK->{expanded=3;true};else->false}}
  }
  val (source,target,_)=fixture(d);val a=mutableSetOf<String>()
  check(source.advanceDetails(target,a)==DetailProgress.SHOW_REQUESTED)
  source.frame(target,1,"after_show")
  check(source.advanceDetails(target,a)==DetailProgress.EXPAND_REQUESTED)
  check(N.calls==listOf(N.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id,N.ACTION_CLICK));source.close()
 }
 test("manual mode never requests show-on-screen") {
  val d=disclosure(false,false).apply { actions+=N.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id;onAction={_,_->true} }
  val (source,target,_)=fixture(d,CaptureOptions(autoScroll=false))
  check(source.advanceDetails(target,mutableSetOf())==DetailProgress.NONE && N.calls.isEmpty());source.close()
 }
 test("recycled node path is revalidated before an action") {
  val (source,target,service)=fixture(disclosure())
  service.tree.children[0]=disclosure().apply { text="Delete";onAction={_,_->true} }
  check(source.advanceDetails(target,mutableSetOf())==DetailProgress.NONE && N.calls.isEmpty());source.close()
 }
 test("changed child caption does not inherit permission to click a recycled row") {
  val d=disclosure(native=false).apply {text=null;children+=S("Show details");onAction={_,_->true}}
  val (source,target,_)=fixture(d)
  d.children[0].text="Delete"
  check(source.advanceDetails(target,mutableSetOf())==DetailProgress.NONE && N.calls.isEmpty());source.close()
 }
 test("changed legacy expanded description blocks a stale toggle") {
  Build.VERSION.SDK_INT=35
  val d=disclosure(native=false).apply {expanded=0;state="collapsed";onAction={_,_->true}}
  val (source,target,_)=fixture(d);d.state="expanded"
  check(source.advanceDetails(target,mutableSetOf())==DetailProgress.NONE && N.calls.isEmpty());source.close()
 }
 test("changed window blocks operations before acting") {
  val (source,target,service)=fixture(disclosure())
  service.tree.window=99
  check(runCatching{source.advanceDetails(target,mutableSetOf())}.isFailure && N.calls.isEmpty());source.close()
 }
 test("lock blocks further reads") {
  val (source,target,service)=fixture(disclosure());service.keyguard.isKeyguardLocked=true
  check(runCatching{source.frame(target,1,"locked")}.isFailure);source.close()
 }
 test("nested scroller still advances when backward is unavailable") {
  val panel=S().apply {
   scrollable=true;id="panel";bounds.bottom=150;actions+=listOf(N.ACTION_SCROLL_BACKWARD,N.ACTION_SCROLL_FORWARD)
   onAction={id,_->id==N.ACTION_SCROLL_FORWARD}
  }
  val (source,target,_)=fixture(panel,CaptureOptions(expandDetails=false))
  check(source.advanceDetails(target,mutableSetOf())==DetailProgress.NESTED_SCROLL_REQUESTED)
  check(N.calls==listOf(N.ACTION_SCROLL_BACKWARD,N.ACTION_SCROLL_FORWARD));source.close()
 }
 test("nested no-op scroll is bounded") {
  val panel=S().apply {scrollable=true;bounds.bottom=150;actions+=N.ACTION_SCROLL_FORWARD;onAction={_,_->true}}
  val (source,target,_)=fixture(panel,CaptureOptions(expandDetails=false,seekStart=false));val a=mutableSetOf<String>()
  check(source.advanceDetails(target,a)==DetailProgress.NESTED_SCROLL_REQUESTED)
  source.frame(target,1,"same")
  check(source.advanceDetails(target,a)==DetailProgress.NONE);check(N.calls.size==1);source.close()
 }
 test("collection seek uses an advertised positional action only") {
  val (source,target,service)=fixture(S())
  service.tree.collection=N.CollectionInfo(20,1)
  service.tree.actions+=N.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
  service.tree.onAction={id,b->check(id==N.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id && b!!.values[N.ACTION_ARGUMENT_ROW_INT]==0);true}
  check(source.scroll(target,false));source.close()
 }
 test("older API uses exposed state description instead of API 36 fields") {
  Build.VERSION.SDK_INT=35
  val d=disclosure(native=false).apply {expanded=0;state="collapsed";onAction={_,_->true}}
  val (source,target,_)=fixture(d)
  check(source.advanceDetails(target,mutableSetOf())==DetailProgress.EXPAND_REQUESTED);source.close()
 }
 test("full-window inspection sends no actions") {
  val (source,_,_)=fixture(disclosure().apply {onAction={_,_->error("Unexpected action")}})
  val t=source.bind(fullWindow=true);check(source.frame(t,0,"tree_inspection").nodes.size==2)
  check(N.calls.isEmpty());source.close()
 }
 println("$passed/$passed adapter test-double checks passed (NOT Android SDK/device validation)")
}
'''
}

def main():
    if not shutil.which('kotlinc') or not shutil.which('java'):
        raise SystemExit('kotlinc and Java are required')
    with tempfile.TemporaryDirectory() as directory:
        tmp = Path(directory)
        for name, content in SOURCES.items():
            (tmp / name).write_text(content)
        production = list((ROOT / 'app/src/main/java/com/example/core/capture').glob('*.kt'))
        production.append(ROOT / 'app/src/main/java/com/example/io/capture/AccessibilityCaptureSource.kt')
        subprocess.run(['kotlinc', *map(str, production), *map(str, tmp.glob('*.kt')), '-include-runtime', '-d', str(tmp/'adapter.jar')], check=True)
        subprocess.run(['java', '-jar', str(tmp/'adapter.jar')], check=True)

if __name__ == '__main__':
    main()
