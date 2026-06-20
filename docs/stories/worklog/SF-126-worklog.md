# SF-126 Worklog — Verwijder Commit+Push knop, SYNC enum en sync() methode

## Story in eigen woorden

Verwijder de handmatige "Commit + Push" knop uit de FE en het bijbehorende `sync` mechanisme uit de backend. De auto-commit na agent-runs blijft intact.

## Checklist

- [x] FactoryDashboardViews.kt: `cmd(key, "sync", "Commit + push")` verwijderd
- [x] TrackerModels.kt: `SYNC("sync")` enum value verwijderd
- [x] ManualCommandService.kt: `SYNC -> sync(issue)` case verwijderd
- [x] ManualCommandService.kt: volledige `sync()` methode verwijderd
- [x] FactoryDashboardViewsTest.kt: sync-gerelateerde assertions verwijderd
- [x] ManualCommandServiceTest.kt: SYNC test-case verwijderd
- [x] TrackerCommentParserTest.kt: `@factory:command:sync` en `FactoryCommand.SYNC` referenties verwijderd (fix na review-blocker)

## Wat gedaan en waarom

**Review-loopback (SF-126):** Reviewer ontdekte dat `TrackerCommentParserTest.kt` nog steeds verwees naar het verwijderde `FactoryCommand.SYNC` enum value (regels 25 en 38). Dit veroorzaakt een compilatiefout. De sync-regel is verwijderd uit de test-input én de verwachte output.

De overige wijzigingen (FE-knop, enum, service-methode, andere tests) waren al correct door de vorige developer-run.
