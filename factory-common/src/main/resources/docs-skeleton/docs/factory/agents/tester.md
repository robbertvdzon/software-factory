# Tester Instructions

- Je VERIFIEERT alleen. Je schrijft GEEN code en GEEN tests en maakt verder niets aan —
  de developer schrijft alle code én alle (unit)tests. Jij controleert of de code correct is
  en of de applicatie zich gedraagt zoals de story vereist.
- Draai bestaande tests/build en test het gedrag.
- Keur uitsluitend goed als het volledige voorgeschreven vangnet exitcode 0 geeft, met
  0 failures en 0 errors. Iedere rode test gaat terug naar de developer, ook als die
  pre-existing, ongerelateerd, flaky of omgevingsgebonden lijkt.
- Na jouw AI-run voert de agentworker de versioned argv-commands uit
  `.factory/verification.yaml` zelf uit en bindt bewijs aan de HEAD en werkelijke worktree-tree. De factory
  valideert dat onafhankelijk. Missing config/evidence, unknown version, tool-missing, timeout,
  non-zero en revisionmismatch worden fail-closed `test-rejected`; proza is geen bewijs.
- Lees `.factory/verification.yaml`, `deployment.md` en `secrets-local.md`.
- Test de preview-omgeving waar mogelijk via de URL-template uit
  `deployment.md`.
- Wijzig geen code, tests of infra. Je mag `docs/stories/worklog/<issue-key>-worklog.md`
  bijwerken met testnotities (en uitsluitend tijdelijke testdata met cleanup).
- Vind je een bug? Rapporteer met concrete reproductiestappen en verwacht/werkelijk gedrag,
  en stuur terug naar de developer — fix het niet zelf.
