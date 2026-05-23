# KAN-003 - Repo-Root Secrets Env En Start Wrapper

Story:
Maak `secrets.env.example`, negeer lokale `secrets.env` en bereid lokaal starten voor.

Plan:
[x]: add `secrets.env.example`
[x]: add repo-root `secrets.env` placeholder
[x]: add `.gitignore` rules for local secrets
[ ]: add `./factory start` wrapper when packaging shape is stable

Done / rationale:
- Secrets staan nu in de root van de repo en alle eigen env-vars gebruiken `SF_`.
