import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

/// Praat met de bridge-backend (`dashboard-backend`), zie
/// docs/ontwerp-bridge-dashboard.md §5. Elke lees-operatie is een simpele REST-GET;
/// de backend vertaalt die zelf naar een bridge-request naar de factory.
class ApiClient {
  static const baseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: '');
  static const _tokenKey = 'software_factory_dashboard_token';
  static const _usernameKey = 'software_factory_dashboard_username';

  String? token;
  String? storedUsername;

  Future<void> restoreSession() async {
    final prefs = await SharedPreferences.getInstance();
    token = prefs.getString(_tokenKey);
    storedUsername = prefs.getString(_usernameKey);
  }

  /// Ruilt een Google ID-token in voor een eigen sessie-token bij de backend
  /// (`POST /api/v1/auth/google`). Het sessie-token wordt daarna identiek aan de vorige
  /// flow bewaard en als `Bearer`-header hergebruikt; [storedUsername] wordt het e-mailadres.
  Future<void> loginWithGoogle(String idToken) async {
    final response = await http.post(
      Uri.parse('$baseUrl/api/v1/auth/google'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'idToken': idToken}),
    );
    // Bij een afgewezen login (401 ongeldig token, 403 niet op de allowlist) toont de backend
    // de reden in `message` (server.error.include-message: always) — dat is geen "sessie
    // verlopen" (er was nog geen sessie), dus niet hergebruiken als UnauthorizedException.
    if (response.statusCode >= 400) {
      throw GoogleLoginRejectedException(_extractMessage(response));
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    token = body['token'] as String;
    storedUsername = body['username'] as String?;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_tokenKey, token!);
    if (storedUsername != null) {
      await prefs.setString(_usernameKey, storedUsername!);
    }
  }

  Future<void> clearSession() async {
    token = null;
    storedUsername = null;
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
    await prefs.remove(_usernameKey);
  }

  Map<String, String> authHeaders() => {
    if (token != null) 'Authorization': 'Bearer $token',
  };

  /// GET [path] en geef de JSON-body als map terug. Gooit [FactoryOfflineException]
  /// bij HTTP 503 (`FACTORY_OFFLINE`) zodat schermen dat uniform als banner tonen.
  Future<Map<String, dynamic>> getJson(String path) async {
    final response = await http.get(Uri.parse('$baseUrl$path'), headers: authHeaders());
    await _throwOnError(response);
    return Map<String, dynamic>.from(jsonDecode(response.body) as Map);
  }

  /// POST [path] met [body] als JSON; geeft de JSON-respons als map terug (leeg bij een lege body).
  Future<Map<String, dynamic>> postJson(String path, [Map<String, dynamic> body = const {}]) async {
    final response = await http.post(
      Uri.parse('$baseUrl$path'),
      headers: {...authHeaders(), 'Content-Type': 'application/json'},
      body: jsonEncode(body),
    );
    await _throwOnError(response);
    if (response.body.isEmpty) return {};
    return Map<String, dynamic>.from(jsonDecode(response.body) as Map);
  }

  /// Volledige URL voor een binair endpoint (bv. een screenshot-image); de widget
  /// die dit toont stuurt zelf de Authorization-header mee via [authHeaders].
  String url(String path) => '$baseUrl$path';

  Future<void> _throwOnError(http.Response response) async {
    if (response.statusCode < 400) return;
    if (response.statusCode == 401) {
      await clearSession();
      throw const UnauthorizedException();
    }
    if (response.statusCode == 503) {
      throw const FactoryOfflineException();
    }
    throw Exception('HTTP ${response.statusCode}: ${response.body}');
  }
}

class UnauthorizedException implements Exception {
  const UnauthorizedException();
  @override
  String toString() => 'Sessie verlopen. Log opnieuw in.';
}

/// De backend wees de Google-login zelf af (ongeldig token of e-mailadres niet op de allowlist) —
/// anders dan [UnauthorizedException], die een reeds bestaande sessie betreft.
class GoogleLoginRejectedException implements Exception {
  final String message;
  const GoogleLoginRejectedException(this.message);
  @override
  String toString() => message;
}

String _extractMessage(http.Response response) {
  try {
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    final message = body['message'] as String?;
    if (message != null && message.isNotEmpty && message != 'No message available') {
      return message;
    }
  } catch (_) {
    // Geen JSON-body; val terug op de ruwe tekst.
  }
  return 'Inloggen mislukt (HTTP ${response.statusCode}).';
}

class FactoryOfflineException implements Exception {
  const FactoryOfflineException();
  @override
  String toString() => 'Factory niet verbonden.';
}

/// Lichtgewicht Server-Sent-Events-client voor `/api/v1/events` (de "changed"-push,
/// zie §5) — geen refresh-knoppen nodig, schermen luisteren op [events].
class SseClient {
  final ApiClient api;

  /// Wachttijd voor de eerste nieuwe poging, daarna verdubbelend tot [maxBackoff]. Instelbaar zodat
  /// tests niet seconden hoeven te wachten.
  final Duration minBackoff;
  final Duration maxBackoff;
  StreamSubscription<String>? _subscription;
  http.Client? _httpClient;
  final _controller = StreamController<String>.broadcast();
  final _connectionController = StreamController<bool>.broadcast();

