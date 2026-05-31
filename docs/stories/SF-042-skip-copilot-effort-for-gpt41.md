# SF-042 - Skip Copilot effort for GPT-4.1

## Story

Als een story met `AI-supplier=copilot` en `AI Level=0` draait, kiest de
orchestrator `gpt-4.1`. De Copilot CLI accepteert voor dit model geen
reasoning-effort configuratie. De agent moet daarom bij dit model geen
`--effort` argument meesturen, zodat level 0 bruikbaar blijft.

Waargenomen fout:

```text
Error: Model "gpt-4.1" does not support reasoning effort configuration (requested: "low").
```

## Stappenplan

[x]: Reproduceer de fout op basis van de containerlog.
[x]: Pas de Copilot command builder aan zodat `gpt-4.1` geen `--effort` krijgt.
[x]: Laat effort voor de andere Copilot modellen intact.
[x]: Werk specs en technische docs bij.
[x]: Voeg een regressietest toe.
[x]: Draai de tests.

## Uitwerking

De Copilot adapter filtert `--effort` nu weg voor modellen waarvan bekend is dat
ze geen reasoning-effort ondersteunen. Voor nu is dat `gpt-4.1`, het model dat
bij Copilot level 0 wordt gekozen. De `SF_AI_EFFORT` waarde mag nog steeds in
de context bestaan; hij wordt alleen niet als CLI-argument doorgegeven voor dit
model.
