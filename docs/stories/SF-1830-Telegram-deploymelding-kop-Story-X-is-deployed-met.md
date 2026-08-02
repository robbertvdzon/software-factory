# SF-1830 - Telegram-deploymelding: kop 'Story X is deployed!' met korte functionele samenvatting

## Story

Telegram-deploymelding: kop 'Story X is deployed!' met korte functionele samenvatting

<!-- refined-by-factory -->

## Samenvatting
Als een story klaar en gedeployed is, krijg je nu een Telegram-bericht met alleen de titel en een
technische bevestigingszin. Dat wordt een kort, functioneel bericht: welke story live staat, in
gewone taal wat er veranderd is, en daaronder de link. De technische bevestigingszin verdwijnt uit
het bericht; de controles die bepalen wanneer er gemeld wordt blijven precies hetzelfde.

## Scope

### 1. Nieuwe berichtopbouw (`TelegramResultNotifyPoller.send()`)
Het bericht wordt:

    🚀 Story <KEY> is deployed!

    <functionele samenvatting>

    <url>

- Kop wordt `🚀 Story <KEY> is deployed!`; de losse regel `<KEY>: <titel>` vervalt.
- De bevestigingsregel (`De live-URL is bereikbaar.` / `De nieuwe versie draait live.` /
  `Er staat een nieuwe APK-release klaar.`) verdwijnt volledig uit de berichttekst.
- De URL (live-URL bij openshift-watch, APK-downloadlink bij skip) blijft onderaan staan als die er is.
- Ontbreken samenvatting en URL, dan bestaat het bericht alleen uit de kop.
- Lege regel tussen elk blok, zoals nu.

### 2. Onderliggende checks ongewijzigd
`confirmOpenshift()` (HTTP-200 op `liveUrl`), `confirmApk()` (`ApkReleaseProbe`), de
`DEPLOY_FAILED`-skip, de `GIVEUP_HOURS`-timeout en het `null`-return-gedrag (= nog niet melden,
volgende poll opnieuw) blijven functioneel identiek: ze bepalen nog steeds ÓF, WANNEER en met welke
URL er gemeld wordt. Alleen hun tekst verdwijnt uit het bericht. Het interne `Confirmation`-model
mag daarom naar alleen een URL-drager gereduceerd worden.

### 3. Bron van de functionele samenvatting (eerste niet-lege wint)
1. **PO-blok van de summarizer.** `RolePrompts.summarizerPrompt()`
   (`agentworker/.../agent/ai/shared/AgentPromptContracts.kt`) wordt uitgebreid: naast de bestaande
   eindsamenvatting levert de summarizer een blok van max. 3 zinnen in gewone taal, gericht op de
   gebruiker die de story heeft aangevraagd (geen jargon, geen technische details, geen
   bestandsnamen/klassenamen), afgebakend met markers op eigen regels in de stijl van de bestaande
   refiner-markers: `<!-- deploy-summary:start -->` / `<!-- deploy-summary:end -->`.
   Werk `docs/factory/agents/summarizer.md` en de identieke kopie in
   `factory-common/src/main/resources/docs-skeleton/docs/factory/agents/summarizer.md` in dezelfde
   bewoording bij (drift tussen die twee wordt door geen enkele test bewaakt).
2. **`## Samenvatting`-sectie uit de story-description** (`TrackerIssue.description`, al gevuld door
   `findWorkIssues`): de tekst vanaf de kopregel `## Samenvatting` tot de volgende `## `-kop of het
   einde, getrimd.
3. **Niets** — dan alleen kop + eventuele URL.

### 4. Uitleespad van het PO-blok
Analoog aan het bestaande `testerReportFor`/`testerReportFrom`-patroon: een nieuwe
`deploySummaryFor(storyKey): String?` op de poort `FactoryOperations`
(`softwarefactory/.../core/contracts/FactoryOperations.kt`), geïmplementeerd in
`FactoryOperationsService` met een `internal`, puur functionele companion-helper
(`deploySummaryFrom(runs)`) die de meest recente SUMMARIZER-run met niet-lege `summaryText` pakt en
daar het blok tussen de markers uit haalt. `TelegramResultNotifyPoller` krijgt `FactoryOperations`
als extra constructor-dependency (zelfde injectie als `TelegramNotificationService`).
Alle bestaande fake-implementaties van `FactoryOperations` in de tests
(`TelegramPollerTest`, `TelegramReplyServiceTest`, `TelegramNotificationServiceTest.FakeDashboard`)
worden meegenomen zodat de module blijft compileren.

### 5. Robuustheid
- Alle fallbacks zijn soft-fail (`runCatching { ... }.getOrNull()`, zelfde patroon als
  `TelegramNotificationService.testerReport()`): een fout bij het ophalen of parsen van de
  samenvatting mag de melding nooit tegenhouden — dan valt hij door naar de volgende bron.
