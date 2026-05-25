package nl.vdzon.softwarefactory.dashboard.database

object PreviewUrlResolver {
    fun resolve(template: String?, prNumber: Int?, targetRepo: String?): String? {
        if (prNumber == null) {
            return null
        }

        val effectiveTemplate = template?.takeIf { it.isNotBlank() }
            ?: fallbackTemplate(targetRepo)
            ?: return null

        return effectiveTemplate
            .replace("{pr_num}", prNumber.toString())
            .replace("{prNumber}", prNumber.toString())
            .replace("{pr}", prNumber.toString())
    }

    private fun fallbackTemplate(targetRepo: String?): String? {
        if (targetRepo?.contains("personal-news-feed-by-claude-code") == true) {
            return "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
        }
        return null
    }
}
