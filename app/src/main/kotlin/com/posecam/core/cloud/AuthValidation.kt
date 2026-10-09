package com.posecam.core.cloud

/** Checks the sign-in / sign-up form before anything is sent, with the wording the collector sees. */
object AuthValidation {
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 128
    const val MAX_NAME = 80

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun normalizeEmail(email: String): String = email.trim().lowercase()

    /**
     * Null when the form can be submitted; otherwise the first thing to fix. [confirm] is null when signing in; [name]
     * is null when signing in and required (not blank) when signing up.
     */
    fun problem(email: String, password: String, confirm: String?, name: String? = null): String? = when {
        confirm != null && name.isNullOrBlank() -> "Enter your name."
        confirm != null && name!!.trim().length > MAX_NAME -> "The name is too long ($MAX_NAME characters at most)."
        email.isBlank() -> "Enter your email."
        email.trim().length > 254 || !EMAIL.matches(email.trim()) -> "That doesn't look like an email address."
        password.isEmpty() -> "Enter your password."
        confirm != null && password.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters for the password."
        password.length > MAX_PASSWORD -> "The password is too long ($MAX_PASSWORD characters at most)."
        confirm != null && confirm != password -> "The two passwords don't match."
        else -> null
    }

    /** What to tell the collector when a request failed; the server's reason when it gave one fit to show. */
    fun messageFor(error: Throwable): String = when (error) {
        is CloudNetworkException -> "Can't reach the server. Check your internet connection and try again."
        is CloudHttpException -> when {
            error.status >= 500 -> "The server had a problem. Try again in a moment."
            error.code == "MALFORMED_RESPONSE" -> "The server sent something unexpected. Try again in a moment."
            else -> error.reason.replaceFirstChar { it.uppercase() }
        }
        else -> "Something went wrong: ${error.message ?: error.javaClass.simpleName}"
    }
}
