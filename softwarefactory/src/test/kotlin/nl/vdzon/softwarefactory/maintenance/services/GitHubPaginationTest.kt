package nl.vdzon.softwarefactory.maintenance.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dekt de gedeelde paginatielus van de GitHub-cleanup-clients (SF-1938) volledig zonder HTTP: de lus
 * krijgt een "haal pagina n op"-functie, dus elke pagina-uitkomst (vol / deelpagina / mislukt) is
 * hier direct te sturen. [requestedPages] maakt "en dus géén extra call" een harde assertie.
 */
class GitHubPaginationTest {

    private val requestedPages = mutableListOf<Int>()

    @Test
    fun `voegt meerdere pagina's samen en stopt bij de eerste deelpagina zonder extra call`() {
        val sizes = mapOf(1 to 100, 2 to 100, 3 to 37)

        val result = fetch(maxPages = 20) { page -> itemsPage(page, sizes.getValue(page)) }

        assertEquals(237, result.items.size)
        assertEquals(listOf(1, 2, 3), requestedPages)
        assertEquals(listOf("p1-1", "p2-1", "p3-1"), listOf(result.items[0], result.items[100], result.items[200]))
        assertTrue(result.complete)
        assertFalse(result.pageLimitReached)
        assertNull(result.failedPage)
    }

    @Test
    fun `een lege eerste pagina levert een lege lijst op zonder tweede call`() {
        val result = fetch(maxPages = 20) { page -> itemsPage(page, 0) }

        assertEquals(emptyList<String>(), result.items)
        assertEquals(listOf(1), requestedPages)
        assertTrue(result.complete)
    }

    @Test
    fun `een lege vervolgpagina sluit de lus af zonder extra call`() {
        val result = fetch(maxPages = 20) { page -> itemsPage(page, if (page == 1) 100 else 0) }

        assertEquals(100, result.items.size)
        assertEquals(listOf(1, 2), requestedPages)
        assertTrue(result.complete)
    }

    @Test
    fun `een mislukte vervolgpagina geeft terug wat al is opgehaald`() {
        val result = fetch(maxPages = 20) { page -> if (page == 3) GitHubPage.Failed else itemsPage(page, 100) }

        assertEquals(200, result.items.size)
        assertEquals(listOf(1, 2, 3), requestedPages)
        assertEquals(3, result.failedPage)
        assertFalse(result.complete)
    }

    @Test
    fun `een mislukte eerste pagina levert een lege lijst op`() {
        val result = fetch(maxPages = 20) { GitHubPage.Failed }

        assertEquals(emptyList<String>(), result.items)
        assertEquals(listOf(1), requestedPages)
        assertEquals(1, result.failedPage)
        assertFalse(result.complete)
    }

    @Test
    fun `de paginagrens wordt gerespecteerd bij een bron die blijft doorleveren`() {
        val result = fetch(maxPages = 5) { page -> itemsPage(page, 100) }

        assertEquals(500, result.items.size)
        assertEquals(listOf(1, 2, 3, 4, 5), requestedPages)
        assertEquals(5, result.pagesFetched)
        assertTrue(result.pageLimitReached)
        assertFalse(result.complete)
        assertNull(result.failedPage)
    }

    @Test
    fun `een grens van nul wordt als één pagina behandeld in plaats van als 'nooit ophalen'`() {
        val result = fetch(maxPages = 0) { page -> itemsPage(page, 100) }

        assertEquals(100, result.items.size)
        assertEquals(listOf(1), requestedPages)
        assertTrue(result.pageLimitReached)
    }

    /**
     * Het ruwe aantal array-elementen bepaalt of er nog een pagina volgt, niet het aantal geparste
     * items: anders zou één onparseerbaar element op een volle pagina de rest onzichtbaar maken.
     */
    @Test
    fun `een volle pagina waarvan een element niet parseert leidt tóch tot een volgende call`() {
        val result = fetch(maxPages = 20) { page ->
            if (page == 1) GitHubPage.Fetched(List(99) { "gefilterd-$it" }, rawCount = 100) else itemsPage(page, 3)
        }

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(102, result.items.size)
    }

    private fun fetch(maxPages: Int, fetchPage: (Int) -> GitHubPage<String>) =
        GitHubPagination.fetchAllPages(maxPages) { page ->
            requestedPages += page
            fetchPage(page)
        }

    private fun itemsPage(page: Int, size: Int): GitHubPage<String> =
        GitHubPage.Fetched(List(size) { "p$page-${it + 1}" })
}