  /// Volgnummer per connect-poging: callbacks van een verlaten poging worden genegeerd. Zonder dit
  /// vermenigvuldigden de verbindingen zich — `onError` én `onDone` vuren allebei bij een
  /// socketfout, en elke gemiste stream plande er weer twee, wat de offline-banner liet knipperen.
  int _generation = 0;
  Timer? _reconnectTimer;
  bool _connecting = false;
  bool _disposed = false;
  late Duration _backoff = minBackoff;

  SseClient(
    this.api, {
    this.minBackoff = const Duration(seconds: 3),
    this.maxBackoff = const Duration(seconds: 30),
  });

  Stream<String> get events => _controller.stream;
  Stream<bool> get connectionChanges => _connectionController.stream;

  Future<void> connect() async {
    if (_disposed || _connecting) return;
    _connecting = true;
    _reconnectTimer?.cancel();
    final generation = ++_generation;
    final client = http.Client();
    try {
      await _subscription?.cancel();
      _subscription = null;
      _httpClient?.close();
      _httpClient = client;
      final request = http.Request('GET', Uri.parse(api.url('/api/v1/events')));
      request.headers.addAll(api.authHeaders());
      final response = await client.send(request);
      if (_disposed || generation != _generation) {
        client.close();
        return;
      }
      if (response.statusCode != 200) {
        _onDisconnected(generation);
        return;
      }
      _backoff = minBackoff;
      _connectionController.add(true);
      var buffer = '';
      _subscription = response.stream.transform(utf8.decoder).listen(
        (chunk) {
          buffer += chunk;
          while (buffer.contains('\n\n')) {
            final index = buffer.indexOf('\n\n');
            final rawEvent = buffer.substring(0, index);
            buffer = buffer.substring(index + 2);
            final eventLine = rawEvent
                .split('\n')
                .firstWhere((line) => line.startsWith('event:'), orElse: () => '');
            if (eventLine.isNotEmpty) {
              _controller.add(eventLine.substring(6).trim());
            }
          }
        },
        onError: (_) => _onDisconnected(generation),
        onDone: () => _onDisconnected(generation),
      );
    } catch (_) {
      _onDisconnected(generation);
    } finally {
      _connecting = false;
    }
  }

  /// Meldt het verlies één keer en plant precies één nieuwe poging. Een melding van een verlaten
  /// poging ([generation] achterhaald) telt niet mee — anders stapelen de reconnects zich op.
  void _onDisconnected(int generation) {
    if (_disposed || generation != _generation) return;
    _connectionController.add(false);
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(_backoff, connect);
    final next = _backoff * 2;
    _backoff = next > maxBackoff ? maxBackoff : next;
  }

  void dispose() {
    _disposed = true;
    _reconnectTimer?.cancel();
    _subscription?.cancel();
    _httpClient?.close();
    _controller.close();
    _connectionController.close();
  }
}

List<Map<String, dynamic>> asList(dynamic value) => (value as List? ?? [])
    .map((item) => Map<String, dynamic>.from(item as Map))
    .toList();

String text(dynamic value, {String fallback = ''}) {
  final result = value?.toString() ?? '';
  return result.isEmpty || result == 'null' ? fallback : result;
}

int number(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

bool boolValue(dynamic value) => value == true || value?.toString().toLowerCase() == 'true';

String formatBytes(int bytes) {
  if (bytes <= 0) return '-';
  return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
}

String formatTimestamp(dynamic value) {
  final raw = text(value, fallback: '-');
  if (raw == '-') return raw;
  final parsed = DateTime.tryParse(raw);
  if (parsed == null) return raw;
  final local = parsed.toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}';
}

/// Formatteert een ISO-8601-tijdstip als "12 min geleden"/"3 u geleden"/"5 d geleden"; `-` als er
/// geen (geldig) tijdstip bekend is. Voor de branch-timeline (commit-tijd per rij) — grover dan
/// [formatDuration] omdat hier de leesbaarheid van "hoe lang geleden" belangrijker is dan precisie.
String formatRelativeTime(dynamic value) {
  final raw = text(value, fallback: '-');
  if (raw == '-') return raw;
  final parsed = DateTime.tryParse(raw);
  if (parsed == null) return raw;
  final diff = DateTime.now().difference(parsed.toLocal());
  if (diff.inSeconds < 60) return 'net';
  if (diff.inMinutes < 60) return '${diff.inMinutes} min geleden';
  if (diff.inHours < 24) return '${diff.inHours} u geleden';
  return '${diff.inDays} d geleden';
}

/// Formatteert een aantal seconden als `5m28s`/`10s`/`3u12m`; `-` als er geen (geldige) duur bekend
/// is. Gedeeld tussen build-duur (builds-lijst) en pod-uptime (live-versie op het Projects-scherm).
String formatDuration(dynamic value) {
  final seconds = value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');
  if (seconds == null || seconds <= 0) return '-';
  final hours = seconds ~/ 3600;
  final minutes = (seconds % 3600) ~/ 60;
  final remaining = seconds % 60;
  if (hours > 0) return '${hours}u${minutes.toString().padLeft(2, '0')}m';
  if (minutes == 0) return '${remaining}s';
  return '${minutes}m${remaining.toString().padLeft(2, '0')}s';
}
