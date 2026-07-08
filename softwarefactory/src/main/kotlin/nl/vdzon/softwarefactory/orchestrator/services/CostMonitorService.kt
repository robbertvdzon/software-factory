package nl.vdzon.softwarefactory.orchestrator.services

import nl.vdzon.softwarefactory.core.CostMonitor
import nl.vdzon.softwarefactory.core.CostMonitorCheckResult
import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.BudgetTrigger
import nl.vdzon.softwarefactory.core.ContinueTrigger
import nl.vdzon.softwarefactory.tracker.ProcessedCommentsApi
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.core.TrackerApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.math.ceil

@Service
class CostMonitorService(
    private val issueTrackerClient: TrackerApi,
    private val storyRunRepository: StoryRunRepository,
    private val processedCommentService: ProcessedCommentsApi,
    private val clock: Clock,
) : CostMonitor {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue {
        var current = issue
        issue.comments.forEach { comment ->
            val instructions = issueTrackerClient.parseInstructions(comment.body)
                .filter { it is BudgetTrigger || it is ContinueTrigger }
            if (instructions.isEmpty()) {
                return@forEach
            }

            if (processedCommentService.isProcessed(issue.key, comment.id, AgentRole.COST_MONITOR)) {
                return@forEach
            }

            instructions.forEach { instruction ->
                when (instruction) {
                    is BudgetTrigger -> {
                        issueTrackerClient.updateIssueFields(
                            issue.key,
                            TrackerFieldUpdate.of(
                                TrackerField.AI_TOKEN_BUDGET to instruction.budget,
                                TrackerField.PAUSED to false,
                            ),
                        )
                        current = current.copy(
                            fields = current.fields.copy(
                                aiTokenBudget = instruction.budget,
                                paused = false,
                            ),
                        )
                    }
                    is ContinueTrigger -> {
                        if (current.fields.paused) {
                            val newBudget = ceil(current.budget().toDouble() * CONTINUE_MULTIPLIER).toLong()
                            issueTrackerClient.updateIssueFields(
                                issue.key,
                                TrackerFieldUpdate.of(
                                    TrackerField.AI_TOKEN_BUDGET to newBudget,
                                    TrackerField.PAUSED to false,
                                ),
                            )
                            current = current.copy(
                                fields = current.fields.copy(
                                    aiTokenBudget = newBudget,
                                    paused = false,
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }

            processedCommentService.markProcessed(issue.key, comment.id, AgentRole.COST_MONITOR)
        }
        return current
    }

    override fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult {
        val totalTokens = storyRun.totalTokens

        // Geen budget ingesteld → onbeperkt: alleen het verbruik bijwerken, nooit pauzeren of waarschuwen.
        val configuredBudget = issue.fields.aiTokenBudget?.takeIf { it > 0 }
        if (configuredBudget == null) {
            if (issue.fields.aiTokensUsed != totalTokens) {
                issueTrackerClient.updateIssueFields(
                    issue.key,
                    TrackerFieldUpdate(linkedMapOf(TrackerField.AI_TOKENS_USED to totalTokens)),
                )
            }
            return CostMonitorCheckResult(
                totalTokens = totalTokens,
                budget = Long.MAX_VALUE,
                paused = false,
                postedThresholds = emptyList(),
            )
        }

        val budget = configuredBudget
        val crossed = thresholds.filter { threshold -> totalTokens * 100 >= budget * threshold }
        val posted = mutableListOf<Int>()

        val fieldUpdates = linkedMapOf<TrackerField, Any?>()
        if (issue.fields.aiTokensUsed != totalTokens) {
            fieldUpdates[TrackerField.AI_TOKENS_USED] = totalTokens
        }

        crossed
            .filterNot { threshold -> issue.hasCostMonitorThreshold(threshold) }
            .forEach { threshold ->
                issueTrackerClient.postAgentComment(
                    issue.key,
                    AgentRole.COST_MONITOR,
                    thresholdMessage(threshold, totalTokens, budget),
                )
                posted += threshold
            }

        val shouldPause = totalTokens >= budget
        if (shouldPause && !issue.fields.paused) {
            fieldUpdates[TrackerField.PAUSED] = true
        }
        if (fieldUpdates.isNotEmpty()) {
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate(fieldUpdates))
        }

        return CostMonitorCheckResult(
            totalTokens = totalTokens,
            budget = budget,
            paused = shouldPause,
            postedThresholds = posted,
        )
    }

    override fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord) {
        val issue = runCatching { issueTrackerClient.getIssue(storyKey) }
            .getOrElse { exception ->
                if (exception.isMissingTrackerIssue()) {
                    closeMissingTrackerStoryRun(storyRun)
                    return
                }
                throw exception
            }
        checkBudget(issue, storyRun)
    }

    override fun checkAllActiveStories() {
        storyRunRepository.activeRuns().forEach { storyRun ->
            val issue = runCatching { issueTrackerClient.getIssue(storyRun.storyKey) }
                .getOrElse { exception ->
                    if (exception.isMissingTrackerIssue()) {
                        closeMissingTrackerStoryRun(storyRun)
                    } else {
                        logger.warn("Cost monitor failed for {}", storyRun.storyKey, exception)
                    }
                    return@forEach
                }

            runCatching {
                checkBudget(issue, storyRun)
            }.onFailure { exception ->
                logger.warn("Cost monitor failed for {}", storyRun.storyKey, exception)
            }
        }
    }

    private fun thresholdMessage(threshold: Int, totalTokens: Long, budget: Long): String =
        when (threshold) {
            100 -> "100% bereikt: $totalTokens/$budget tokens. Paused=true gezet; verhoog budget met BUDGET=N of gebruik CONTINUE."
            else -> "$threshold% bereikt: $totalTokens/$budget tokens."
        }

    private fun TrackerIssue.budget(): Long =
        fields.aiTokenBudget?.takeIf { it > 0 } ?: DEFAULT_BUDGET

    private fun TrackerIssue.hasCostMonitorThreshold(threshold: Int): Boolean =
        comments.any { comment ->
            comment.body.startsWith(AgentRole.COST_MONITOR.commentPrefix, ignoreCase = true) &&
                comment.body.contains("$threshold%")
        }

    private fun closeMissingTrackerStoryRun(storyRun: StoryRunRecord) {
        logger.info(
            "Cost monitor closing stale active story-run {} for missing tracker issue {}.",
            storyRun.id,
            storyRun.storyKey,
        )
        runCatching {
            storyRunRepository.close(storyRun.id, MISSING_TRACKER_STATUS, OffsetDateTime.now(clock))
        }.onFailure { exception ->
            logger.warn("Cost monitor could not close stale story-run {}", storyRun.storyKey, exception)
        }
    }

    private fun Throwable.isMissingTrackerIssue(): Boolean =
        this is TrackerApiException &&
            message?.contains("status 404") == true

    companion object {
        const val DEFAULT_BUDGET: Long = 40_000
        const val MISSING_TRACKER_STATUS = "tracker-missing"
        private const val CONTINUE_MULTIPLIER = 1.5
        // Fase 6 — alleen bij 100% pauzeren + comment; geen tussentijdse 75/90%-comments.
        private val thresholds = listOf(100)
    }
}
