# VER-01 rolloutmatrix — revisiongebonden testerbewijs

Inventarisatiebron: actuele `projects.yaml`/projectcatalogus op 11 juli 2026. Enforcement wordt pas
gemerged nadat iedere rij op de actuele default branch door `VerificationConfigValidatorCli` met
schema 1 is gevalideerd. Er is geen fail-openroute.

| Project / eigenaar | Repository / default | Verificatie vóór VER-01 | Config-PR | Base-SHA → config-SHA | Productieparserv-validatie | Activatie |
| --- | --- | --- | --- | --- | --- | --- |
| personal-feed / `robbertvdzon` | `personal-news-feed-by-claude-code` / `main` | geen protected required check; PR `validate`; backend-Maven en imagebuilds in Actions | [#176](https://github.com/robbertvdzon/personal-news-feed-by-claude-code/pull/176) | `12a357a` → `c0ff52c` | schema 1, `backend-maven-verify`, geldig op gemergede `main`; PR-jobs validate/backend/frontend/reader groen | gereed vóór enforcement |
| softwarefactory / `robbertvdzon` | `software-factory` / `main` | protected `Backend verification`; `mvn verify` | VER-01 [SF-927-PR](https://github.com/robbertvdzon/software-factory/pulls) | `d4f3280` → volgt bij merge | schema 1, `repository-maven-verify`, geldig op storybranch; herhaling op gemergede `main` verplicht | enforcement landt samen met geldige config |
| robberts-assistent / `robbertvdzon` | `robberts-assistent` / `main` | geen branch protection; `Build Wind APK` voert `flutter test` uit | [#2](https://github.com/robbertvdzon/robberts-assistent/pull/2) | `0b1863d` → `c097db1` | schema 1, `wind-flutter-test`, geldig op gemergede `main`; [post-merge APK-run 29154889730](https://github.com/robbertvdzon/robberts-assistent/actions/runs/29154889730) volledig groen | gereed vóór enforcement |

## Schema 1

`.factory/verification.yaml` bevat een numerieke `version: 1` en een niet-lege `commands`-lijst.
Ieder command heeft een stabiele lowercase/hyphen `id`, een niet-lege stringlijst `argv`, een
relatief bestaand `workingDirectory` binnen de checkout en `timeoutSeconds` van 1..7200. De runner
gebruikt `ProcessBuilder(argv)` en nooit een impliciete shell.

De additive `AgentResultFile.verificationEvidence` bevat configversie, geteste HEAD/tree en per
command id, ISO-start/eind, duur, nullable exitcode, status (`passed`, `failed`, `timeout`,
`tool-missing`, `execution-error`), optionele rapportlocatie en begrensde/geredacteerde samenvatting.

## Activatiecontrole

Direct vóór merge van SF-927 worden alle drie actuele base-checkouts opnieuw met de productieparser
gevalideerd. Na merge wordt dezelfde controle op alle default branches herhaald. Een nieuwe actieve
repository zonder vooraf gemergede en geldige config wordt door testergoedkeuring fail-closed
geweigerd; project-onboarding moet daarom configvalidatie vóór activatie uitvoeren.
