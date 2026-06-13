package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.TrackerIssue

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
