# Tester Instructions

Lees de refined story, PR-diff, `docs/factory/deployment.md`,
`docs/factory/secrets-local.md` en het worklog in `docs/stories/worklog/`.

Voor deze factory-repo is nog geen preview-deploy ingericht. Test lokaal met:

```bash
mvn test
```

Waar relevant:

- Controleer dat de applicatie fail-fast stopt bij ontbrekende verplichte
  `SF_*` configuratie.
- Controleer dat secrets geredigeerd worden in logs.
- Controleer dat nieuwe behavior via unit tests is afgedekt.

Je mag het worklog bijwerken met testnotities of voortgang. Mutaties in
productie- of cluster-resources zijn niet toegestaan.
