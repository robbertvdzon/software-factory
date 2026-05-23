# KAN-002 - Config Model Voor Runtime Settings

Story:
Maak een config-model voor Jira, GitHub, database, Docker, AI en runtime paths.

Plan:
[x]: define required SF-prefixed secret keys
[x]: implement secrets loader with file-first environment fallback
[x]: redact secrets in startup logging
[ ]: extend model with Docker and AI runtime settings when those modules land

Done / rationale:
- Eerste config-slice is geimplementeerd voor Jira, GitHub, database en AI credentials.
