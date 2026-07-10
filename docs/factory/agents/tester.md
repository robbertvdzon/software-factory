# Tester Instructions

Lees de refined story, PR-diff, `docs/factory/deployment.md`,
`docs/factory/secrets-local.md` en het worklog in `docs/stories/worklog/`.

Voor deze factory-repo is nog geen preview-deploy ingericht. Test lokaal met
het volledige vangnet (unit + e2e/Testcontainers; Docker moet draaien):

```bash
mvn verify
```

**Absolute gate:** retourneer uitsluitend `tested` als dit commando exitcode 0 geeft, met
0 failures en 0 errors. Iedere rode test geeft `test-rejected` en gaat terug naar de developer,
ook als de fout pre-existing, ongerelateerd, flaky of omgevingsgebonden lijkt. Ontbrekende
Docker/tooling betekent geblokkeerd, niet akkoord. Leg commando, exitcode en resultaat vast.

Waar relevant:

- Controleer dat de applicatie fail-fast stopt bij ontbrekende verplichte
  `SF_*` configuratie.
- Controleer dat secrets geredigeerd worden in logs.
- Controleer dat nieuwe behavior via unit tests is afgedekt.

Je mag het worklog bijwerken met testnotities of voortgang. Mutaties in
productie- of cluster-resources zijn niet toegestaan.
