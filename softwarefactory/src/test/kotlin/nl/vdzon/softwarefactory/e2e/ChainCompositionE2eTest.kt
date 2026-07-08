package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * End-to-end dekking van de **samenstelling van de subtaak-keten** zoals de functional-spec belooft
 * (§Documentatie-stap SF-213, §Merge SF-244): de factory-afgedwongen afsluit-subtaken en de gefilterde
 * planner-spec. Deze paden werden door geen van de bestaande e2e-tests rechtstreeks geassert.
 *
 *  - **Planner-spec-filtering zonder dubbele afsluit-subtaak.** Stuurt de planner expliciet óók een
 *    `documentation`/`merge`/`deploy`-spec mee; die worden eruit gefilterd zodat er nooit een dubbele
 *    afsluit-subtaak ontstaat (de factory dwingt deze zelf precies één keer af).
 *  - **Afgedwongen ketenvolgorde.** De gematerialiseerde subtaken staan in de spec-volgorde
 *    `development → review → test → summary → documentation → merge → deploy` (manual-approve-poort
 *    staat in de e2e-config uit).
 *  - **Documenter-goedkeuringspad zonder vraag.** Zonder documenter-vraag loopt de documentation-subtaak
 *    bij `Auto-approve=on` vanzelf door naar `documentation-approved` (precies één documenter-run).
 *
 * De composition-asserts draaien op het materialisatiemoment (ná `planning-approved`, vóór
 * `start-developing`), dus deterministisch en zonder keten-uitvoering. Elke test gebruikt een unieke
 * story-key en deelt geen state.
 */
class ChainCompositionE2eTest : E2eTestBase() {

    @Test
    fun `planner-meegestuurde documentation, merge en deploy worden niet gedupliceerd en in de juiste volgorde gezet`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            // De planner stuurt bewust óók de factory-afgedwongen typen mee: die moeten gefilterd worden.
            plannedSubtasks = AgentScript.subtasks(
                "development", "review", "test", "summary", "documentation", "merge", "deploy",
            )
        }
        val await = awaiter(Duration.ofSeconds(60))
        val story = "${state.projectKey}-430"
        createStory(story)

        await.awaitStoryPhase(story, "planning-approved")
        // 4 planner-subtaken (dev/review/test/summary) + afgedwongen documentation/merge/deploy = 7.
        await.awaitSubtasksCreated(story, 7)

        val types = state.childrenOf(story).map { it.fields.subtaskType }
        assertEquals(
            listOf("development", "review", "test", "summary", "documentation", "merge", "deploy"),
            types,
            "afgedwongen volgorde + geen dubbele afsluit-subtaak (manual-approve staat in de e2e-config uit)",
        )
        // Expliciet: precies één van elke factory-afgedwongen afsluiter ondanks de meegestuurde planner-spec.
        assertEquals(1, types.count { it == "documentation" }, "precies één documentation-subtaak")
        assertEquals(1, types.count { it == "merge" }, "precies één merge-subtaak")
        assertEquals(1, types.count { it == "deploy" }, "precies één deploy-subtaak")
    }

    @Test
    fun `documentation-subtaak zonder vraag loopt bij auto-approve vanzelf door naar approved`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            documenterAsksQuestion = false // geen vraag → geen menselijk moment
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = loginUi()
        val await = awaiter(Duration.ofSeconds(120))
        val story = "${state.projectKey}-440"
        createStory(story, autoApprove = true)

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)

        // De factory-afgedwongen documentation-stap (SF-213) komt ná de geplande subtaak.
        await.awaitSubtasksCreated(story, 1)
        val documentation = state.childrenOf(story).first { it.fields.subtaskType == "documentation" }

        // Geen vraag + auto-approve: documenting → documented → documentation-approved zonder mens.
        await.awaitSubtaskPhase(documentation.key, "documentation-approved")
        assertEquals(
            1,
            dispatchCount(story, AgentRole.DOCUMENTER),
            "documenter draait precies 1x als er geen vraag is",
        )
    }
}
