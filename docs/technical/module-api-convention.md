# Module-API-conventie

Versie 1. Een Modulith-module-root bevat uitsluitend een smalle API/poort plus modulemetadata.
Services, clients, repositories, schedulers en configuratie horen in een intern subpackage.

- `models` is uitsluitend voor publieke immutable `data class`-contracten die in een API-signature
  of echte cross-moduledependency gebruikt worden. Alle properties zijn `val`; mutable state en
  mutable collecties zijn verboden.
- `types` is voor bewust geëxporteerde enums, sealed- en value-types.
- `errors` is uitsluitend een `@NamedInterface("errors")` met getypeerde publieke exceptions.
  DTO's, services en generieke exception-wrappers horen daar niet thuis.
- Een cross-modulecaller gebruikt alleen de root-API of een expliciete named interface, nooit een
  interne subpackage.

De root-allowlist is leeg. De architectuurtest detecteert concrete publieke declaraties in een
module-root; een nieuw concreet roottype faalt daardoor zonder uitzonderingsroute. De enige bestaande
models-uitzondering is `UiBriefingItem`, een sealed interface voor het
polymorfe dashboardbriefing-contract; alle concrete modellen zijn data classes.

De `dashboard`-module is het referentievoorbeeld: capabilitygerichte ports in de root, modellen in
`dashboard.models` en concrete services/repositories in interne subpackages.
