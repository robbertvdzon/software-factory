# SF-162 Worklog — Story-brede test

## Story in eigen woorden

Test-loopback na SF-161 (implementatie merge/deploy-subtaken). De tester wees een bug aan in
`DeploySubtaskHandlerTest`: de test "null phase returns Skipped" gebruikte `DeployConfig.Skip`
als config, maar `DeploySubtaskHandler.process()` handelt `Skip` af vóór de `when (phase)`-switch
(roept direct `advanceChain` aan). Daardoor retourneert de methode nooit `Skipped` bij `Skip`-config,
ongeacht de phase. De test testte dus een situatie die de code niet dekt.

## Checklist

- [x] Bug geïdentificeerd: `DeploySubtaskHandlerTest` "null phase returns Skipped" gebruikte
      `DeployConfig.Skip` terwijl die config de `when(phase)`-branch short-circuit
- [x] Test gecorrigeerd: config gewijzigd naar `DeployConfig.OpenshiftWatch(...)` zodat de
      `null -> Skipped`-branch in de `when`-block bereikt wordt

## Wat is er gedaan en waarom

`DeploySubtaskHandler.process()` heeft een early-exit voor `DeployConfig.Skip`:

```kotlin
if (deployConfig is DeployConfig.Skip) {
    issueTrackerClient.updateIssueFields(...)
    return advanceChain(subtask)
}
```

Bij `Skip`-config wordt `advanceChain` altijd aangeroepen, ook bij null phase. De test
"null phase returns Skipped" verwachtte echter `IssueProcessResult.Skipped`. Dit is een
contradictie: de productie-code is correct (Skip → advance chain direct), de test was fout.

Fix: de test gebruikt nu `DeployConfig.OpenshiftWatch(namespace="ns", deployment="dep",
timeoutMinutes=5)` als config. Met een niet-Skip-config bereikt null phase de
`null -> IssueProcessResult.Skipped(...)` branch in de `when`-switch, wat het bedoelde
gedrag test.
