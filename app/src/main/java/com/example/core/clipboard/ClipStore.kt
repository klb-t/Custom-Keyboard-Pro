package com.example.core.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.core.data.ClipboardEntity
import com.example.util.AppLogger
import java.io.File
import java.util.UUID

/**
 * Keeping what was copied, including the parts of it that are not text.
 *
 * The thing this fixes is not obvious and was already broken: a `content://` URI on
 * the clipboard carries a *temporary* read grant, tied to that clip and to the app
 * that put it there. Storing the URI string — which is what the previous version did
 * — produces a history entry that works for about as long as the user looks at it and
 * is unreadable by the time they want it back. Nothing reports this; the entry is
 * simply there and dead.
 *
 * So the bytes are copied at the moment of capture, while the grant is still good,
 * into the app's own storage. That is the only moment at which it is possible.
 *
 * Handing one back out is the mirror image: an app being pasted into cannot read our
 * files either, so the paste goes out as a [FileProvider] URI with a grant attached
 * to the clip.
 */
object ClipStore {

    private const val DIR = "clips"
    const val AUTHORITY_SUFFIX = ".clips"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    private fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    // -----------------------------------------------------------------------
    // Taking a copy
    // -----------------------------------------------------------------------

    /**
     * Copies what [uri] points at into our own storage, and returns the file.
     *
     * Null when it could not be read — which is normal rather than exceptional: the
     * source app may have revoked the grant already, or may be offering something it
     * cannot actually produce.
     */
    fun capture(context: Context, uri: Uri, maxBytes: Long): File? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: guessMime(uri.toString())
        val file = File(dir(context), "${UUID.randomUUID()}${extensionFor(mime, uri)}")
        return try {
            var written = 0L
            var tooBig = false
            val opened = resolver.openInputStream(uri)
            if (opened == null) {
                file.delete()
                return null
            }
            opened.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (maxBytes > 0 && written > maxBytes) {
                            tooBig = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            when {
                tooBig -> {
                    AppLogger.d(
                        "ClipStore",
                        "not keeping $uri: over the ${maxBytes / 1_000_000}MB limit"
                    )
                    file.delete()
                    null
                }
                file.length() == 0L -> {
                    file.delete()
                    null
                }
                else -> file
            }
        } catch (e: Exception) {
            // The commonest cause is a grant that has already lapsed, which is the
            // exact failure this whole mechanism exists to get ahead of.
            AppLogger.d("ClipStore", "could not keep $uri: ${e.message}")
            file.delete()
            null
        }
    }

