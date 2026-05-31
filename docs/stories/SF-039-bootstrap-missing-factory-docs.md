# SF-039 - Ontbrekende factory docs aanvullen bij workspace-prepare

## Story

Als target repository al wel een `docs/` map heeft, maar nog geen `docs/factory/` en `docs/stories/`, moet de orchestrator deze mappen en standaardbestanden automatisch aanmaken wanneer hij de story-workspace voorbereidt. Bestaande documentatie mag daarbij niet worden overschreven.

## Stappenplan

[x]: Controleer waar de factory docs skeleton installer nu wordt aangeroepen.
[x]: Pas de orchestrator workspace-prepare flow aan zodat ontbrekende skeleton-bestanden altijd idempotent worden aangevuld.
[x]: Voeg een regressietest toe voor een repo met bestaande `docs/` map zonder `factory/` en `stories/`.
[x]: Draai de gerichte verificatie.

## Uitvoering

- `StoryWorkspaceService.prepare` installeert nu na checkout altijd de factory docs skeleton in de target repo.
- De installer is idempotent: bestaande bestanden worden overgeslagen en alleen ontbrekende skeleton entries worden aangemaakt.
- Hierdoor worden `docs/factory/` en `docs/stories/.gitkeep` ook aangemaakt als de repository al een eigen `docs/` map had.

## Verificatie

- `mvn -q -pl softwarefactory -Dtest=StoryWorkspaceServiceTest test`
- `mvn -q -pl softwarefactory test`
- `git diff --check`
