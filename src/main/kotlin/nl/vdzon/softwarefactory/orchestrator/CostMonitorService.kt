package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.tracker.AgentRole
import nl.vdzon.softwarefactory.tracker.BudgetTrigger
import nl.vdzon.softwarefactory.tracker.ContinueTrigger
import nl.vdzon.softwarefactory.tracker.IssueTrackerClient
import nl.vdzon.softwarefactory.tracker.TrackerCommentParser
import nl.vdzon.softwarefactory.tracker.TrackerFieldUpdate
import nl.vdzon.softwarefactory.tracker.TrackerIssue
import nl.vdzon.softwarefactory.tracker.TrackerField
import nl.vdzon.softwarefactory.tracker.ProcessedCommentService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.ceil

interface CostMonitor {
    fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue

    fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult

    fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord)
}

data class CostMonitorCheckResult(
    val totalTokens: Long,
    val budget: Long,
    val paused: Boolean,
    val postedThresholds: List<Int>,
)

@Service
class CostMonitorService(
    private val issueTrackerClient: IssueTrackerClient,
    private val storyRunRepository: StoryRunRepository,
    private val processedCommentService: ProcessedCommentService,
) : CostMonitor {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue {
        var current = issue
        issue.comments.forEach { comment ->
            if (processedCommentService.isProcessed(issue.key, comment.id, AgentRole.COST_MONITOR)) {
                return@forEach
            }

            val instructions = TrackerCommentParser.parseInstructions(comment.body)
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

            if (instructions.any { it is BudgetTrigger || it is ContinueTrigger }) {
                processedCommentService.markProcessed(issue.key, comment.id, AgentRole.COST_MONITOR)
            }
        }
        return current
    }

    override fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult {
        val totalTokens = storyRun.totalTokens
        val budget = issue.budget()
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
        val issue = issueTrackerClient.getIssue(storyKey)
        checkBudget(issue, storyRun)
    }

    fun checkAllActiveStories() {
        storyRunRepository.activeRuns().forEach { storyRun ->
            runCatching {
                val issue = issueTrackerClient.getIssue(storyRun.storyKey)
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

    companion object {
        const val DEFAULT_BUDGET: Long = 40_000
        private const val CONTINUE_MULTIPLIER = 1.5
        private val thresholds = listOf(75, 90, 100)
    }
}
