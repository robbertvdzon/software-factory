package nl.vdzon.softwarefactory.dashboard.database

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PreviewUrlResolverTest {
    @Test
    fun `uses configured template when present`() {
        val url = PreviewUrlResolver.resolve(
            "https://preview-{pr_num}.example.test/{prNumber}/{pr}",
            125,
            "https://github.com/robbertvdzon/example",
        )

        assertThat(url).isEqualTo("https://preview-125.example.test/125/125")
    }

    @Test
    fun `falls back to personal news feed preview route`() {
        val url = PreviewUrlResolver.resolve(
            null,
            125,
            "https://github.com/robbertvdzon/personal-news-feed-by-claude-code",
        )

        assertThat(url).isEqualTo("https://pnf-pr-125.vdzonsoftware.nl")
    }

    @Test
    fun `does not invent preview route without template or known repo`() {
        val url = PreviewUrlResolver.resolve(
            null,
            125,
            "https://github.com/robbertvdzon/sample-build-project",
        )

        assertThat(url).isNull()
    }
}
