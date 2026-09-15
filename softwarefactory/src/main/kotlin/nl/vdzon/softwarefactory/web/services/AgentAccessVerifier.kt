package nl.vdzon.softwarefactory.web.services

import java.security.MessageDigest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** Each environment has its own key. Production keys stay outside Agent Runtime. */
@Component
class AgentAccessVerifier(
    @param:Value("\${AI_ACCESS_TOKEN:}") private val token: String,
    @param:Value("\${AI_ACCESS_EMAILS:}") emails: String,
    @param:Value("\${AI_ACCESS_ALLOWED_ORIGINS:}") origins: String,
) {
    private val allowedEmails = emails.split(',').map(String::trim).map(String::lowercase).filter(String::isNotBlank).toSet()
    private val allowedOrigins = origins.split(',').map(String::trim).filter(String::isNotBlank).toSet()
    init { require(token.isEmpty() || token.length >= 32) { "AI_ACCESS_TOKEN must contain at least 32 characters" } }

    fun verify(provided: String?, email: String, origin: String?): String {
        if (token.isBlank() || provided.isNullOrBlank() || provided.length > 4096 ||
            !MessageDigest.isEqual(token.toByteArray(), provided.toByteArray())) reject()
        if (origin != null && allowedOrigins.none { configured -> Regex(Regex.escape(configured).replace("{pr}", "\\E[0-9]+\\Q")).matches(origin) }) reject()
        val identity = email.trim().lowercase()
        if (identity !in allowedEmails) reject()
        return identity
    }
    private fun reject(): Nothing = throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent login rejected")
}