    fun fileFor(entity: ClipboardEntity): File? =
        entity.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }

    fun delete(entity: ClipboardEntity) {
        fileFor(entity)?.delete()
    }

    /** Files no database row points at any more. Keeps "clear all" honest on disk. */
    fun sweepOrphans(context: Context, keep: Collection<String>) {
        val kept = keep.toSet()
        dir(context).listFiles()?.forEach { file ->
            if (file.absolutePath !in kept) file.delete()
        }
    }

    // -----------------------------------------------------------------------
    // Handing one back
    // -----------------------------------------------------------------------

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Builds one clip out of several entries.
     *
     * This is the composite. Android's clipboard has a single slot and no "add to"
     * operation, so two things copied in two different apps can never meet there on
     * their own. They can meet *here*, because the history is ours, and a [ClipData]
     * genuinely does hold several items.
     *
     * What this cannot do is make the receiving app read them. Most read
     * `getItemAt(0)` and nothing else. That is why the parts are ordered and why a
     * text rendition of the whole thing goes first: in an app that takes one item the
     * user gets the summary rather than an arbitrary fragment, and in an app that
     * takes more they get everything. The degradation is the honest best available,
     * not a shortcoming of this code.
     */
    fun compose(
        context: Context,
        entries: List<ClipboardEntity>,
        label: String = "Several things"
    ): ClipData? {
        if (entries.isEmpty()) return null

        val items = mutableListOf<ClipData.Item>()
        val mimeTypes = linkedSetOf<String>()

        val summary = entries.joinToString("\n") { entry ->
            if (entry.isText) entry.content else (entry.label ?: describe(entry))
        }
        items += ClipData.Item(summary)
        mimeTypes += ClipDescription.MIMETYPE_TEXT_PLAIN

        entries.forEach { entry ->
            if (entry.isText) {
                items += ClipData.Item(entry.content)
                mimeTypes += ClipDescription.MIMETYPE_TEXT_PLAIN
            } else {
                val file = fileFor(entry)
                if (file != null) {
                    val uri = runCatching { shareUri(context, file) }.getOrNull()
                    if (uri != null) {
                        items += ClipData.Item(uri)
                        mimeTypes += entry.mime.ifBlank { "application/octet-stream" }
                    }
                }
            }
        }

        val description = ClipDescription(label, mimeTypes.toTypedArray())
        val clip = ClipData(description, items.first())
        items.drop(1).forEach { clip.addItem(it) }
        return clip
    }

    /**
     * One entry as a clip. The single-item case, kept separate because it is the common one.
     *
     * The resolver is not optional, however tempting it looks: [ClipData.newUri] asks
     * it for the URI's type in order to fill in the clip's description, so a null one
     * throws and the whole thing silently becomes "tapping a picture does nothing".
     */
    fun single(context: Context, entry: ClipboardEntity): ClipData? {
        if (entry.isText) return ClipData.newPlainText(entry.label ?: "Text", entry.content)
        val file = fileFor(entry) ?: return null
        return runCatching {
            val uri = shareUri(context, file)
            ClipData.newUri(context.contentResolver, entry.label ?: "File", uri)
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // Naming things
    // -----------------------------------------------------------------------

    fun guessMime(value: String): String = when {
        value.endsWith(".png", true) -> "image/png"
        value.endsWith(".jpg", true) || value.endsWith(".jpeg", true) -> "image/jpeg"
        value.endsWith(".webp", true) -> "image/webp"
        value.endsWith(".gif", true) -> "image/gif"
        value.endsWith(".pdf", true) -> "application/pdf"
        value.endsWith(".mp4", true) -> "video/mp4"
        value.endsWith(".mp3", true) -> "audio/mpeg"
        value.endsWith(".txt", true) -> "text/plain"
        else -> "application/octet-stream"
    }

    fun extensionFor(mime: String, uri: Uri? = null): String {
        val fromMime = when {
            mime == "image/jpeg" -> ".jpg"
            mime == "image/svg+xml" -> ".svg"
            mime.startsWith("image/") -> "." + mime.removePrefix("image/").substringBefore('+')
            mime == "application/pdf" -> ".pdf"
            mime == "audio/mpeg" -> ".mp3"
            mime.startsWith("video/") -> "." + mime.removePrefix("video/")
            mime.startsWith("audio/") -> "." + mime.removePrefix("audio/")
            mime.startsWith("text/") -> ".txt"
            else -> ""
        }
        if (fromMime.length > 1) return fromMime
        val last = uri?.lastPathSegment.orEmpty()
        val dot = last.lastIndexOf('.')
        return if (dot in 1..(last.length - 2)) last.substring(dot) else ".bin"
    }

    /** What to show for something that is not text. */
    fun describe(entity: ClipboardEntity): String {
        val kind = when {
            entity.mime.startsWith("image/") -> "Picture"
            entity.mime.startsWith("video/") -> "Video"
            entity.mime.startsWith("audio/") -> "Audio"
            entity.mime == "application/pdf" -> "PDF"
            else -> "File"
        }
        val size = when {
            entity.sizeBytes <= 0L -> ""
            entity.sizeBytes < 1024 -> " · ${entity.sizeBytes} B"
            entity.sizeBytes < 1024 * 1024 -> " · ${entity.sizeBytes / 1024} kB"
            else -> " · ${"%.1f".format(entity.sizeBytes / 1024.0 / 1024.0)} MB"
        }
        val from = entity.sourcePackage?.substringAfterLast('.')?.let { " · from $it" }.orEmpty()
        return "$kind$size$from"
    }
}
