# KAN-006 - GitHub PR Flow

Story:
Als developer-agent wil ik target-repo wijzigingen in een branch kunnen pushen
en een PR kunnen openen of bijwerken, zodat review/test iteraties rond dezelfde
PR plaatsvinden.

Subtaken:
[ ]: Target repo clone + branch checkout/create
[ ]: Developer commit + push
[ ]: PR create/update via `gh` of GitHub API
[ ]: Bestaande PR vinden per branch/story
[ ]: Merge-detectie en story afsluiten
[ ]: PR-comment `@factory` iteratie-flow

Stappen:
[ ]: clone target repo using the Jira `Target Repo` URL
[ ]: checkout existing story branch or create from base branch
[ ]: commit developer changes with story key context
[ ]: push branch to remote
[ ]: create PR when absent and reuse existing PR on loopback
[ ]: scan active story PRs for merged state
[ ]: transition Jira status to `Done` after human merge
[ ]: process `@factory` PR comments idempotently
[ ]: add fake GitHub tests

Done / rationale:
- Nog niet geimplementeerd.
