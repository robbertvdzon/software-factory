# SF-034 - Voeg Copilot toe als AI-supplier

## Story

Als gebruiker wil ik in YouTrack `copilot` als `AI-supplier` kunnen kiezen, omdat de agentworker al een GitHub Copilot CLI-adapter heeft. `microsoft` blijft een toekomstige supplierwaarde, maar mag niet de enige zichtbare optie zijn voor Copilot-runs.

## Stappenplan

[x]: Maak story-document aan met scope en plan.
[x]: Voeg `copilot` toe aan de YouTrack `AI-supplier` bootstrapwaarden.
[x]: Accepteer `SUPPLIER=copilot` in YouTrack-comments.
[x]: Werk specs en technische docs bij.
[x]: Pas tests aan en draai verificatie.

## Uitvoering

- Gestart na observatie dat een developer-run faalde met `AI supplier 'microsoft' is nog niet geimplementeerd`, terwijl de Copilot-client wel bestaat maar niet via de YouTrack-dropdown beschikbaar was.
- `copilot` toegevoegd aan de YouTrack schema-bootstrap voor `AI-supplier`. Bij startup of project-discovery voegt de app deze enumwaarde toe aan bekende factory-projecten.
- `SUPPLIER=copilot` wordt nu herkend in YouTrack-comments, zodat je de supplier ook via een command-comment kunt zetten.
- Specs en technische documentatie aangepast: `copilot` is de GitHub Copilot CLI adapter; `microsoft` blijft een toekomstige Microsoft/Azure supplier.
- Tests aangepast voor de nieuwe supplierwaarde en gericht gedraaid voor YouTrack, manual commands en agent supplier routing.
