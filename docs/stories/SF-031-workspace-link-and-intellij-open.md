# SF-031 - Workspace-link en openen in IntelliJ

## Story

Als gebruiker wil ik bij een AI-story kunnen zien waar de lokale work folder staat, zodat ik de code direct kan inspecteren. Vanuit de UI wil ik die folder met een knop in IntelliJ IDEA kunnen openen.

## Stappenplan

[x]: Maak story-document aan met scope en plan.
[x]: Laat de orchestrator een workspace-link/comment in de story zetten wanneer de work folder voor het eerst wordt aangemaakt.
[x]: Voeg een backend-actie toe die het bekende workspace-pad veilig opent met IntelliJ IDEA.
[x]: Toon de work folder in de dashboard details en voeg een knop "Open in IntelliJ" toe.
[x]: Breid de Flutter dashboard API en UI uit met workspacePath en dezelfde knop.
[x]: Werk specs/technische docs bij.
[x]: Voeg tests toe en draai verificatie.

## Uitvoering

- Gestart met het plan. De implementatie moet zowel de huidige server-rendered dashboardpagina als de Flutter dashboardroute ondersteunen, omdat beide in dit project bestaan.
- De orchestrator post bij eerste workspace-aanmaak een YouTrack-comment met het lokale repo-pad en het IntelliJ-commando. Hergebruik van een bestaande workspace post geen dubbele comment.
- De lokale HTML-dashboardroute en de Flutter dashboard API openen IntelliJ via de backend met `open -a "IntelliJ IDEA" <workspace>/repo`. Het pad komt uit `story_runs.workspace_path`; de browser voert geen shell-command uit.
- De story-detailviews tonen de work folder en bieden een knop "Open in IntelliJ". De Flutter API geeft `workspacePath` nu mee in `StoryRunDto`.
- Verificatie uitgevoerd met gerichte Kotlin-tests, volledige Maven-suite en Flutter widget-test.