- De samenvatting wordt afgekapt op een Telegram-veilige lengte (~1000 tekens, in de orde van het
  bestaande `TESTER_REPORT_LIMIT`) en gestript van eventuele trailing control-JSON via de bestaande
  `ControlJsonStripper`.

### Buiten scope
- De melding voor de stand "als klaar" (`TelegramNotificationService.notifySubtaskDone` met het
  subtaakoverzicht) blijft ONGEWIJZIGD.
- De bestaande eindsamenvatting van de summarizer (tracker-comment + `docs/stories/*.md` via
  `finalSummaryText()`) blijft ongewijzigd; het PO-blok komt er alleen bovenop.
- De deploy-/APK-detectie zelf, `TelegramStore`/`telegram_notifications` en de poll-frequentie.

## Acceptance criteria
- Een story met meldingen-stand "als klaar en gedeployed" die deployt, levert precies één
  Telegram-bericht met de kop `🚀 Story <KEY> is deployed!`, daaronder de functionele samenvatting,
  daaronder (indien aanwezig) de URL. Geen subtaaklijst, geen bevestigingszin, geen losse
  `<KEY>: <titel>`-regel.
- Is er een summarizer-run met een `<!-- deploy-summary:start/end -->`-blok, dan staat exact de
  tekst uit dat blok (gestript en getrimd) in de melding.
- Ontbreekt het summarizer-blok, dan valt de melding terug op de `## Samenvatting` uit de
  story-description; ontbreekt ook die, dan komt de melding er nog steeds (kop + eventuele URL).
- Een exception of DB-fout bij het ophalen van de samenvatting blokkeert de melding niet: het
  bericht gaat alsnog uit met de eerstvolgende beschikbare bron (uiteindelijk kop + URL).
- Voor een openshift-watch-project zonder HTTP-200 op de live-URL, en voor een skip-project zonder
  nieuwe APK-release, wordt er nog steeds NIET gemeld (ongewijzigd gedrag); `DEPLOY_FAILED` en de
  opgeef-timeout markeren de story nog steeds stil als afgehandeld.
- Idempotentie blijft: hooguit één `result-notify`-melding per story (`TelegramStore`, signature
  `result-notify`), ook na herstart.
- De melding voor de stand "als klaar" blijft ongewijzigd (bestaande
  `TelegramNotificationServiceTest`-asserties blijven groen).
- Nieuwe unittests in `TelegramResultNotifyPollerTest`: (a) mét PO-blok, (b) alleen
  description-`## Samenvatting`, (c) zonder beide (alleen kop + URL), (d) soft-fail wanneer het
  ophalen van de samenvatting gooit. Plus een unittest op de `deploySummaryFrom`-helper (meest
  recente SUMMARIZER-run wint; geen markers → null).
- `mvn -q verify` (of `tools/verify-repository`) is groen; de quality-ratchet levert geen nieuwe
  blocking findings op.

## Aannames
- De URL onderaan blijft staan (nuttig bij APK-downloads); alleen de bevestigende zin verdwijnt.
- Het PO-blok verschijnt ook in de summarizer-comment op de tracker, in `docs/stories/<key>.md` en
  in het briefing-paneel van het dashboard, omdat die de ruwe summarizer-tekst tonen. Dat wordt
  geaccepteerd (het is een korte, leesbare sectie); er wordt in deze story geen extra strip-logica
  voor gebouwd.
- De SUMMARY-subtaak loopt in de keten altijd vóór MERGE/DEPLOY, dus bij het versturen van de
  melding bestaat de summarizer-run al; er wordt niet op gewacht — is hij er onverhoopt niet, dan
  treedt gewoon fallback 2/3 in werking.
- De samenvatting wordt als platte tekst verstuurd (geen Markdown/HTML-parsemode), consistent met de
  huidige `sendMessage`-aanroep.
- "Precies één bericht" betekent: geen extra Telegram-bericht bovenop de bestaande
  `result-notify`-melding; de aparte ERROR-melding bij een gefaalde deploy blijft zoals hij is.

## Eindsamenvatting

Ik heb de story-context, de volledige diff en het worklog (developer, reviewer, tester) gelezen. Hier is de eindsamenvatting.

---

# SF-1830 — Telegram-deploymelding: kop "Story X is deployed!" met korte functionele samenvatting

## Wat is gebouwd

De Telegram-melding die uitgaat zodra een story live staat (stand "als klaar en gedeployed") is opnieuw opgebouwd. Het bericht bestaat nu uit maximaal drie blokken, gescheiden door een lege regel:

```
🚀 Story <KEY> is deployed!

<korte functionele samenvatting>

<url>
```

