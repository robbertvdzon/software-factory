package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bouwstap 5 (e2e-plan §4): het volledige scenario van een verse story met label
 * `ai-refinement` tot álle subtaken afgerond, met de **echte** Spring-app (orchestrator-
 * loop, completion-pad, web-laag) en alleen de drie buitenranden vervangen door dubbels
 * ([E2eTestConfig]).
 *
 * De keten loopt zo ver mogelijk vanzelf: `Auto-approve=on` op de story laat de
 * orchestrator de `*-ed → *-approved`-goedkeuringen zelf zetten, zodat de test enkel de
 * écht menselijke acties uit het scenario stuurt (vragen beantwoorden via de UI en
 * "start developing"). De vragen-loops blijven gedekt: de scripted refiner én developer
 * stellen op hun eerste poging een vraag, die we via [FactoryUiDriver] beantwoorden.
 *
 * Geverifieerde keten:
 *  - refiner: vraag → (antwoord) → refined → (auto) refined-approved
 *  - planner: planned → (auto) planning-approved, 4 subtaken aangemaakt
 *  - development-subtask: vraag → (antwoord) → developed → (auto) development-approved →
 *    reviewer → review-approved
 *  - review/test/summary-subtaken: elk via hun agent naar `*-approved`/`summarized`
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eTestConfig::class)
@org.junit.jupiter.api.Disabled(
    "Onaf: het volledige scenario draait de pipeline tot de sync/PR-stap, die de ECHTE git-push/clone " +
        "(StoryWorkspaceService) en GitHub (gh CLI / GitHubApi) aanroept. Die buitenrand is nog niet " +
        "gedoubled (alleen YouTrack, AgentRuntime en config zijn vervangen). Om dit groen te krijgen " +
        "moeten GitHubApi + de git/workspace-laag als @Primary test-dubbels in E2eTestConfig komen.",
)
class FullRefineToDevelopE2eTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    private val youtrack get() = E2eTestConfig.FAKE_YOUTRACK
    private val state get() = youtrack.state
    private val runtime get() = E2eTestConfig.TEST_AGENT_RUNTIME

    @Test
    fun `story doorloopt refine tot alle subtaken afgerond`() {
        // Schoon beginnen: de scripted runtime is een gedeelde static over de test-JVM.
        runtime.dispatched.clear()

        val ui = FactoryUiDriver(rest, "http://localhost:$port").login()
        val await = AwaitDsl(youtrack, timeout = Duration.ofSeconds(60))

        // 1. Story direct in de fake-YouTrack-state: supplier mock, auto-approve aan, label ai-refinement.
        val storyKey = "${state.projectKey}-1"
        state.createIssue(summary = "Add integration test", key = storyKey)
        state.setEnumField(storyKey, "AI-supplier", "mock")
        state.setEnumField(storyKey, "Auto-approve", "on")
        state.issue(storyKey)!!.tags += "ai-refinement"

        // 2. De orchestrator pakt de story op → refiner stelt een vraag.
        await.awaitStoryPhase(storyKey, "refined-with-questions")

        // 3. Antwoord via de UI → refiner rondt af → (auto-approve) → planner → 4 subtaken.
        ui.answerStory(storyKey, "ja, ga door")
        await.awaitSubtasksCreated(storyKey, 4)
        await.awaitStoryPhase(storyKey, "planning-approved")

        // 4. Mens drukt op "start developing" → eerste subtask krijgt de ai-development-tag.
        ui.startDeveloping(storyKey)
        val devSubtask = state.childrenOf(storyKey).first()

        // 5. Developer stelt een vraag → beantwoord via de UI → pipeline loopt door.
        await.awaitSubtaskPhase(devSubtask.key, "developed-with-questions")
        ui.answerSubtask(devSubtask.key, "variant A, graag")

        // 6. Alle subtaken doorlopen hun pipeline tot ze approved/summarized zijn.
        await.awaitAllSubtasksApproved(storyKey)

        // --- Assert eindtoestand ---
        val children = state.childrenOf(storyKey)
        assertEquals(4, children.size, "verwachtte 4 subtaken onder $storyKey")

        // --- Assert de dispatch-volgorde van de scripted agents ---
        val roles = runtime.dispatched.map { it.second }
        assertOrderedSubsequence(
            roles,
            listOf(
                AgentRole.REFINER,   // attempt 1: vraag
                AgentRole.REFINER,   // attempt 2: refined
                AgentRole.PLANNER,   // planned + 4 subtaken
                AgentRole.DEVELOPER, // attempt 1: vraag
                AgentRole.DEVELOPER, // attempt 2: developed
                AgentRole.REVIEWER,  // dev-subtask review
                AgentRole.REVIEWER,  // review-subtask
                AgentRole.TESTER,    // test-subtask
                AgentRole.SUMMARIZER, // summary-subtask
            ),
        )
        assertEquals(2, roles.count { it == AgentRole.REFINER }, "refiner moet 2x draaien (vraag + afronden)")
        assertEquals(2, roles.count { it == AgentRole.DEVELOPER }, "developer moet 2x draaien (vraag + afronden)")
        assertEquals(1, roles.count { it == AgentRole.PLANNER }, "planner draait precies 1x")
    }

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
