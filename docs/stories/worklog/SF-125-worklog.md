# SF-125 / SF-126 - Worklog

Story: Verwijder Commit+Push knop, SYNC enum en sync() methode; update tests

## Checklist

[x]: Verwijder `cmd(key, "sync", "Commit + push")` uit FactoryDashboardViews.kt (r.864)
[x]: Verwijder `SYNC("sync")` uit FactoryCommand enum in TrackerModels.kt (r.65)
[x]: Verwijder `FactoryCommand.SYNC -> sync(issue)` when-branch in ManualCommandService.kt (r.120)
[x]: Verwijder private `sync()` methode in ManualCommandService.kt (r.309-338)
[x]: Verwijder `assertContains(html, "Commit + push")` en sync-url assertions in FactoryDashboardViewsTest.kt (r.201-202)
[x]: Verwijder sync-testcase in ManualCommandServiceTest.kt
[x]: Controleer exhaustiveness van when-expressies over FactoryCommand
[x]: AgentRunCompletionService en bijbehorende tests niet aangeraakt

## Gedaan

- **FactoryDashboardViews.kt r.864**: Verwijderd `${cmd(key, "sync", "Commit + push")}`.
- **TrackerModels.kt r.65**: Verwijderd `SYNC("sync")` uit de `FactoryCommand` enum.
- **ManualCommandService.kt r.120**: Verwijderd de `FactoryCommand.SYNC -> sync(issue)` when-branch.
- **ManualCommandService.kt r.309-338**: Verwijderd de volledige private `sync()` methode.
- **FactoryDashboardViewsTest.kt r.201-202**: Verwijderd de twee assertions op sync-url en "Commit + push" tekst.
- **ManualCommandServiceTest.kt**: Verwijderd de `sync command commits pushes...` testcase.
- **when-exhaustiveness**: De when-expressie in ManualCommandService gebruikt geen `else` maar enumerates alle entries; na verwijdering van SYNC compileert dit zonder exhaustiveness-fouten omdat SYNC niet meer in de enum zit.
- **AgentRunCompletionService**: Niet aangeraakt — auto-commit na agent-runs blijft intact.
- `FakeStoryWorkspaceService` in de tests is behouden omdat die nog gebruikt wordt door de re-implement test.
