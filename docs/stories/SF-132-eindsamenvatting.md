# SF-132 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Op basis van de gelezen bestanden stel ik de eindsamenvatting op.

---

## Eindsamenvatting SF-132 — Altijd specs in code bijwerken als onderdeel van de development

### Wat is gebouwd

Er zijn twee agent-instructiebestanden in `docs/factory/agents/` aangepast om het bijwerken van specificaties een verplicht onderdeel te maken van elke story-cyclus:

- **`developer.md`** — Nieuwe verplichte stap toegevoegd onder "Verplicht per story": de developer moet bij elke story de gerakte onderdelen in `docs/factory/` (functional-spec.md, technical-spec.md en relevante UX-docs) actualiseren zodat de specs de huidige codebase weerspiegelen. Dit moet ook worden vermeld in het worklog.
- **`reviewer.md`** — Nieuw focuspunt toegevoegd: "Spec-consistentie" — de reviewer controleert of de relevante specs consistent zijn met de PR-diff en markeert inconsistenties als **blocker** voor merge.

### Gemaakte keuzes

- Uitsluitend de agent-instructies aangepast; geen wijzigingen in `functional-spec.md` of `technical-spec.md` zelf, omdat de story-implementatie geen inhoudelijke functionaliteitswijziging bevat.
- De verplichting is eenvoudig en direct geformuleerd, zodat toekomstige agents zonder interpretatie weten wat er van hen wordt verwacht.

### Wat is getest

- Geen geautomatiseerde tests uitgevoerd — de wijzigingen betreffen uitsluitend Markdown-instructiebestanden zonder testbare code.
- De test-agent (SF-134) heeft de wijzigingen geaccepteerd en de fase is doorgelopen naar `test-approved`.

### Wat bewust niet is gedaan

- Geen aanpassingen aan `functional-spec.md`, `technical-spec.md` of UX-docs — de bestaande specs waren al consistent met de (ongewijzigde) codebase.
- Geen tooling of automatische checks ingebouwd die spec-updates afdwingen; de verplichting is procesmatig geborgd via de agent-instructies.

---
