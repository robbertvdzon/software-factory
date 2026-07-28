# SF-1422 - Worklog

Story-context bij eerste pickup:
Corrigeer 1 MiB → 8 MiB in documentatie en foutmelding

Wijzig docs/factory/durable-completion.md regel 37 ('Payloads zijn maximaal 1 MiB' → '8 MiB') en regels 43-44 ('1 MiB-grens' → '8 MiB-grens'). Wijzig in softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/repositories/CompletionInboxRepository.kt regel 415 de foutmeldingstekst 'completion payload exceeds 1 MiB' naar 'completion payload exceeds 8 MiB'. MAX_PAYLOAD_BYTES (regel 316, 8_388_608) en alle overige validatielogica blijven ongewijzigd - puur tekst, geen gedragswijziging. Controleer dat er geen bestaande test is die matcht op de letterlijke string '1 MiB'.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `docs/factory/durable-completion.md` regel 37: "Payloads zijn maximaal 1 MiB" → "Payloads zijn maximaal 8 MiB".
- `docs/factory/durable-completion.md` regels 43-44: "de 1 MiB-grens overschrijdt" → "de 8 MiB-grens overschrijdt".
- `CompletionInboxRepository.kt` regel 415: foutmeldingstekst "completion payload exceeds 1 MiB" → "completion payload exceeds 8 MiB".
- `MAX_PAYLOAD_BYTES` (regel 316, `8_388_608`) ongewijzigd gelaten, conform scope.
- Repo-brede grep op "1 MiB" bevestigt: geen overige treffers in `.kt`/`.md`-bestanden buiten de drie in-scope regels (overige treffers zijn task-metadata/worklog-tekst, geen code/doc-inhoud).
- Grep op "exceeds 1 MiB"/"exceeds 8 MiB" bevestigt dat geen enkele bestaande test matcht op de letterlijke exceptiontekst; geen tests hoefden aangepast te worden.
- `mvn verify` vanaf de repo-root: BUILD SUCCESS voor alle modules (factory-contracts, factory-common,
  softwarefactory incl. Testcontainers-e2e, agentworker, softwarefactory-dashboard-backend);
  0 failures, 0 errors, 0 skipped in elke module.
- Geen wijzigingen aan `docs/factory/functional-spec.md`/`technical-spec.md`/ux-docs nodig: puur tekstcorrectie zonder gedrags- of architectuurwijziging.
