# SF-1985 - Telegram-meldingen per gebeurtenis met frontend-presets

## Story

Telegram-meldingen per gebeurtenis met frontend-presets

<!-- refined-by-factory -->

## Samenvatting

Maak Telegram-meldingen per story afzonderlijk instelbaar per gebeurtenis.
Bij het aanmaken kiest de gebruiker uit drie eenvoudige presets; standaard is “Als deployed”.
Na het aanmaken zijn alle gebeurtenissen los aan en uit te zetten.
Bestaande stories behouden na migratie hun huidige meldingsgedrag.
Vragen, goedkeuring en meldingen blijven onafhankelijke instellingen.

## Scope

- Vervang `NotifyMode` door een uitbreidbare eventset met exact deze ondersteunde waarden:
  `QUESTION`, `APPROVAL_REQUIRED`, `MANUAL_ACTION_REQUIRED`, `QUOTA_WAIT`, `ERROR`,
  `STEP_COMPLETED`, `WORKFLOW_COMPLETED` en `DEPLOYED`.
- Sla uitsluitend de concrete eventset op de story op. Subtaken gebruiken bij het bepalen van meldingen steeds de eventset van hun parent-story.
- Verwijder `NotifyMode` uit databasecontracten, gedeelde modellen, notificatieclassificatie, deployed-poller, dashboard- en bridge-API’s en frontend.
- Presetnamen en combinatie-enums maken geen deel uit van backend, database of API-contracten.
- Hanteer deze gebeurtenisbetekenissen:
  - `QUESTION`: een agent stelt een vraag.
  - `APPROVAL_REQUIRED`: een workflowstap wacht op menselijke goedkeuring.
  - `MANUAL_ACTION_REQUIRED`: een handmatige subtaak, vaste manual-approve-poort of handmatige merge-actie wacht op een mens.
  - `QUOTA_WAIT`: een story of subtaak wacht tot het opgeslagen `RetryAfter`-tijdstip.
  - `ERROR`: een story of subtaak is met een fout vastgelopen.
  - `STEP_COMPLETED`: een afzonderlijke workflowstap of subtaak is afgerond.
  - `WORKFLOW_COMPLETED`: de volledige factory-workflow is afgerond.
  - `DEPLOYED`: het resultaat is extern als deployed/live bevestigd.
- Toon uitsluitend in het frontend-aanmaakscherm deze presets:
  - **Alleen als ik nodig ben**: `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR`.
  - **Als deployed**: `DEPLOYED`, `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR`.
  - **Na elke stap**: alle acht events.
- Selecteer **Als deployed** standaard. Vertaal de preset vóór het create-request naar de concrete eventset.
- Toon op story-detail alle acht events als onafhankelijke checkboxes. Iedere combinatie, inclusief een lege set, moet opgeslagen kunnen worden; toon geen afgeleide presetstatus.
- Toon in zowel aanmaak- als wijzigingsscherm een niet-blokkerende waarschuwing wanneer goedkeuring op `elke-stap` staat en `APPROVAL_REQUIRED` niet geselecteerd is.
- Laat auditvoorstellen atomair aanmaken met exact `QUESTION`, `MANUAL_ACTION_REQUIRED` en `ERROR`.
- Laat alle overige aanmaakroutes een concrete eventset meegeven of de gedocumenteerde concrete standaardset van **Als deployed** gebruiken. Een eventuele default wordt als eventset gedefinieerd, niet als presetnaam.
- Voeg een migratie toe die bestaande `notify_mode`-waarden omzet:
  - `geen` → `QUESTION`
  - `na-elke-stap` → `QUESTION`, `APPROVAL_REQUIRED`, `MANUAL_ACTION_REQUIRED`, `QUOTA_WAIT`, `ERROR`, `STEP_COMPLETED`, `WORKFLOW_COMPLETED`
  - `als-klaar` → `QUESTION`, `ERROR`, `WORKFLOW_COMPLETED`
  - `als-klaar-en-gedeployed` → `QUESTION`, `ERROR`, `DEPLOYED`
- Behoud de bestaande database-backed idempotentie per issue en gebeurtenistoestand, waaronder één `QUOTA_WAIT` per `RetryAfter` en precies één `DEPLOYED` per story.
- Werk de functionele, technische, API- en UX-documentatie bij zodat nergens `NotifyMode` nog als actueel contract wordt beschreven.

## Acceptance criteria

