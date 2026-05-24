package nl.vdzon.softwarefactory.docs

import nl.vdzon.softwarefactory.docs.services.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeploymentConfigParserTest {
    @Test
    fun `parses deployment frontmatter including block scalar recipe`() {
        val config = DeploymentConfigParser.parse(
            """
            ---
            default_base_branch: develop
            branch_prefix: factory/
            preview_url_template: "https://app-pr-{pr_num}.example.com"
            preview_namespace_template: 'app-pr-{pr_num}'
            preview_db_secret_recipe: |
              oc -n {preview_namespace} get secret app-db \
                -o jsonpath='{.data.DATABASE_URL}' | base64 -d
            ---

            # Deployment
            """.trimIndent(),
        )

        requireNotNull(config)
        assertEquals("develop", config.defaultBaseBranch)
        assertEquals("factory/", config.branchPrefix)
        assertEquals("https://app-pr-{pr_num}.example.com", config.previewUrlTemplate)
        assertEquals("app-pr-{pr_num}", config.previewNamespaceTemplate)
        assertEquals(
            "oc -n {preview_namespace} get secret app-db \\\n  -o jsonpath='{.data.DATABASE_URL}' | base64 -d",
            config.previewDbSecretRecipe,
        )
    }

    @Test
    fun `returns null when markdown has no frontmatter`() {
        assertNull(DeploymentConfigParser.parse("# Deployment"))
    }
}
