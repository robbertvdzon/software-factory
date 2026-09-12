import 'dart:async';

import 'package:flutter/foundation.dart';

import 'api_client.dart';

/// Gedeelde app-state: sessie, live "changed"-events en de bereikbaarheid van de backend
/// ("geen refresh-knoppen": schermen luisteren op events).
class AppState extends ChangeNotifier {
  final ApiClient api;
  late final SseClient sse;
  Timer? _statusTimer;

  bool connected = true;
  /// Aantal mislukte `/api/v1/status`-calls op rij; zie [refreshStatus].
  int _statusFailures = 0;
  String? connectedSince;
  String? factoryVersion;
  int changedTick = 0;
  int myActionsCount = 0;
  int auditQuestionCount = 0;

  AppState(this.api) {
    sse = SseClient(api);
  }

  /// Start meteen: de SSE-connect en de eerste status/my-actions-fetch gaan op de achtergrond
  /// parallel lopen i.p.v. serieel vóór de eerste render, zodat de UI (bv. Stories) niet op deze
  /// calls hoeft te wachten bij een volledige page-refresh (F5).
  Future<void> start() async {
    sse.events.listen((_) {
      changedTick++;
      notifyListeners();
      refreshStatus();
    });
    // De SSE-stream is alleen het live-eventkanaal; of de backend bereikbaar is, bewijst
    // /api/v1/status. Bij elke transportwissel dus altijd de echte status opnieuw ophalen
    // i.p.v. de banner blind op de SSE-staat te zetten (anders blijft "offline" hangen na
    // een kortstondige SSE-reconnect).
    sse.connectionChanges.listen((_) => refreshStatus());
    _statusTimer = Timer.periodic(const Duration(seconds: 20), (_) => refreshStatus());
    unawaited(sse.connect());
    unawaited(refreshStatus());
  }

  Future<void> refreshStatus() async {
    try {
      final status = await api.getJson('/api/v1/status');
      _statusFailures = 0;
      connected = boolValue(status['connected']);
      connectedSince = status['since'] as String?;
      factoryVersion = status['factoryVersion'] as String?;
    } catch (_) {
      // Eén mislukte call is meestal een netwerk-hik (transportwissel, slapende telefoon), geen
      // onbereikbare backend. Meteen omslaan liet de banner knipperen; pas na twee mislukkingen
      // op rij is het aannemelijk genoeg om te tonen.
      _statusFailures++;
      if (_statusFailures >= 2) connected = false;
    }
    unawaited(refreshMyActionsCount());
    unawaited(refreshAuditQuestionCount());
    notifyListeners();
  }

  Future<void> refreshMyActionsCount() async {
    try {
      final body = await api.getJson('/api/v1/my-actions/count');
      myActionsCount = number(body['count']);
      notifyListeners();
    } catch (_) {
      // Best-effort badge; een mislukte telling is geen reden om iets anders te breken.
    }
  }

  /// Openstaande auditvragen — een audit die wacht is onzichtbaar zonder dit bolletje, want hij
  /// staat niet tussen de story-acties (een audit heeft geen story).
  Future<void> refreshAuditQuestionCount() async {
    try {
      final body = await api.getJson('/api/v1/audits/questions/count');
      auditQuestionCount = number(body['count']);
      notifyListeners();
    } catch (_) {
      // Best-effort badge, net als de My actions-telling.
    }
  }

  void stop() {
    _statusTimer?.cancel();
    sse.dispose();
  }

  /// Simuleert een "changed"-SSE-push (zie [start]) zonder een echte SSE-verbinding — voor widgets
  /// die op [changedTick] auto-verversen, bv. `_BranchTimelineSection` in `projects_screen.dart`.
  @visibleForTesting
  void simulateChanged() {
    changedTick++;
    notifyListeners();
  }
}
