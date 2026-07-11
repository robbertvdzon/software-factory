# Functional Spec

Beschrijf wat de applicatie functioneel moet doen, welke gebruikersflows
belangrijk zijn, en welke acceptatiecriteria vaak terugkomen.

Een testerresultaat bereikt alleen `tested` met compleet groen machinebewijs uit
`.factory/verification.yaml` voor exact dezelfde HEAD/worktree-tree. Missing bewijs/config, onbekende
versie, tool-missing, timeout, non-zero en revisionmismatch leveren altijd `test-rejected` op;
pre-existing, flaky en omgevingsfouten zijn nooit groen.
