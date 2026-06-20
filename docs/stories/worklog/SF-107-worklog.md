# SF-107 - Worklog

Story-context bij eerste pickup:
Auto-approve toggle toevoegen aan detail-view en nieuwe-story formulier

(1) Voeg in FactoryDashboardViews.kt::overviewDetails() auto-approve status en toggle-buttons toe (HTML met POST-forms). (2) Voeg in newStoryForm() checkbox voor autoApprove toe. (3) Voeg POST-endpoint /stories/{storyKey}/set-auto-approve/{state} toe in FactoryDashboardController.kt. (4) Voeg setAutoApproveFlag() method toe in FactoryDashboardService. (5) Voeg autoApprove parameter toe in YouTrackClient.createStory() en verwerk in customFields. (6) Update FactoryDashboardService.createStory() om parameter door te geven. (7) Zelf tests schrijven voor de nieuwe service-methodes. (8) Self-review op HTML, parameter-flow en YouTrack-integratie voordat code in PR gaat.

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
