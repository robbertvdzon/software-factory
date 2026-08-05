package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.pipeline.DeployTargetStatusApi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SF-1971 — end-to-end dekking van de **deploy-stap met meerdere deploy-doelen**: de enige stap die
 * tot nu toe geen e2e-test had, en die in acht dagen twee keer in productie stukging. De fout zat
 * niet in een los onderdeel maar in het samenspel: ná de merge las de deploy-stap de verkeerde
 * story-run-administratie, waardoor de story-diff onbepaalbaar werd (`changedPaths == null`), de
 * `matchPaths`-filter fail-open ging en ook een deploy-doel meedeed dat de story helemaal niet raakt
 * — dat doel werd nooit "live" en de deploy bleef hangen.
 *
 * Deze test draait daarom de échte keten (dezelfde Spring-app als [FullRefineToDevelopE2eTest], met
 * alleen de buitenranden vervangen) op het project [E2eTestConfig.DEPLOY_PROJECT], dat twee
 * `openshift-watch`-doelen met elkaar uitsluitende `matchPaths` heeft:
 *
 *  - [E2eTestConfig.DEPLOY_TARGET_MATCHED] (`backend/`) — geraakt door de gefakete story-diff
 *    ([FakeGitHubApi.CHANGED_FILES]); de test-[nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe]
 *    geeft hier een niet-lege image, dus dit doel wordt "live";
 *  - [E2eTestConfig.DEPLOY_TARGET_UNMATCHED] (`frontend/`) — niet geraakt; de probe geeft hier
 *    altijd `null`, dus dit doel wordt nóóit "live".
 *
 * `deploy-approved` is daarmee het bewijs: die fase is alleen bereikbaar als de deploy-stap ná de
 * merge de échte story-run (repo + PR-nummer) leest, de diff dus kent, en het niet-geraakte doel
 * eruit filtert. Draait de matching fail-open, dan blijft de subtaak in `deploying` hangen en loopt
 * deze test in zijn await-timeout.
 */
class DeployTargetsE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var deployTargetStatus: DeployTargetStatusApi

    @Test
    fun `story op een project met twee deploy-doelen deployt alleen het doel dat de diff raakt`() {
        // Geen vragen: deze test gaat over merge+deploy, niet over de vraag/antwoord-gates.
        runtime.script.apply {
            refinerAsksQuestion = false
            developerAsksQuestion = false
            // Zoals de echte agentworker: het github-pr-event vult storyRun.prNumber via het normale
            // completion-pad, zodat merge én deploy een PR-nummer hebben om mee te werken.
            developerReportsPullRequest = true
        }
        val ui = loginUi()
        // De volledige keten is veel sequentiële, gepollde stappen; in een koude test-JVM
        // (fork-per-class) haalt dat de standaard-60s niet.
        val await = awaiter(Duration.ofSeconds(180))
        val storyKey = "${state.projectKey}-600"

        createStory(storyKey, repo = E2eTestConfig.DEPLOY_PROJECT)

        await.awaitSubtasksCreated(storyKey, 4)
        await.awaitStoryPhase(storyKey, "planning-approved")
        ui.startDeveloping(storyKey)

        // De merge doet een échte lokale squash-merge op de LocalGitRemote.
        await.awaitSubtaskPhase(childOfType(storyKey, "merge").key, "merge-approved")
        assertTrue(
            E2eTestConfig.FAKE_GITHUB.pullRequests().single().isMerged,
            "de PR van $storyKey hoort na de merge-subtaak gemerged te zijn",
        )

        // De kern: de deploy komt door, ondanks een tweede doel dat nooit live wordt.
        await.awaitSubtaskPhase(childOfType(storyKey, "deploy").key, "deploy-approved")

        // Expliciet, via dezelfde read-only poort die het dashboard gebruikt: precies één doel doet
        // mee, en dat is het doel dat de story-diff raakt.
        val matched = deployTargetStatus.matchedDeployTargetsFor(storyKey, E2eTestConfig.DEPLOY_PROJECT)
        assertEquals(
            listOf(E2eTestConfig.DEPLOY_TARGET_MATCHED.name),
            matched.map { it.target.name },
            "alleen het doel met matchPaths die ${FakeGitHubApi.CHANGED_FILES} raken hoort mee te doen",
        )
        assertTrue(matched.single().watched, "een openshift-watch-doel hoort bewaakt te worden")

        // De deploy is nooit op deploy-failed beland (het niet-geraakte doel had 'm anders in zijn
        // timeout getrokken).
        assertEquals(
            0,
            state.fieldValueCount(childOfType(storyKey, "deploy").key, "Subtask Phase", "deploy-failed"),
            "een correct gefilterd deploy-doel mag nooit tot deploy-failed leiden",
        )
    }
}
