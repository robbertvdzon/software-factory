# SF-035 - Copilot credentials mounten in agent-containers

## Story

Als gebruiker wil ik GitHub Copilot in Docker kunnen gebruiken via mijn bestaande Copilot-abonnement, zonder een losse LLM API key te configureren. De factory moet daarom een lokale Copilot credentials-directory naar de agent-container kunnen mounten.

## Stappenplan

[x]: Maak story-document aan met scope en plan.
[x]: Voeg `SF_COPILOT_CREDENTIALS_DIR` toe aan de secrets-loader en redacted summary.
[x]: Mount de directory voor `AI-supplier=copilot` naar `/home/runner/.copilot`.
[x]: Laat de Copilot-agent file-based Copilot credentials herkennen.
[x]: Werk example secrets en specs bij.
[x]: Pas tests aan en draai verificatie.

## Uitvoering

- Gestart na een Copilot developer-run die wel de Copilot adapter gebruikte, maar faalde omdat de Docker-container geen Copilot-auth van de host kon zien.
- `FactorySecrets` en `SecretsEnvLoader` lezen nu `SF_COPILOT_CREDENTIALS_DIR`; de waarde staat ook in de redacted config summary.
- `DockerAgentRuntime` mount bij `AI-supplier=copilot` of `github` deze directory naar `/home/runner/.copilot` en mount dan niet per ongeluk de Claude credentials.
- `CopilotAiClient` herkent nu ook `~/.copilot/config.json` als file-based Copilot login en geeft een foutmelding die naar `SF_COPILOT_CREDENTIALS_DIR` verwijst.
- `secrets.env.example`, `docs/factory/secrets-local.md`, `docs/technical/external-systems.md` en `specs/specs.md` zijn bijgewerkt.
- Verificatie: gerichte tests voor secrets, Docker runtime en Copilot client, daarna de volledige Maven-suite.
