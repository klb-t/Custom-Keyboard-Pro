package com.example.domain.security

data class SecurityContext(
    val allowLearning: Boolean = true,
    val allowHistory: Boolean = true,
    val allowClipboardCapture: Boolean = true,
    val allowContextCapture: Boolean = true,
    val allowPersistence: Boolean = true,
    val allowLocalAI: Boolean = true,
    val allowExternalProcessing: Boolean = false,
    val allowScreenContext: Boolean = false
)

object SecurityContextResolver {
    fun resolvePolicy(
        isPasswordField: Boolean,
        isIncognitoMode: Boolean,
        isSensitiveApp: Boolean,
        userSettings: SecurityContext
    ): SecurityContext {
        if (isPasswordField || isIncognitoMode || isSensitiveApp) {
            return userSettings.copy(
                allowLearning = false,
                allowHistory = false,
                allowClipboardCapture = false,
                allowContextCapture = false,
                allowPersistence = false,
                allowExternalProcessing = false,
                allowScreenContext = false
            )
        }
        return userSettings
    }
}
