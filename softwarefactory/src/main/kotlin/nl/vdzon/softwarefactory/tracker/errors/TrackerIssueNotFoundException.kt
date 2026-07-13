package nl.vdzon.softwarefactory.tracker.errors

open class TrackerApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TrackerIssueNotFoundException(val issueKey: String) : TrackerApiException("Issue tracker: onbekende issue-key '$issueKey'.")
