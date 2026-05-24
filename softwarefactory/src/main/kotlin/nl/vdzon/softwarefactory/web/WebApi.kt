package nl.vdzon.softwarefactory.web

/**
 * Public API marker of the web module.
 *
 * The web module owns all HTTP adapters: dashboard pages, internal agent
 * callbacks and knowledge endpoints. It should remain thin and delegate
 * business behavior to the public APIs of the owning modules.
 */
interface WebApi

