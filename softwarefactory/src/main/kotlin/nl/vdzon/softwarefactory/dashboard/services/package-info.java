/**
 * Named interface van de `web`-module: de bridge (`bridge.BridgeRequestHandler`/`BridgeClient`)
 * hergebruikt {@code FactoryDashboardService}, {@code DashboardEventBus} en
 * {@code FactoryVersionService} rechtstreeks in plaats van businesslogica te dupliceren (zie
 * docs/ontwerp-bridge-dashboard.md §7). Zonder deze annotatie beschouwt Spring Modulith dit
 * package als intern aan `web` en faalt {@code ModulithArchitectureTest}.
 */
@org.springframework.modulith.NamedInterface("services")
package nl.vdzon.softwarefactory.dashboard.services;
