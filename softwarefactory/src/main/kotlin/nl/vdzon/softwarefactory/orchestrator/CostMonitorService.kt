package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.youtrack.TrackerIssue

interface CostMonitor {
    fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue

    fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult

    fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord)

    fun checkAllActiveStories() = Unit
}

data class CostMonitorCheckResult(
    val totalTokens: Long,
    val budget: Long,
    val paused: Boolean,
    val postedThresholds: List<Int>,
)
