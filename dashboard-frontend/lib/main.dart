import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const SoftwareFactoryDashboard());
}

const buildSha = String.fromEnvironment('BUILD_SHA', defaultValue: 'dev');
const buildTimestamp = String.fromEnvironment(
  'BUILD_TIMESTAMP',
  defaultValue: 'dev',
);

class SoftwareFactoryDashboard extends StatelessWidget {
  const SoftwareFactoryDashboard({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Software Factory',
      theme: AppTheme.light(),
      home: const DashboardScreen(),
    );
  }
}

class AppTheme {
  static ThemeData light() {
    const seed = Color(0xff6366f1);
    final scheme = ColorScheme.fromSeed(seedColor: seed);
    return ThemeData(
      colorScheme: scheme,
      scaffoldBackgroundColor: Colors.white,
      useMaterial3: true,
      appBarTheme: const AppBarTheme(elevation: 0, centerTitle: false),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xfff1f3f8),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
    );
  }
}

/// Praat met de bridge-backend (`dashboard-backend`). In dit skelet is alleen
/// login geïmplementeerd; de bridge-operaties komen in latere fases.
class ApiClient {
  static const baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: '',
  );
  static const _tokenKey = 'software_factory_dashboard_token';
  static const _usernameKey = 'software_factory_dashboard_username';
  String? token;
  String? storedUsername;

  Future<void> restoreSession() async {
    final prefs = await SharedPreferences.getInstance();
    token = prefs.getString(_tokenKey);
    storedUsername = prefs.getString(_usernameKey);
  }

  Future<void> login(String username, String password) async {
    final response = await http.post(
      Uri.parse('$baseUrl/api/v1/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'username': username, 'password': password}),
    );
    if (response.statusCode == 401) {
      throw const UnauthorizedException();
    }
    if (response.statusCode >= 400) {
      throw Exception(response.body);
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    token = body['token'] as String;
    storedUsername = body['username'] as String? ?? username;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_tokenKey, token!);
    await prefs.setString(_usernameKey, storedUsername!);
  }

  Future<void> clearSession() async {
    token = null;
    storedUsername = null;
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
    await prefs.remove(_usernameKey);
  }
}

class UnauthorizedException implements Exception {
  const UnauthorizedException();

  @override
  String toString() => 'Ongeldige gebruikersnaam of wachtwoord.';
}

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final api = ApiClient();
  final username = TextEditingController(text: 'admin');
  final password = TextEditingController();
  var initialized = false;
  var loading = false;
  String? error;

  @override
  void initState() {
    super.initState();
    _restoreSession();
  }

  Future<void> _restoreSession() async {
    await api.restoreSession();
    if (api.storedUsername != null) username.text = api.storedUsername!;
    if (!mounted) return;
    setState(() => initialized = true);
  }

  Future<void> _login() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      await api.login(username.text, password.text);
      password.clear();
    } catch (e) {
      error = e.toString();
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _logout() async {
    await api.clearSession();
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    if (!initialized) return _loadingView();
    return api.token == null ? _loginView() : _homeView();
  }

  Widget _loadingView() =>
      const Scaffold(body: Center(child: CircularProgressIndicator()));

  Widget _loginView() => Scaffold(
    body: Center(
      child: SizedBox(
        width: 430,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Container(
                  height: 64,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: const Color(0xffdedafe),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: const Text(
                    'SF',
                    style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
                  ),
                ),
                const SizedBox(height: 22),
                const Text(
                  'Software Factory',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Login op het bridge-dashboard',
                  style: TextStyle(color: Colors.black54),
                ),
                const SizedBox(height: 20),
                TextField(
                  controller: username,
                  decoration: const InputDecoration(
                    labelText: 'Gebruikersnaam',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: password,
                  decoration: const InputDecoration(labelText: 'Wachtwoord'),
                  obscureText: true,
                  onSubmitted: (_) => loading ? null : _login(),
                ),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: loading ? null : _login,
                  child: Text(loading ? 'Inloggen...' : 'Inloggen'),
                ),
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      error!,
                      style: const TextStyle(color: Colors.red),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    ),
  );

  Widget _homeView() => Scaffold(
    appBar: AppBar(
      title: const Text('Software Factory'),
      actions: [
        IconButton(onPressed: _logout, icon: const Icon(Icons.logout)),
      ],
    ),
    body: Center(
      child: Text(
        'Ingelogd als ${api.storedUsername}.\n'
        'De bridge-schermen komen in een volgende fase.\n'
        'Build: $buildSha · $buildTimestamp',
        textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.black54),
      ),
    ),
  );
}
