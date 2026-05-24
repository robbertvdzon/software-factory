package nl.vdzon.softwarefactory.support

import nl.vdzon.softwarefactory.support.services.SecretRedactor

/**
 * Public API of the support module.
 *
 * The support module contains cross-cutting helpers that must not depend on
 * business modules, such as secret redaction.
 */
interface SupportApi {
    fun redact(value: String): String

    companion object {
        fun default(): SupportApi = DefaultSupportApi
    }
}

private object DefaultSupportApi : SupportApi {
    override fun redact(value: String): String =
        SecretRedactor.redact(value)
}
