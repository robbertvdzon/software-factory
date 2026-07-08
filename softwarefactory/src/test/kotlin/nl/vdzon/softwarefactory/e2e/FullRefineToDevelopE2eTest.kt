package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Het volledige happy-path-scenario (e2e-plan §4): een verse story (Story Phase `start`) tot en met
 * **merge en deploy**, met de **echte** Spring-app en alleen de buitenranden vervangen
 * ([E2eTestConfig]). `Auto-approve=on` laat de orchestrator de `*-ed → *-approved`-gates zelf zetten,
 * zodat de test enkel de écht menselijke acties stuurt (twee vragen beantwoorden + "start developing").
 *
 * De merge/deploy-keten (SF-154/SF-164: juist dít stuk gaf productie-incidenten) draait via de fake
 * [FakeGitHubApi]: de scripted developer rapporteert het `github-pr`-event (zoals de echte
 * agentworker), waarmee `storyRun.prNumber` via het normale completion-pad gevuld raakt; de
 * merge-subtaak squash-merget vervolgens ECHT naar `main` op de [LocalGitRemote]. De deploy-subtaak
 * volgt de `DeployConfig.Skip`-route (geen deploy-config voor `sample`) en advancet op fase `start`;
 * het rest-restart-pad is apart gedekt in `DeploySubtaskHandlerTest`.
 *
 * De per-rol vraag/reject-flows staan in [PipelineFlowsE2eTest]; dit is de end-to-end keten in één keer.
 */
class FullRefineToDevelopE2eTest : E2eTestBase() {

    @Test
    fun `story doorloopt refine tot en met merge en deploy`() {
        // De developer rapporteert (net als de echte agentworker) een github-pr-event, zodat de
        // afgedwongen merge-subtaak een PR-nummer heeft en de keten tot het einde kan doorlopen.
        runtime.script.developerReportsPullRequest = true
        val ui = loginUi()
        // De volledige keten (refine→plan→dev→review→test→summary→documentation→merge→deploy) is
        // veel sequentiële, gepollde stappen; in een koude test-JVM (fork-per-class) haalt dat de
        // standaard-60s niet.
        val await = awaiter(Duration.ofSeconds(180))

        // Story (default: alle 4 subtaak-typen, refiner + developer stellen een vraag).
        val storyKey = "${state.projectKey}-1"
        createStory(storyKey)

        // Refine → (antwoord) → plan → 4 subtaken → planning-approved.
        refineAndPlan(ui, await, storyKey, expectedSubtasks = 4)

        // Mens drukt op "start developing" → eerste subtask krijgt fase `start`.
        ui.startDeveloping(storyKey)
        val devSubtask = state.childrenOf(storyKey).first()

        // Developer stelt een vraag → beantwoord via de UI → de hele keten loopt door.
        await.awaitSubtaskPhase(devSubtask.key, "developed-with-questions")
        ui.answerSubtask(devSubtask.key, "variant A, graag")

        // --- Merge (SF-154/SF-244): de afgedwongen merge-subtaak merget automatisch via de PR ---
        val merge = enforcedChild(storyKey, "merge")
        val deploy = enforcedChild(storyKey, "deploy")
        await.awaitSubtaskPhase(merge.key, "merge-approved")

        // De fake GitHub kent precies één PR en die is gemerged.
        val pr = E2eTestConfig.FAKE_GITHUB.pullRequests().single()
        assertTrue(pr.isMerged, "de PR (#${pr.number}) hoort na de merge-subtaak gemerged te zijn")

        // De squash-merge is ECHT op main van de lokale remote beland: het merge-commit staat in de
        // log, en de story-branch-inhoud (worklog/factory-docs, gecommit door de agent-sync) is
        // meegekomen — de main-tree bevat meer dan het seed-README alleen.
        val branchName = requireNotNull(mergedBranchName(pr.number)) {
            "verwachtte een squash-merge-commit voor PR #${pr.number} op main, maar de log is: " +
                E2eTestConfig.LOCAL_REMOTE.mainCommitSubjects()
        }
        assertTrue(
            E2eTestConfig.LOCAL_REMOTE.mainFiles().size > 1,
            "main hoort na de squash-merge de story-branch-inhoud te bevatten, maar bevat alleen: ${E2eTestConfig.LOCAL_REMOTE.mainFiles()}",
        )
        assertTrue(branchName.contains(storyKey), "de gemergede branch ($branchName) hoort bij story $storyKey")

        // --- Deploy: geen deploy-config voor `sample` → Skip-route advancet op fase `start` ---
        await.awaitSubtaskPhase(deploy.key, "deploy-approved")

        // --- Story Done: de laatste keten-advance zet de story in de Done-lane ---
        await.awaitIssueState(storyKey, "Done")

        // --- Eindtoestand ---
        // 4 geplande subtaken (development/review/test/summary) + de factory-afgedwongen documentation,
        // merge en deploy = 7. (De manual-approve-poort staat in de e2e-keten uit.) Alle subtaken
        // eindigen in een `*-approved`-fase — óók merge en deploy.
        assertEquals(7, state.childrenOf(storyKey).size, "verwachtte 7 subtaken onder $storyKey")
        await.awaitAllSubtasksApproved(storyKey)

        // --- Dispatch-volgorde van de scripted agents (story-gebonden tegen cross-test-besmetting) ---
        val roles = runtime.dispatched.filter { it.first == storyKey }.map { it.second }
        assertOrderedSubsequence(
            roles,
            listOf(
                AgentRole.REFINER,    // attempt 1: vraag
                AgentRole.REFINER,    // attempt 2: refined
                AgentRole.PLANNER,    // planned + 4 subtaken
                AgentRole.DEVELOPER,  // attempt 1: vraag
                AgentRole.DEVELOPER,  // attempt 2: developed (+ github-pr-event)
                AgentRole.REVIEWER,   // dev-subtask review
                AgentRole.REVIEWER,   // review-subtask
                AgentRole.TESTER,     // test-subtask
                AgentRole.SUMMARIZER, // summary-subtask
                AgentRole.DOCUMENTER, // afgedwongen documentation-subtask
            ),
        )
        assertEquals(2, roles.count { it == AgentRole.REFINER }, "refiner moet 2x draaien (vraag + afronden)")
        assertEquals(2, roles.count { it == AgentRole.DEVELOPER }, "developer moet 2x draaien (vraag + afronden)")
        assertEquals(1, roles.count { it == AgentRole.PLANNER }, "planner draait precies 1x")
    }

    /** De factory-afgedwongen subtaak van [type] onder [storyKey]. */
    private fun enforcedChild(storyKey: String, type: String) =
        state.childrenOf(storyKey).first { it.fields.subtaskType == type }

    /** De branch uit het squash-merge-commit-onderwerp op main ("Squash-merge PR #N (branch)"). */
    private fun mergedBranchName(prNumber: Int): String? =
        E2eTestConfig.LOCAL_REMOTE.mainCommitSubjects()
            .firstOrNull { it.startsWith("Squash-merge PR #$prNumber (") }
            ?.substringAfter("(")?.substringBeforeLast(")")

    /** Borgt dat [expected] als geordende deelreeks (subsequence) in [actual] voorkomt. */
    private fun <T> assertOrderedSubsequence(actual: List<T>, expected: List<T>) {
        var idx = 0
        for (item in actual) {
            if (idx < expected.size && item == expected[idx]) idx++
        }
        assertTrue(
            idx == expected.size,
            "verwachtte $expected als geordende deelreeks van de dispatch-volgorde, maar kreeg $actual",
        )
    }
}
