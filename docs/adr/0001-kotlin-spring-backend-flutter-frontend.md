# 0001 - Kotlin/Spring-backend gecombineerd met een Flutter-frontend

- Status: Accepted
- Datum: 2026-07-27

## Context

De repo bevat een backend die volledig in Kotlin/Spring Boot is opgebouwd, verdeeld
over Maven-modules (zie `docs/factory/technical-spec.md`): `softwarefactory` is de
orchestrator, en `dashboard-backend` is een dunne bridge-service (lokaal op poort
`9090`) die de agent-/story-data ontsluit voor het dashboard. Daarnaast bestaat er
een losstaande dashboard-frontend, `dashboard-frontend`, geschreven in Dart/Flutter
en met een eigen Docker-build buiten de Maven-aggregator om (geen Maven-module,
lokaal op poort `9080`).

Deze scheiding tussen een JVM/Maven-backend en een Flutter-frontend is al langere
tijd de bestaande situatie in de repo en informeel beschreven in
`docs/factory/technical-spec.md`, maar was tot nu toe niet vastgelegd als expliciet
architectuurbesluit. Deze ADR legt die reeds gemaakte keuze retroactief vast.

## Decision

We gebruiken Kotlin met Spring Boot voor de backend-services:

- `softwarefactory` — de orchestrator (Maven-module, root-aggregator).
- `dashboard-backend` — een dunne Spring Boot bridge-service die de dashboard-API
  aanbiedt aan de frontend.

Voor de dashboard-frontend gebruiken we Flutter (Dart):

- `dashboard-frontend` — een Flutter web-app die de `dashboard-backend`-API
  consumeert, met een eigen Docker-build los van de Maven-toolchain.

## Consequences

- Er zijn twee aparte build-toolchains te onderhouden: Maven/JVM voor de
  Kotlin/Spring-modules en een losse Docker-build voor Flutter/Dart.
- De backend- en frontend-code kennen aparte deploy-paden en artefacten (JVM-
  artefacten uit de Maven-build versus een Docker-image voor de Flutter-web-app),
  wat los van elkaar gepland en uitgerold moet worden.
- Beide technologiestacks (Kotlin/Spring/Maven en Flutter/Dart) moeten actief
  onderhouden worden, inclusief hun eigen dependency-, test- en tooling-beheer.
- Wijzigingen die zowel backend- als frontend-gedrag raken (bv. een nieuw
  API-contract) vereisen gecoördineerde aanpassingen in twee losse
  technologiestacks.
