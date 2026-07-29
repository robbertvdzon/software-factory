package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SF-1454 (audit-story SF-1429): bewijst dat een gebruiker met `NotifyMode = na-elke-stap`
 * (`NotifyMode.EVERY_STEP`) écht één Telegram-bericht krijgt zodra een subtaak terminaal wordt,
 * via de **echte** pijplijn — echte Spring-app, Testcontainers-Postgres, echte
 * `OrchestratorPoller`-`@Scheduled`-poll — en niet via een directe aanroep van
 * `TelegramNotificationService`.
 *
 * De story plant bewust maar één AI-subtaak (`review`; `REVIEW_APPROVED` is één van de terminale
 * fases uit `SubtaskPhase.isTerminal` — `DEVELOPMENT_APPROVED` is dat NIET, want development is in
 * de normale keten slechts een tussenstap vóór review/test/summary). Zodra review-approved is
 * bereikt, dispatcht de echte pijplijn — nog binnen dezelfde testrun — meteen door naar de
 * factory-afgedwongen afsluiters (documentation/merge/deploy), die (met NotifyMode=na-elke-stap)
 * hun eigen Telegram-meldingen krijgen zodra ZIJ terminaal worden. "Precies één bericht" wordt
 * daarom gescoped op de review-subtaak-gebeurtenis zelf (bevat de review-subtaak-key), conform de
 * story-aanname: "precies één bericht voor de terminale subtaak-gebeurtenis" — niet een globale
 * telling over de hele testrun.
 */
class TelegramSubtaskDoneE2eTest : E2eTestBase() {

    @Test
    fun `subtaak-klaar stuurt precies één Telegram-melding via de echte pijplijn`() {
        runtime.script.apply {
            refinerAsksQuestion = false
            reviewerAsksQuestion = false
            plannedSubtasks = AgentScript.subtasks("review")
        }
        val await = awaiter(Duration.ofSeconds(60))
        val storyKey = "${state.projectKey}-500"
        createStory(storyKey)
        state.setEnumField(storyKey, "NotifyMode", NotifyMode.EVERY_STEP.trackerValue)

        await.awaitStoryPhase(storyKey, "planning-approved")
        val reviewSubtask = plannedChild(storyKey)

        // Auto-approve staat aan: de orchestrator start de eerste subtaak zelf
        // (StoryRefinementCoordinator.autoStartDevelopment) en drijft 'm via de echte
        // OrchestratorPoller-@Scheduled-poll tot een terminale fase — geen directe
        // TelegramNotificationService-aanroep vanuit deze test.
        await.awaitSubtaskPhase(reviewSubtask.key, "review-approved")

        // De DONE-melding zelf heeft altijd "${key}: ${summary}" als issue-regel (zie
        // TelegramNotificationService.buildMessage); dat onderscheidt 'm van de eerdere
        // "Planning klaar"-voortgangsmelding, die dezelfde subtaak-key alleen in het `[ ]`-overzicht
        // noemt (ander formaat, geen `key: summary`).
        val issueLine = "${reviewSubtask.key}: ${reviewSubtask.summary}"
        await("Telegram-'klaar'-bericht voor ${reviewSubtask.key}")
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(50))
            .until { telegram.messages.any { message -> message.contains(issueLine) } }

        val matching = telegram.messages.filter { it.contains(issueLine) }
        assertEquals(
            1,
            matching.size,
            "verwachtte precies één Telegram-bericht voor de klaar-gebeurtenis van ${reviewSubtask.key}, kreeg $matching",
        )
        val message = matching.single()
        assertTrue(message.contains("✅ Klaar"), message)
    }
}
