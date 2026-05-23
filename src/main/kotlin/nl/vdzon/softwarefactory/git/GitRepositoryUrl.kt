package nl.vdzon.softwarefactory.git

data class GitRepositoryUrl(
    val original: String,
    val cloneUrl: String,
    val slug: String?,
) {
    companion object {
        private val sshGithubPattern = Regex("""^git@github\.com:([^/]+)/(.+?)(?:\.git)?$""")
        private val httpsGithubPattern = Regex("""^https://github\.com/([^/]+)/(.+?)(?:\.git)?/?$""")

        fun parse(rawUrl: String): GitRepositoryUrl {
            val trimmed = rawUrl.trim()
            sshGithubPattern.matchEntire(trimmed)?.let { match ->
                val slug = "${match.groupValues[1]}/${match.groupValues[2]}"
                return GitRepositoryUrl(
                    original = trimmed,
                    cloneUrl = "https://github.com/$slug.git",
                    slug = slug,
                )
            }
            httpsGithubPattern.matchEntire(trimmed)?.let { match ->
                val slug = "${match.groupValues[1]}/${match.groupValues[2]}"
                return GitRepositoryUrl(
                    original = trimmed,
                    cloneUrl = "https://github.com/$slug.git",
                    slug = slug,
                )
            }
            return GitRepositoryUrl(original = trimmed, cloneUrl = trimmed, slug = null)
        }
    }
}
