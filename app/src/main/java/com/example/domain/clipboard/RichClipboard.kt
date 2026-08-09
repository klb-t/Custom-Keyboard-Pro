package com.example.domain.clipboard

import java.util.UUID

/**
 * Clipboard - nie lista stringów, lecz object store (Zasada 18)
 * Capability model zamiast wyjątków (Zasada 23, 38)
 * Domain model is the canonical one (Zasada 7)
 */
data class ClipboardItem(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val representations: List<ContentRepresentation>,
    val metadata: Map<String, String> = emptyMap(),
    val isPinned: Boolean = false,
    val editHistory: List<String> = emptyList() // ID poprzednich wersji w ramach edycji
)

/**
 * Explicit typed storage representations (Zasada 6).
 */
sealed interface ContentRepresentation {
    val mimeType: String

    data class InlineText(
        override val mimeType: String = "text/plain",
        val text: String
    ) : ContentRepresentation

    data class InlineBinary(
        override val mimeType: String,
        val data: ByteArray
    ) : ContentRepresentation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as InlineBinary
            if (mimeType != other.mimeType) return false
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            return 31 * mimeType.hashCode() + data.contentHashCode()
        }
    }

    data class UriReference(
        override val mimeType: String,
        val uri: String
    ) : ContentRepresentation

    data class LocalMaterializedFile(
        override val mimeType: String,
        val filePath: String
    ) : ContentRepresentation
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
