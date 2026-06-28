package nl.vdzon.softwarefactory.web.controllers

/**
 * Validatie van een door de gebruiker meegegeven redirect-doel (`next`/`returnTo`).
 *
 * Alleen lokale paden zijn toegestaan: de waarde moet met een enkele `/` beginnen. Een leidende
 * `//` (protocol-relatieve URL) én een leidende `/\` worden geweigerd, omdat browsers een backslash
 * naar een forward slash normaliseren — `/\evil.com` wordt dan `//evil.com` en zou een open redirect
 * naar een externe host opleveren. Bij twijfel valt de waarde terug op een veilig default-pad.
 */
internal object SafeRedirect {
    fun localPath(value: String?, default: String): String =
        value?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/\\") } ?: default
}
