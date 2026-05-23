# KAN-006 - GitHub PR Flow

Story:
Als developer-agent wil ik target-repo wijzigingen in een branch kunnen pushen
en een PR kunnen openen of bijwerken, zodat review/test iteraties rond dezelfde
PR plaatsvinden.

Subtaken:
[x]: Target repo clone + branch checkout/create
[x]: Developer commit + push
[x]: PR create/update via `gh` of GitHub API
[x]: Bestaande PR vinden per branch/story
[x]: Merge-detectie en story afsluiten
[x]: PR-comment `@factory` iteratie-flow

Stappen:
[x]: clone target repo using the Jira `Target Repo` URL
[x]: checkout existing story branch or create from base branch
[x]: commit developer changes with story key context
[x]: push branch to remote
[x]: create PR when absent and reuse existing PR on loopback
[x]: scan active story PRs for merged state
[x]: transition Jira status to `Done` after human merge
[x]: process `@factory` PR comments idempotently
[x]: add fake GitHub tests

Done / rationale:
- Start KAN-006: specs voor §7, §10, §11 en §13 zijn gelezen; implementatie richt zich op een testbare git/GitHub CLI-laag, agent-side repo flow en orchestrator-side PR monitoring.
- De agent clone't nu de target-repo naar `/work/repo`, leest `deployment.md` voor base branch en branch prefix, en checkt per rol de juiste branch uit.
- De dummy developer maakt een target-repo wijziging, werkt het story-log bij, commit, pusht en opent of hergebruikt een PR.
- GitHub PR metadata wordt als completion-event teruggegeven en opgeslagen op `story_runs`, zodat latere polls dezelfde PR kunnen monitoren.
- De orchestrator detecteert gemergde PR's en transitieert de Jira-story naar `Done`; actieve story-run records worden afgesloten met `final_status=merged`.
- `@factory` PR-comments worden via GitHub reactions idempotent geclaimed en zetten de story terug naar `tested-with-feedback-for-developer`.
- `mvn test` draait groen met 41 tests, inclusief fake git/GitHub tests voor clone/branch, PR-hergebruik, PR-comments, developer-flow, Jira `Done`-transities en orchestrator merge-detectie.
- Live developer-container smoke op `KAN-69` en `sample-build-project` is groen: branch `ai/KAN-69` is gepusht/hergebruikt, PR #1 staat open, Jira `AI Phase` staat op `developed` en `Error` is leeg.
- Spring Boot start met polling uit; Flyway heeft migratie v2 toegepast op schema `software_factory` en de app start correct met de nieuwe GitHub/PR wiring.
- `./factory build-images` draait groen; `agent-base:local` en `agent-tester:local` zijn opnieuw gebouwd op de KAN-006 code.
