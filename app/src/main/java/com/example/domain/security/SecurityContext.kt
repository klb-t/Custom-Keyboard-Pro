package com.example.domain.security

/**
 * SecurityContext (Zasada 61).
 * Central policy object for privacy decisions.
 */
data class SecurityContext(
    val allowLearning: Boolean = true,
    val allowHistory: Boolean = true,
    val allowClipboardCapture: Boolean = true,
    val allowContextCapture: Boolean = true,
    val allowPersistence: Boolean = true,
    val allowLocalAI: Boolean = true,
    val allowExternalProcessing: Boolean = false, // Zewnętrzne wyłączone domyślnie
    val allowScreenContext: Boolean = false
)

object SecurityContextResolver {
    
    /**
     * Zwraca konfigurację uwzględniając wymuszone ograniczenia np. pól na hasła (Zasada 62).
     */
    fun resolvePolicy(
        isPasswordField: Boolean,
        isIncognitoMode: Boolean,
        userSettings: SecurityContext
    ): SecurityContext {
        if (isPasswordField || isIncognitoMode) {
            return userSettings.copy(
                allowLearning = false,
                allowHistory = false,
                allowContextCapture = false,
                allowExternalProcessing = false,
                allowScreenContext = false
            )
        }
        return userSettings
    }
}
