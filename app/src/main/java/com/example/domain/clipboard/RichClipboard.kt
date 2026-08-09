package com.example.domain.clipboard

import java.util.UUID

/**
 * Clipboard - nie lista stringów, lecz object store (Zasada 18)
 * Capability model zamiast wyjątków (Zasada 23, 38)
 */
data class ClipboardItem(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sourceUri: String? = null,
    val representations: List<ContentRepresentation>,
    val metadata: Map<String, String> = emptyMap(),
    val isPinned: Boolean = false,
    val editHistory: List<String> = emptyList() // ID poprzednich wersji w ramach edycji
)

data class ContentRepresentation(
    val mimeType: String,
    val payload: ByteArray, // w przypadku większych plików to będzie URI wskazujące na lokalny plik zmaterializowany (Zasada 20)
    val isLocalFile: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ContentRepresentation

        if (mimeType != other.mimeType) return false
        if (!payload.contentEquals(other.payload)) return false
        if (isLocalFile != other.isLocalFile) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + isLocalFile.hashCode()
        return result
    }
}

/**
 * Operacje na Clipboard item jako capabilities (Zasada 23)
 */
enum class ClipboardCapability {
    PREVIEW, EDIT_TEXT, COPY, PASTE, EXPORT, CONVERT, TRANSCODE, SHARE
}

interface ClipboardBackend {
    fun getCapabilities(mimeType: String): Set<ClipboardCapability>
    fun canPasteToTarget(targetMimeTypes: List<String>, itemMimeTypes: List<String>): Boolean
}
