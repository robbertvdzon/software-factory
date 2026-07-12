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

De bestaande roottypes zijn tijdelijk en individueel vastgelegd in
`softwarefactory/src/test/resources/module-root-allowlist.txt`, met eigenaarplan MOD-02/ARC-01.
De architectuurtest eist exacte gelijkheid: ieder nieuw rootbestand of achterblijvende allowlistregel
faalt. De enige bestaande models-uitzondering is `UiBriefingItem`, een sealed interface voor het
polymorfe dashboardbriefing-contract; alle concrete modellen zijn data classes.
