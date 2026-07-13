package nl.vdzon.softwarefactory.runtime.types

enum class CompletionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
}

/** Stable protocol keys. Renaming or reordering one requires a migration. */
enum class CompletionStep {
    ACCEPT_RUN_RESULT,
    APPLY_USAGE_AND_COSTS,
    WRITE_FINAL_STORY,
    SYNC_REPOSITORY,
    UPSERT_REPOSITORY_METADATA,
    STORE_AGENT_EVENTS,
    SYNC_TESTER_ARTIFACTS,
    APPLY_TRACKER_RESULT,
    UPSERT_KNOWLEDGE,
    FINALIZE_COMMENT_MARKERS,
    CLEAN_WORKSPACE,
    PUBLISH_COMPLETION_WAKE,
}
