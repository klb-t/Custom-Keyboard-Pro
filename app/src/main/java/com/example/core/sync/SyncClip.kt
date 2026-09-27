package com.example.core.sync

import android.util.Base64
import com.example.core.data.ClipboardEntity
import org.json.JSONObject

/** Portable content never contains a path, URI grant, source-app trust or credentials. */
object SyncClip {
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    fun validate(value: JSONObject) {
        require(value.getString("type") in listOf(ClipboardEntity.TYPE_TEXT, ClipboardEntity.TYPE_FILE))
        require(value.getString("content").length <= 200_000)
        require(value.optString("label").length <= 1000)
        require(value.getLong("timestamp") in 0 until 1_000_000_000_000_000L)
        require(value.get("pinned") is Boolean)
        if (value.getString("type") == ClipboardEntity.TYPE_FILE) {
            require(value.getString("mime").matches(Regex("image/[a-zA-Z0-9.+-]{1,80}")))
            require(value.getString("data").length <= (MAX_IMAGE_BYTES + 2) / 3 * 4)
            val bytes = bytes(value); require(bytes.size in 1..MAX_IMAGE_BYTES)
        } else require(!value.has("data"))
        require(!value.has("filePath") && !value.has("uri"))
    }
    fun bytes(value: JSONObject): ByteArray = Base64.decode(value.getString("data"), Base64.NO_WRAP)
}
