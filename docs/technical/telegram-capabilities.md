# Telegram-capabilities

De publieke Telegramgrens bestaat uit vier smalle ports; concrete polling, opslag, clients en
assistentservices blijven intern.

| Consumer | Capability | Port | Contract |
| --- | --- | --- | --- |
| orchestratorpoller | periodieke notificaties | `TelegramNotifier` | `notifyPending()` |
| bridge | assistentstatus en berichten | `TelegramAssistantApi` | `status`, `handle` |
| nightly gateway | digest versturen | `TelegramMessageGateway` | `sendMessage` |
| nightly summarizer | AI-samenvatting | `AssistantClient` | `askForSummary` |

`AssistantReply`, `AssistantStatus` en `TelegramUpdate` zijn immutable data-classcontracten in de
named interface `telegram.models`. `PendingQuestion` en assistent-/opslagdetails blijven bij hun
interne implementatie. Er is bewust geen brede `TelegramApi`-facade.
