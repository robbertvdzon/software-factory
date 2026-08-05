package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests bovenop [E2eTestBase] voor functionele spec-scenario's (docs/factory/functional-spec.md)
 * die nog niet door [PipelineFlowsE2eTest]/[PipelineLoopbackE2eTest]/[FullRefineToDevelopE2eTest] werden
 * gedekt. Geen productiegedrag gewijzigd: alleen testcode.
 *
 *  - **SF-335 — silent autonoom**: een silent story doorloopt de hele keten volledig autonoom (auto-start
 *    development + alle gates) zónder enige menselijke actie, ook met `Auto-approve=off` (silent ⇒ auto-approve).
 *  - **SF-213 — documentatie-stap**: de factory-afgedwongen `documentation`-subtaak loopt op de juiste plek
 *    in de keten mee en ondersteunt het `documentation-with-questions`-pad (vraag → antwoord → approved).
 *  - **SF-1986 (as 3, meldingen)**: een lege eventset levert geen Telegram-bericht op; met uitsluitend
 *    `QUESTION` geselecteerd bereikt een `*-with-questions`-fase de gebruiker wel.
 *  - **SF-200 — test-chain-reset cap**: bij het bereiken van `SF_MAX_TEST_CHAIN_RESETS` (default 3) volgt geen
 *    reset meer maar komt de test-subtaak in `Error` (geen oneindige reset-loop).
 *
 * Elke test gebruikt een unieke story-key (eigen workspace + story-run).
 */
class SpecScenarioCoverageE2eTest : E2eTestBase() {

