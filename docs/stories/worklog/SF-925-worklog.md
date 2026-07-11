# SF-925 - Worklog

Story: Splits onderhoudbaarheidsverbeterplan in autonome deelplannen met voortgang

## Scope

Maak uitsluitend plandocumentatie:

- een masterindex met uitvoervolgorde en aanbevolen GPT-5.6-Sol-effortniveau;
- bindende story-, review-, test- en overdrachtsregels;
- een persistent voortgangsoverzicht;
- negen zelfstandige deelplannen die zonder gesprekscontext uitvoerbaar zijn;
- een koppeling vanuit het bestaande bronplan.

Geen verbeterpunt uit het plan wordt in SF-925 geïmplementeerd.

## Checklist

[x]: Factory-story aangemaakt zonder automatische start
[x]: bestaande audit, storygeschiedenis en trackerroute gecontroleerd
[x]: masterindex, uitvoerregels en voortgangsstructuur opgezet
[x]: negen zelfstandige deelplannen geschreven
[x]: dekking en afhankelijkheden onafhankelijk gereviewd
[x]: links, Markdown en repositorytests gecontroleerd
[x]: voortgang en Factory-story met eindbewijs bijgewerkt

## Tussentijdse keuzes

- Voortgang staat in de repository én verwijst naar Factory-story/branch/PR, zodat een chatbericht
  nooit de enige statusbron is.
- Iedere werkpakketcode krijgt een eigen Factory-story; `MOD-03` wordt bovendien per module
  opgesplitst.
- Alleen `REL-01` krijgt standaard Ultra. Mechanische migraties gebruiken Medium en de afsluitende
  cleanup Light; veiligheids- en architectuurbeleid gebruikt High.
- Iedere bestaande testfailure is expliciet een blocker. Reviewer en tester mogen deze niet als
  pre-existing of ongerelateerd negeren.

## Eindbewijs op storybranch

- Negen plannen dekken alle 25 administratieve werkpakketten exact eenmaal; `MOD-03` is aanvullend
  uitgewerkt als acht afzonderlijke modulestories.
- Drie onafhankelijke kruisreviews controleerden documentzelfstandigheid, architectuur/
  afhankelijkheden en test-/gate-uitvoerbaarheid. Hun bevindingen over Mavenfilters,
  completionrecovery, modulecontracttypen, qualitybaseline en overdrachtsvelden zijn verwerkt.
- De onveranderlijke qualitynulmeting voor auditcommit `cc7cac2` staat machineleesbaar onder
  `docs/verbetertraject-2026-07/baselines/quality-cc7cac2.json`.
- Relatieve links, codefences, JSON, werkpakketdekking, verplichte secties en gefilterde
  Mavencommandopatronen zijn automatisch gecontroleerd.
- `mvn verify`: `BUILD SUCCESS`; 85 Surefire-/Failsafe-rapporten, 621 tests, 0 failures, 0 errors,
  0 skipped.
- Gerichte commandocontrole:
  `mvn -pl softwarefactory -am -Dtest=ModulithArchitectureTest
  -Dsurefire.failIfNoSpecifiedTests=false test`: 1 test, 0 failures/errors/skips.
- Er is geen verbeterwerkpakket gestart: voortgang blijft 0/9 plannen en 0/25 werkpakketten.
- De voorbereidingsstatus blijft bewust `BEZIG` totdat deze plandocumentatie is gemerged en de
  post-mergegate groen is; een gepushte branch alleen geldt niet als afgerond trajectbewijs.
