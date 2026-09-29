package com.example.io.capture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.core.capture.ConversationArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The capture remains local until an explicit Copy or system document-picker Save. */
class ConversationCaptureReviewActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var archive: ConversationArchive? = null
    private var pendingFormat: String? = null
    private var plain: String? = null
    private var preview: TextView? = null
    private var previewJob: Job? = null
    private var treeMode = false
    private fun tell(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        pendingFormat = savedInstanceState?.getString("capture-format")
        archive = ConversationCaptureUi.result
        treeMode = savedInstanceState?.getBoolean("capture-tree") ?: (archive?.reason == "tree_inspection_no_actions")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        val title = TextView(this).apply { text = "Conversation capture · select, copy or save\nRaw JSON preserves exposed hierarchy and viewport revisions. This is not proof of a complete conversation." }
        root.addView(title)
        val controls = LinearLayout(this)
        fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }
        controls.addView(button("Copy preview") { copy() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("Save text") { save("text") }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("Save JSON") { save("json") }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        val views = LinearLayout(this)
        views.addView(button("Show text") { render(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        views.addView(button("Show tree") { render(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        views.addView(button("Save tree") { save("tree") }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(views)
        val text = TextView(this).apply { setTextIsSelectable(true); this.text = "Preparing local preview…"; textSize = 15f }
        preview = text
        root.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val gap = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(gap + b.left, gap + b.top, gap + b.right, gap + b.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        val a = archive
        if (a == null) {
            text.text = "No capture remains in memory (for example after process termination). Return to the conversation and start a new capture."
            return
        }
        render(treeMode)
    }
    private fun render(tree: Boolean) {
        val a = archive ?: return
        treeMode = tree; plain = null
        previewJob?.cancel()
        preview?.text = "Preparing local preview…"
        previewJob = scope.launch {
            plain = withContext(Dispatchers.Default) { if (tree) a.treeText() else a.text() }
            preview?.text = plain
        }
    }
    private fun copy() {
        val text = plain ?: return tell("Preview is not ready")
        if (text.toByteArray(Charsets.UTF_8).size > 240_000) return tell("Too large for safe clipboard transfer. Use Save text or Save JSON.")
        try {
            val clip = ClipData.newPlainText("IO Matrix conversation capture", text)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            tell("Copied the current local preview")
        } catch (_: Exception) { tell("Clipboard refused the content. Use Save text instead.") }
    }
    private fun save(format: String) {
        if (archive == null) return tell("No capture in memory")
        pendingFormat = format
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (format == "json") "application/json" else "text/plain")
                .putExtra(Intent.EXTRA_TITLE, "IO-Matrix-conversation.${if (format == "json") "json" else if (format == "tree") "tree.txt" else "txt"}"), 7412)
        } catch (_: Exception) { pendingFormat = null; tell("No document picker is available") }
    }
    @Deprecated("Platform Activity result bridge supports the existing API 24 host")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7412) return
        val format = pendingFormat
        pendingFormat = null
        val uri = data?.data
        val a = archive
        if (resultCode != RESULT_OK || format == null || uri == null || a == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val content = when (format) { "json" -> a.json(); "tree" -> a.treeText(); else -> a.text() }
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("No document stream")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }
                tell("Capture saved")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { tell("Save failed; the selected document may be partial. The capture is still in memory.") }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("capture-format", pendingFormat)
        outState.putBoolean("capture-tree", treeMode)
    }
    override fun onDestroy() {
        scope.cancel()
        if (isFinishing && ConversationCaptureUi.result === archive) ConversationCaptureUi.result = null
        super.onDestroy()
    }
}