1. De create-dialog toont precies de drie beschreven presets en selecteert standaard **Als deployed**.
2. Het create-request bevat alleen de concrete eventset en bevat geen presetnaam of `NotifyMode`.
3. Een standaard aangemaakte, volledig automatisch verlopende story verstuurt vóór externe live-bevestiging geen stap- of workflow-klaarmelding en daarna precies één `DEPLOYED`-melding.
4. **Alleen als ik nodig ben** verstuurt zonder vraag, handmatige actie of fout geen Telegram-bericht.
5. **Na elke stap** vertaalt naar exact alle acht events, inclusief `QUOTA_WAIT`.
6. Story-detail toont alle acht events als losse checkboxes en bewaart iedere combinatie correct, inclusief een lege set.
7. `QuestionsAllowed` bepaalt uitsluitend hoe de workflow met agentvragen omgaat; `QUESTION` bepaalt uitsluitend of daarover een Telegram-bericht wordt verstuurd.
8. `ApprovalMode` bepaalt uitsluitend of goedkeuring nodig is; `APPROVAL_REQUIRED` bepaalt uitsluitend of daarover een Telegram-bericht wordt verstuurd.
9. Bij `ApprovalMode=elke-stap` zonder `APPROVAL_REQUIRED` is de waarschuwing zichtbaar, maar blijven aanmaken en opslaan mogelijk.
10. `MANUAL_ACTION_REQUIRED` dekt de handmatige subtaak, de vaste manual-approve-poort en een handmatige merge-actie.
11. Auditor-stories worden in dezelfde create-operatie opgeslagen met exact `QUESTION`, `MANUAL_ACTION_REQUIRED` en `ERROR`, en melden daardoor geen afronding, quota-wacht of deployment.
12. Bestaande stories worden volgens de migratiematrix geconverteerd; `na-elke-stap` behoudt daarbij ook de bestaande quota-wachtmelding.
13. Subtaken gebruiken de actuele eventset van hun parent-story en krijgen geen zelfstandig instelbare eventset.
14. Iedere geselecteerde eventcategorie wordt op de bijbehorende toestand verstuurd; niet-geselecteerde categorieën worden onderdrukt.
15. Herhaalde polls en procesherstarts veroorzaken geen dubbele melding voor dezelfde issue/gebeurtenistoestand.
16. Tests dekken presetmapping, create- en updatecontracten, alle aanmaakroutes, migratie, parent-inheritance, alle acht eventcategorieën, auditor-aanmaak, waarschuwing, lege eventset en deployed-/quota-idempotentie.
17. De volledige backend- en frontend-verificatie is groen en relevante documentatie beschrijft uitsluitend het nieuwe eventset-contract.

## Aannames

- Wanneer één overgang meerdere geselecteerde events veroorzaakt, blijven die events onafhankelijk. De afronding van de laatste stap kan dus zowel `STEP_COMPLETED` als `WORKFLOW_COMPLETED` opleveren.
- Een lege eventset onderdrukt alle Telegram-meldingen, maar verandert de workflow niet. Daardoor kan een story zonder Telegram-signaal op een vraag of goedkeuring wachten.
- Gewijzigde keuzes gelden vanaf het opslaan. Bestaande idempotentiegegevens blijven behouden, zodat reeds gemelde toestanden niet opnieuw worden gemeld.
- `WORKFLOW_COMPLETED` betekent dat de factory-workflow terminaal is; `DEPLOYED` blijft de afzonderlijke, latere externe live-bevestiging.

## Eindsamenvatting

## Eindsamenvatting voor PO

Telegram-meldingen zijn per story instelbaar gemaakt voor acht afzonderlijke gebeurtenissen. Het aanmaakscherm biedt drie presets met **Als deployed** als standaard; daarna kan iedere melding los worden aan- of uitgezet, inclusief alles uit. Bestaande stories worden gemigreerd met behoud van hun huidige meldingsgedrag.

Belangrijke keuzes: alleen concrete meldingskeuzes worden opgeslagen, subtaken volgen altijd hun parent-story en ongeldige waarden worden zonder wijziging afgewezen. Gelijktijdige gebeurtenissen blijven onafhankelijk, terwijl herhaald pollen geen dubbele meldingen veroorzaakt.

De volledige backend- en frontendverificatie was groen: alle zes Maven-modules, 865 unit-tests, 88 integratie-/e2e-tests, Flutter-analyse, 145 Flutter-tests, kwaliteitscontroles, Docker-build en documentatie-audit. Reviewer en tester keurden de uiteindelijke versie zonder open bevindingen goed.

Bewust nog niet uitgevoerd: merge en productie-deploy; daarvoor volgen aparte subtaken. Presets zijn bewust uitsluitend frontendgemak en geen opgeslagen backendcontract.

<!-- deploy-summary:start -->
Je kunt bij het aanmaken van een story kiezen wanneer je Telegram-meldingen wilt ontvangen. Daarna kun je ieder soort melding afzonderlijk aan- of uitzetten, terwijl bestaande stories hun huidige meldingsgedrag behouden.
<!-- deploy-summary:end -->
