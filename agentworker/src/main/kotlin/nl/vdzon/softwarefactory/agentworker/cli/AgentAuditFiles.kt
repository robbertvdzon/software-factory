package nl.vdzon.softwarefactory.agentworker.cli

import nl.vdzon.softwarefactory.agent.AgentPaths
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Pad van het auditrapport dat de AUDITOR schrijft; de env-override is er voor tests/lokale runs. */
internal fun auditReportPath(env: Map<String, String>): String =
    env["SF_AUDIT_REPORT_FILE"]?.takeIf { it.isNotBlank() } ?: AgentPaths.AUDIT_REPORT_FILE

/** Pad van het bevindingenbestand dat de AUDITOR schrijft als hij een vraag stelt. */
internal fun auditFindingsPath(env: Map<String, String>): String =
    env["SF_AUDIT_FINDINGS_FILE"]?.takeIf { it.isNotBlank() } ?: AgentPaths.AUDIT_FINDINGS_FILE

/** De door de auditor geschreven bevindingen, of null als het bestand ontbreekt/leeg is. */
internal fun readAuditFindings(env: Map<String, String>): String? =
    readWorkspaceMarkdown(Path.of(auditFindingsPath(env)), "audit findings")

/** Het door de auditor geschreven markdown-rapport, of null als het bestand ontbreekt/leeg is. */
internal fun readAuditReport(env: Map<String, String>): String? =
    readWorkspaceMarkdown(Path.of(auditReportPath(env)), "audit report")

private fun readWorkspaceMarkdown(path: Path, label: String): String? {
    val markdown = runCatching { path.takeIf { it.exists() }?.readText() }
        .onFailure { println("Agent worker could not read $label file: path=$path error=${it.message}") }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    println("Agent worker $label: path=$path chars=${markdown?.length ?: 0}")
    return markdown
}
