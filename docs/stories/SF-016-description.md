# SF-016 - Tracker-Neutral Foundation

## Story

Replace the Jira-specific configuration and domain boundary with tracker-neutral
names so the factory can use YouTrack as the issue source.

## Plan

[x]: document the story and implementation steps
[x]: replace Jira secret keys with YouTrack keys
[x]: rename the tracker domain model and comment parsing boundary
[x]: update docs and skeleton references from Jira to YouTrack
[x]: run focused config/domain tests

## Work Log

- Created this story document first so the implementation plan is visible before
  changing code.
- Replaced the old Jira environment contract with `SF_YOUTRACK_BASE_URL`,
  `SF_YOUTRACK_TOKEN` and optional `SF_YOUTRACK_PROJECTS`. Existing required
  GitHub/database settings stayed project-prefixed.
- Renamed the Kotlin boundary to tracker-neutral types (`IssueTrackerClient`,
  `TrackerIssue`, `TrackerField`, `TrackerCommentParser`) so YouTrack is an
  implementation detail instead of leaking Jira naming through the domain.
- Moved tracker files from the old `jira` source folders into
  `src/main/kotlin/.../tracker` and updated tests to the same package layout.
- Updated factory docs, skeleton docs and examples to describe YouTrack and
  `AI-supplier`.
- Verification: `mvn -q clean test`.
