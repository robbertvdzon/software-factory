import 'dart:async';

import 'package:flutter/foundation.dart';

import 'api_client.dart';

/// Gedeelde app-state: sessie, live "changed"-events en de factory-online-status
/// (§9 UI-richting: "Factory-status prominent" + "geen refresh-knoppen").
class AppState extends ChangeNotifier {
  final ApiClient api;
  late final SseClient sse;
  Timer? _statusTimer;

  bool connected = true;
  String? connectedSince;
  String? factoryVersion;
  int changedTick = 0;
  int myActionsCount = 0;

  AppState(this.api) {
    sse = SseClient(api);
  }

  /// Start meteen (geeft niet op de bridge-round-trips te wachten): de SSE-connect en de eerste
  /// status/my-actions-fetch gaan op de achtergrond parallel lopen i.p.v. serieel vóór de
  /// eerste render. Elke call gaat via de websocket-bridge naar de laptop-factory (tot 30s
  /// timeout) — serieel wachten hierop vóórdat de UI (bv. Stories) zelf mag laden, gaf een
  /// merkbare vertraging bij elke volledige page-refresh (F5), terwijl in-app-navigeren snel
  /// bleef omdat AppState dan al bestond en dit alleen bij opstart gebeurde.
  Future<void> start() async {
    sse.events.listen((_) {
      changedTick++;
      notifyListeners();
      refreshStatus();
    });
    // De SSE-stream is alleen het live-eventkanaal browser<->backend; of de fáctory
    // bridge-verbonden is, weet alleen /api/v1/status. Bij elke transportwissel dus
    // altijd de echte status opnieuw ophalen i.p.v. de banner blind op de SSE-staat
    // te zetten (anders blijft "offline" hangen na een kortstondige SSE-reconnect).
    sse.connectionChanges.listen((_) => refreshStatus());
    _statusTimer = Timer.periodic(const Duration(seconds: 20), (_) => refreshStatus());
    unawaited(sse.connect());
    unawaited(refreshStatus());
  }

  Future<void> refreshStatus() async {
    try {
      final status = await api.getJson('/api/v1/status');
      connected = boolValue(status['connected']);
      connectedSince = status['since'] as String?;
      factoryVersion = status['factoryVersion'] as String?;
    } catch (_) {
      connected = false;
    }
    unawaited(refreshMyActionsCount());
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

  void stop() {
    _statusTimer?.cancel();
    sse.dispose();
  }
}
