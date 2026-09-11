# Externe systemen

## Agent Runtime v2

De hoofdapp gebruikt de Runtime `/v2`-API voor jobcreate/get/cancel, events, resultaten, artifacts,
execution options, repositoryaliassen en hervatbare uploads. Authenticatie loopt met
`SF_AGENT_RUNTIME_TOKEN`.

Runtime krijgt alleen domeinminimale input. Voor repositorywerk is dat een geregistreerde alias en
branch. Runtime beheert workerselectie, providercredential, Gitcredential, tijdelijke checkout,
verificatie, commit/push, transcript, artifacts, usage en kosten.

## PostgreSQL

PostgreSQL is de bron van waarheid voor tracker, stories/subtaken, story-/agentruns, durable
completion, modelconfiguratie, inputuploads, audits, Telegram, knowledge en maintenance. Flyway
migreert `SF_DATABASE_SCHEMA` bij start.

## GitHub

Software Factory gebruikt `SF_GITHUB_TOKEN` om remote storybranches, pull requests, checkstatus,
merge en releases te beheren. De Runtime-worker heeft een eigen Gitidentity/credential voor commits
en pushes; die credential wordt niet aan Factory verstrekt.

Een apart `SF_GITHUB_PACKAGES_TOKEN` kan ghcr.io-cleanup minimaal scopen.

## Dashboard en bridge

`dashboard-frontend` praat met `dashboard-backend`. Zolang de hoofdapp nog lokaal draait, verbindt
die uitgaand met de backend via de geauthenticeerde WebSocketbridge (`SF_BRIDGE_URLS` en
`SF_BRIDGE_TOKEN`). Het Product Factory-contract loopt via dezelfde backendgrens met een eigen
`SF_PRODUCT_FACTORY_TOKEN`.

De bridge is tijdelijk en verdwijnt wanneer de hoofdapp naar OpenShift verhuist.

## Telegram

Telegram Bot API levert storymeldingen, vragen/antwoorden en chatinput. De conversationele AI-beurt
zelf gaat door naar Agent Runtime. Foto's worden als Runtime-inputobject verstuurd en het tijdelijke
lokale downloadbestand wordt verwijderd.

## OpenShift/Kubernetes

Frontend en dashboard-backend draaien op OpenShift. Software Factory kan daarnaast preview- en
deploymentstatus en cleanup beheren met `SF_KUBECONFIG` en optioneel een apart
`SF_PREVIEW_CLEANUP_KUBECONFIG`. Deze credentials gaan nooit naar Runtime of AI.

De Runtime-worker mag extern op een MacBook draaien; dat is onderdeel van Agent Runtime en geen
lokale Software Factory-component.

## Google OIDC

Dashboardlogin gebruikt Google OIDC, een e-mailallowlist en een ondertekende remember-cookie. Op
HTTPS moet de cookie secure zijn. Een verlopen sessie moet de frontend terugbrengen naar een
bruikbare loginflow.

## Targetprojecten

Targetrepositories leveren code, `.factory/verification.yaml` en `.factory/nightly/`-auditdefinities.
Hun lokale gitignored `secrets.env` is nadrukkelijk geen extern contract: Factory en Runtime lezen
of muteren het niet.
