package com.example.core.security

/** Marks every field in a private form, including identifiers that are not passwords. */
object PrivateInputContract {
    const val MARKER = "io.matrix.private"
    fun isPrivate(options: String?): Boolean = options.orEmpty().split(',', ';').any { it.trim() == MARKER }
}