    @Test
    fun `silent story doorloopt de keten autonoom zonder enige menselijke actie`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            // documenterAsksQuestion blijft false: een silent story mag geen vraag uitlokken.
            plannedSubtasks = AgentScript.subtasks("development", "review", "test", "summary")
        }
        // De volledige keten is veel sequentiële, gepollde stappen; ruim de tijd geven in een koude test-JVM.
        val await = awaiter(Duration.ofSeconds(180))
        val story = "${state.projectKey}-200"
        // Goedkeuring elke-stap, maar vragen UIT + goedkeuring alsnog automatisch: het oude
        // "silent"-gedrag (SF-335) is nu vragen=uit + goedkeuring=automatisch + lege eventset.
        createStory(story, autoApprove = false)
        state.setEnumField(story, "QuestionsAllowed", "false")
        state.setEnumField(story, "ApprovalMode", "automatisch")
        state.setNotificationEvents(story)

        // Geen loginUi(), geen startDeveloping, geen answer/approve: bewust géén enkele UI-actie.
        await.awaitAllAiSubtasksApproved(story)

        // De keten liep autonoom: de afgedwongen documenter draaide mee zonder een mens.
        assertEquals(1, dispatchCount(story, AgentRole.DOCUMENTER), "documenter draait in de silent keten")
        // Geen enkele subtaak-reject is via de UI gestuurd; de gates gingen vanzelf door (één run per AI-rol).
        assertEquals(1, dispatchCount(story, AgentRole.SUMMARIZER), "summarizer draait precies 1x (geen reject)")

        // Lege eventset: geen enkel Telegram-bericht over déze story of haar subtaken.
        // Gescoped op de eigen keys, want telegram.messages is gedeelde JVM-state (naloop van een
        // vorige test in dezelfde JVM mag deze assertie niet laten flaken).
        assertNoTelegramMessagesFor(story)
    }

    @Test
    fun `een vraag komt altijd door ook als meldingen uit staan`() {
        runtime.script.apply {
            // Refiner stelt op attempt 1 een vraag; met vragen AAN wacht de story daarop.
            refinerAsksQuestion = true
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val await = awaiter()
        val story = "${state.projectKey}-240"
        // Velden vóór `Story Phase=start`: de orchestrator/notify-poll ziet de story pas als de fase
        // staat, dus uitsluitend QUESTION geldt gegarandeerd vanaf de allereerste poll.
        state.createIssue(summary = "E2E story $story", key = story)
        state.setEnumField(story, "Repo", "sample")
        state.setEnumField(story, "AI-supplier", "mock")
        state.setEnumField(story, "ApprovalMode", "elke-stap")
        state.setEnumField(story, "QuestionsAllowed", "true")
        state.setNotificationEvents(story, "QUESTION")
        state.setEnumField(story, "Story Phase", "start")

        // Via de échte orchestrator-poll (geen directe TelegramNotificationService-aanroep):
        // de refiner-vraag zet de story in de wachtstand.
        await.awaitStoryPhase(story, "refined-with-questions")

        // De QUESTION-melding heeft altijd "${key}: ${summary}" als issue-regel (buildMessage).
        // Notify draait op de orchestrator-poll-cadans (OrchestratorPoller.runOnce → notifyPending),
        // dus normaal binnen enkele polls; ruim wachten omdat een koude/belaste CI-JVM trager is.
        val issueLine = "$story: E2E story $story"
        Awaitility.await("Telegram-vraagbericht voor $story")
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(50))
            .until { telegramMessages().any { it.contains(issueLine) } }

        val matching = telegramMessages().filter { it.contains(issueLine) }
        assertEquals(
            1,
            matching.size,
            "verwachtte precies één Telegram-bericht voor $story (alleen de vraag), kreeg $matching",
        )
        assertTrue(matching.single().contains("❓ De Software Factory heeft een vraag"), matching.single())
    }

    /** Snapshot van de gedeelde berichtenlijst (synchronizedList: itereren onder de monitor). */
    private fun telegramMessages(): List<String> =
        synchronized(telegram.messages) { telegram.messages.toList() }

    /**
     * Assert dat geen enkel Telegram-bericht over [storyKey] of één van haar subtaken gaat. Matcht op
     * hele keys (word-boundary), zodat een langere key met dezelfde prefix (`SF-2001` naast `SF-200`)
     * niet meetelt.
     */
    private fun assertNoTelegramMessagesFor(storyKey: String) {
        val keys = listOf(storyKey) + state.childrenOf(storyKey).map { it.key }
        val patterns = keys.map { Regex("\\b${Regex.escape(it)}\\b") }
        val offending = telegramMessages().filter { message -> patterns.any { it.containsMatchIn(message) } }
        assertTrue(
            offending.isEmpty(),
            "lege eventset: verwachtte geen Telegram-bericht voor $storyKey of haar subtaken, kreeg $offending",
        )
    }

    @Test
    fun `silent story zet een refiner-vraag in een clarification-Error op story-niveau`() {
        runtime.script.apply {
            // Refiner stelt op attempt 1 een vraag; bij een silent story mag dat geen wachtstand worden.
            refinerAsksQuestion = true
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val await = awaiter()
        val story = "${state.projectKey}-230"
        // Goedkeuring elke-stap, vragen UIT: een story-vraag wacht niet op een mens (SF-1261, story-niveau).
        createStory(story, autoApprove = false)
        state.setEnumField(story, "QuestionsAllowed", "false")

        // Geen UI-actie: de refiner-vraag belandt direct in een clarification-Error op de STORY zelf
        // (i.p.v. op een subtaak, zoals in PipelineLoopbackE2eTest).
        await.awaitErrorContains(story, "[CLARIFICATION]")

        assertEquals(1, dispatchCount(story, AgentRole.REFINER), "refiner draait niet opnieuw: de vraag eindigt in Error")
        assertEquals(0, dispatchCount(story, AgentRole.PLANNER), "de planner start niet: de story stalt op de clarification-Error")
    }

    @Test
    fun `documentation-subtaak stelt een vraag die de gebruiker beantwoordt`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            documenterAsksQuestion = true // stelt op attempt 1 een vraag → documentation-with-questions
            plannedSubtasks = AgentScript.subtasks("development")
        }
        val ui = loginUi()
        // Ook deze keten bevat refine, plan, development en de afgedwongen documentatiestap.
        // Hanteer dezelfde koude-JVM-grens als de langere silent-keten hierboven.
        val await = awaiter(Duration.ofSeconds(180))
        val story = "${state.projectKey}-210"
        // Auto-approve aan: dev/review lopen vanzelf door, zodat alleen de documenter-vraag het menselijke moment is.
        createStory(story, autoApprove = true)

        await.awaitStoryPhase(story, "planning-approved")
        ui.startDeveloping(story)

        // De documentatie-stap is factory-afgedwongen (SF-213) en komt ná de geplande subtaken.
        await.awaitSubtasksCreated(story, 1)
        val documentation = enforcedChild(story, "documentation")

        // Documenter stelt een vraag → wacht op een mens (niet-silent) → beantwoord via de UI → approved.
        await.awaitSubtaskPhase(documentation.key, "documentation-with-questions")
        ui.answerSubtask(documentation.key, "werk de README en docs/factory bij", phase = "documentation-questions-answered")
        await.awaitSubtaskPhase(documentation.key, "documentation-approved")

        assertEquals(2, dispatchCount(story, AgentRole.DOCUMENTER), "documenter: vraag + afronden")
    }

    @Test
    fun `test-chain reset cap zet de test-subtaak in Error en stopt de reset-loop`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("test")
        }
        val ui = loginUi()
        val await = awaiter()
        val story = "${state.projectKey}-220"
        createStory(story, autoApprove = false)
        approveRefineAndPlan(ui, await, story, expectedSubtasks = 1)
        ui.startDeveloping(story)
        val test = plannedChild(story)

        // Default cap = SF_MAX_TEST_CHAIN_RESETS (3). De cap blokkeert pas de (cap+1)-de TESTER-run:
        // 3 bevindingen resetten nog (TESTER-runs 1..3), de 4e bevinding raakt de cap → Error.
        for (run in 2..4) {
            await.awaitSubtaskPhase(test.key, "tested")
            ui.setSubtaskPhase(test.key, "test-rejected")
            awaitDispatchCount(story, AgentRole.TESTER, run)
        }

        // 4e bevinding → cap bereikt → geen reset meer, test-subtaak in Error.
        await.awaitSubtaskPhase(test.key, "tested")
        ui.setSubtaskPhase(test.key, "test-rejected")
        await.awaitErrorContains(test.key, "Test-chain reset cap bereikt")

        assertEquals(4, dispatchCount(story, AgentRole.TESTER), "tester mag de cap (3) niet overschrijden: 4 runs")
        assertEquals(0, dispatchCount(story, AgentRole.DEVELOPER), "tester doet geen eigen developer-fix")
    }

    /** De factory-afgedwongen subtaak van [type] onder [storyKey] (documentation/merge/deploy/manual-approve). */
    private fun enforcedChild(storyKey: String, type: String) =
        state.childrenOf(storyKey).first { it.fields.subtaskType == type }
}
