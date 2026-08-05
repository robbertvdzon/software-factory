package nl.vdzon.softwarefactory.maintenance.services

import org.slf4j.Logger

/**
 * Uitkomst van één paginaophaling bij de GitHub-API: gelukt (met de geparste items én het ruwe
 * aantal array-elementen van die pagina) of mislukt (niet-2xx, timeout, rate limit).
 *
 * Het ruwe aantal staat er los in omdat de parsers ongeldige elementen weggooien: zou de lus op
 * `items.size` stoppen, dan zou één onparseerbare regel op een volle pagina de rest van de historie
 * onzichtbaar maken.
 */
internal sealed interface GitHubPage<out T> {
    data class Fetched<T>(val items: List<T>, val rawCount: Int = items.size) : GitHubPage<T>

    data object Failed : GitHubPage<Nothing>
}

/**
 * Wat de paginatielus opleverde: alles wat is opgehaald, plus of dat compleet is.
 *
 * [failedPage] is de pagina die faalde (null als er niets faalde); [pageLimitReached] betekent dat
 * de lus op de ingestelde bovengrens stopte terwijl GitHub nog doorleverde. In beide gevallen is
 * [complete] `false` — voor een gewone lijst is dat alleen een waarschuwing waard, voor een
 * *veiligheids*lijst (beschermde sha's) is het reden om de opruiming over te slaan.
 */
internal data class PagedItems<T>(
    val items: List<T>,
    val pagesFetched: Int,
    val failedPage: Int? = null,
    val pageLimitReached: Boolean = false,
) {
    val complete: Boolean get() = failedPage == null && !pageLimitReached
}

/**
 * De gedeelde, pure paginatielus voor de GitHub-cleanup-clients. Bewust zonder HTTP-kennis: [fetchPage]
 * krijgt alleen het paginanummer (1-based), zodat de lus zelf zonder netwerk te testen is.
 *
 * Stopt zodra een pagina minder dan [perPage] elementen teruggeeft (dus géén extra call voor de
 * volgende pagina), zodra een pagina faalt (teruggeven wat al is opgehaald) of zodra [maxPages] is
 * opgehaald.
 */
internal object GitHubPagination {
    const val PER_PAGE = 100

    fun <T> fetchAllPages(
        maxPages: Int,
        perPage: Int = PER_PAGE,
        fetchPage: (page: Int) -> GitHubPage<T>,
    ): PagedItems<T> {
        val limit = maxPages.coerceAtLeast(1)
        val items = mutableListOf<T>()
        var page = 1
        var fetched = 0
        var failedPage: Int? = null
        var mayHaveMore = true
        while (mayHaveMore && page <= limit) {
            when (val result = fetchPage(page)) {
                is GitHubPage.Failed -> {
                    failedPage = page
                    mayHaveMore = false
                }

                is GitHubPage.Fetched -> {
                    items += result.items
                    fetched = page
                    mayHaveMore = result.rawCount >= perPage
                    page++
                }
            }
        }
        return PagedItems(
            items = items,
            pagesFetched = fetched,
            failedPage = failedPage,
            pageLimitReached = mayHaveMore && failedPage == null,
        )
    }

    /** Eén waarschuwingsregel met naam en aantal als er iets ontbreekt; stil als het resultaat compleet is. */
    fun <T> warnIfIncomplete(logger: Logger, subject: String, paged: PagedItems<T>) {
        if (paged.failedPage != null) {
            logger.warn(
                "GitHub-paginatie voor {} faalde op pagina {}; teruggegeven wat al opgehaald was ({} items).",
                subject, paged.failedPage, paged.items.size,
            )
        } else if (paged.pageLimitReached) {
            logger.warn(
                "GitHub-paginatie voor {} raakte de paginagrens ({} pagina's, {} items opgehaald); " +
                    "verhoog sf.maintenance.github-page-limit als er meer verwacht wordt.",
                subject, paged.pagesFetched, paged.items.size,
            )
        }
    }
}
