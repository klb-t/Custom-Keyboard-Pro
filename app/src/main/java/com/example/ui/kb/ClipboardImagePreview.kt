package com.example.ui.kb

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.core.clipboard.ClipStore
import com.example.core.data.ClipboardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode a bounded thumbnail off the typing thread, including in Trash. */
@Composable
internal fun ClipboardImagePreview(item: ClipboardEntity) {
    if (item.isText || !item.mime.startsWith("image/")) return
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = ClipStore.fileFor(item) ?: return@runCatching null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 192) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    preview?.let { Image(it, "Clipboard image preview", Modifier.size(52.dp).padding(end = 8.dp), contentScale = ContentScale.Fit) }
}