- De losse regel `<KEY>: <titel>` is vervallen.
- De technische bevestigingszinnen ("De live-URL is bereikbaar." / "De nieuwe versie draait live." / "Er staat een nieuwe APK-release klaar.") staan niet meer in het bericht.
- De URL (live-URL bij openshift-watch, APK-downloadlink bij skip) blijft onderaan staan als die er is.
- Ontbreken samenvatting én URL, dan gaat het bericht alsnog uit met alleen de kop.

De functionele samenvatting komt uit de eerste niet-lege bron: (1) een nieuw blok dat de summarizer levert tussen `<!-- deploy-summary:start -->` / `<!-- deploy-summary:end -->` — max. 3 zinnen in gewone taal voor de aanvrager van de story; (2) de `## Samenvatting`-sectie uit de story-description; (3) niets.

## Gemaakte keuzes

- **Checks ongewijzigd, alleen de tekst weg.** `confirmOpenshift()` (HTTP-200 op de live-URL), `confirmApk()`, de `DEPLOY_FAILED`-skip, de opgeef-timeout en het "nog niet melden"-gedrag bepalen nog steeds ÓF, WANNEER en met welke URL er gemeld wordt. Het interne `Confirmation`-model is daarom teruggebracht tot alleen een URL-drager.
- **Bestaand patroon gevolgd.** De nieuwe poortmethode `deploySummaryFor(storyKey)` is exact gemodelleerd naar het bestaande tester-report-pad, met een pure, los testbare helper die de meest recente summarizer-run pakt en daar het blok tussen de markers uithaalt.
- **Soft-fail overal.** Een DB- of parse-fout bij het ophalen van de samenvatting kan de melding nooit tegenhouden; hij valt door naar de volgende bron en uiteindelijk naar kop + URL. De tekst wordt gestript van eventuele control-JSON en afgekapt op 1000 tekens (Telegram-veilig).
- **Summarizer-instructie op drie plekken gelijk gehouden.** De prompt én beide kopieën van `summarizer.md` (docs én docs-skeleton) zijn in dezelfde bewoording bijgewerkt en byte-identiek geverifieerd — die drift wordt door geen enkele test bewaakt.
- Tijdens de eigen kwaliteitscontrole gaf de sectie-parser eerst een blocking finding (te veel returns); die helper is herschreven naar één return-expressie.

## Wat is getest

- **Nieuwe unittests** op de poller met exacte assertions op de volledige berichttekst: PO-blok wint, fallback op de description-sectie, alleen kop + URL zonder beide bronnen, en een gooiende ophaalactie die de melding niet blokkeert. Plus een test op de helper (meest recente summarizer-run wint; geen markers → null).
- **Volledige testrun over alle modules**: `mvn verify` groen, 0 failures / 0 errors (829 tests over softwarefactory, agentworker en dashboard-backend). Kwaliteitsratchet: geen nieuwe blocking findings, 3 opgelost. Documentatie-audit: PASS.
- **Gedragsbewijs** buiten de unittests om: de gerenderde summarizer-prompt bevat beide markers elk op een eigen regel mét de 3-zinnen-instructie; de sectie-parser losgelaten op de échte SF-1830-description levert precies de `## Samenvatting`-regels en stopt bij de volgende kop (randgevallen: lege/ontbrekende sectie → geen samenvatting).
- De bestaande melding voor de stand "als klaar" (met subtaakoverzicht) is aantoonbaar ongewijzigd gebleven.

## Bewust niet gedaan

- De melding voor de stand "als klaar", de deploy-/APK-detectie zelf, de opslag van verstuurde meldingen en de poll-frequentie zijn niet aangeraakt.
- De bestaande eindsamenvatting van de summarizer richting tracker en `docs/stories/*.md` blijft zoals hij was; het PO-blok komt er alleen bovenop. Gevolg (bewust geaccepteerd): dat blok is ook zichtbaar in de tracker-comment, het story-document en het briefing-paneel — er is geen extra strip-logica voor gebouwd.
- Een échte Telegram-verzending is niet end-to-end waargenomen: de testomgeving heeft geen preview-URL, browser of Telegram-koppeling. Het bewijs is unittest- en gedragsniveau.

## Restpunt (niet blokkerend)

Reviewer en tester melden beiden hetzelfde kleine punt: de klasse-documentatie bovenin de poller noemt nog de verwijderde zin "Er staat een nieuwe APK-release klaar". Puur een commentaarregel, geen effect op het bericht — kan in de documentatie-subtaak meegenomen worden.

<!-- deploy-summary:start -->
Als een story live staat, krijg je nu een korter en duidelijker Telegram-bericht. Bovenaan staat welke story is uitgerold, daaronder in gewone taal wat er voor jou veranderd is, en daaronder de link. De technische bevestigingszin is verdwenen; wanneer je een melding krijgt is niet veranderd.
<!-- deploy-summary:end -->
