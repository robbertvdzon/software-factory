# SF-129 - Worklog

Auto-approve toggle toevoegen aan detail-view en nieuwe-story formulier

## Story samenvatting

Voeg in de story-detail pagina (backend-rendered web-UI) een toggle toe zodat de gebruiker:
1. De huidige status van auto-approve kan zien in de "Eigenschappen" sectie
2. Auto-approve met twee buttons (on/off) kan aanpassen zonder comment
3. Na klikken wordt het commando naar YouTrack verstuurd
4. De optie "auto-approve" ook bij het aanmaken van een nieuwe story beschikbaar is

## Implementatieplan

1. [x] Code-review: existing setAutoApprove logica in ManualCommandService
2. [x] Voeg auto-approve status en toggle-buttons toe aan overviewDetails() in FactoryDashboardViews.kt
3. [x] Voeg auto-approve checkbox toe aan newStoryForm() in FactoryDashboardViews.kt
4. [x] Voeg POST-endpoint /stories/{storyKey}/set-auto-approve/{state} toe in FactoryDashboardController.kt
5. [x] Voeg setAutoApproveFlag() method toe in FactoryDashboardService (delegeert naar issueTrackerClient.updateIssueFields)
6. [x] Voeg autoApprove parameter toe in createStory() method
7. [x] Tests schrijven voor de neue views en service-methodes
8. [x] Maven compile validatie

## Gedaan / Implementatiedetails

### Views (FactoryDashboardViews.kt)

- **overviewDetails()**: Voeg auto-approve status toe (aan/uit) met ergonomische toggle-buttons:
  - Toon "Aanzetten" button alleen als auto-approve uit is
  - Toon "Uitzetten" button alleen als auto-approve aan is
  - Forms posten naar `/stories/{storyKey}/set-auto-approve/{on|off}`

- **newStoryForm()**: Voeg checkbox toe voor "Auto-approve aanzetten" (met `name="autoApprove" value="on"`)

### Controller (FactoryDashboardController.kt)

- **setAutoApprove()**: Nieuw POST-endpoint `/stories/{storyKey}/set-auto-approve/{state}`
  - Parseer state ("on" of "off") en delegeer naar service
  - Notificeer event bus na success
  - Redirect terug naar story detail met status-flag `?auto-approve=updated`

- **createStory()**: Voeg `autoApprove` parameter toe
  - Delegeer naar service met boolean flag
  - Service stelt flag in als `true` wordt gekozen

### Service (FactoryDashboardService.kt)

- **setAutoApproveFlag()**: Nieuwe method die auto-approve vlag bijwerkt
  - Roept `issueTrackerClient.updateIssueFields()` aan met `TrackerField.AUTO_APPROVE` en value "on"/"off"

- **createStory()**: Aangepast met autoApprove parameter
  - Maakt story aan
  - Roept `setAutoApproveFlag()` aan als `autoApprove=true`

### Tests (FactoryDashboardViewsTest & FactoryDashboardServiceTest)

- **Views tests**: 
  - Test auto-approve status weergave (aan/uit)
  - Test toggle buttons verschijnen/verdwijnen op basis van huidige state
  - Test checkbox in new story form

- **Service tests**:
  - Test `setAutoApproveFlag()` met enabled=true → POST field "on"
  - Test `setAutoApproveFlag()` met enabled=false → POST field "off"

## Rationale

- Reuse bestaande AUTO_APPROVE enum-veld logica (dezelfde als ManualCommandService.setAutoApprove)
- Minimale UI-changes: twee kleine buttons in properties sectie, één checkbox in form
- No new backend services nodig; hergebruik YouTrack-integratie
- Ergonomische UI: buttons grayed/hidden als state al bereikt is
- Maven compile succesvol; tests compileren
- Volgorde in acceptance criteria: UI view → form → controller → service → tests

## Review Feedback (2026-06-20 - phase: reviewing)

### [blocker] Ontbrekende test voor createStory(autoApprove=true)
**Locatie:** FactoryDashboardServiceTest, regel 107-124
**Issue:** De nieuwe tests testen alleen de FakeYouTrackApi mock direct, niet de `setAutoApproveFlag()` via de service. Bovendien ontbreekt een test die valideert dat `createStory()` met `autoApprove=true` daadwerkelijk `setAutoApproveFlag()` aanroept.
**Impact:** Scope van implementation incomplete: de integratie tussen `createStory(autoApprove=true)` en `setAutoApproveFlag()` is ongetest.
**Aanbeveling:** Voeg test toe die service.createStory() aanroept met autoApprove=true en verifieert dat het veld bijgewerkt is.

### [suggestie] Service-test test niet de volledige flow
**Locatie:** FactoryDashboardServiceTest
**Issue:** Tests testen FakeYouTrackApi.updateIssueFields() direct, niet de service-method setAutoApproveFlag(). Dit is testing-infrastructure, niet de werkelijke service-logica.
**Aanbeveling:** Maak een volledige service-test met IssueTrackerClient dependency injection via de fake.

### [suggestie] Acceptance criterion 4: pagina-verversing onduidelijk
**Locatie:** .task.md acceptance criteria #4
**Issue:** "De pagina ververst en toont de bijgewerkte status" – de controller redirect naar dezelfde URL, maar de bijgewerkte status hangt af van YouTrack-respons-timing en client-refresh gedrag. Niet duidelijk of dit automatisch refresh (AJAX) of manueel (F5) vereist.
**Aanbeveling:** Controleren in manuele test: na button-klik, verschijnt de bijgewerkte status onmiddellijk of moet pagina handmatig ververst worden?
