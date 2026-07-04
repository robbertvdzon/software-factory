import 'package:flutter/material.dart';

import 'api_client.dart';
import 'app_shell.dart';
import 'app_state.dart';

void main() {
  runApp(const SoftwareFactoryDashboard());
}

const buildSha = String.fromEnvironment('BUILD_SHA', defaultValue: 'dev');
const buildTimestamp = String.fromEnvironment('BUILD_TIMESTAMP', defaultValue: 'dev');

class SoftwareFactoryDashboard extends StatelessWidget {
  const SoftwareFactoryDashboard({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Software Factory',
      theme: AppTheme.light(),
      home: const RootScreen(),
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
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide.none),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))),
      ),
    );
  }
}

/// Root: laadt de sessie, toont login of de app-shell, en start/stopt [AppState]
/// (live-events + status-polling) rond een geldige sessie.
class RootScreen extends StatefulWidget {
  const RootScreen({super.key});

  @override
  State<RootScreen> createState() => _RootScreenState();
}

class _RootScreenState extends State<RootScreen> {
  final api = ApiClient();
  AppState? appState;
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

  @override
  void dispose() {
    appState?.stop();
    super.dispose();
  }

  Future<void> _restoreSession() async {
    await api.restoreSession();
    if (api.storedUsername != null) username.text = api.storedUsername!;
    if (api.token != null) await _enterApp();
    if (!mounted) return;
    setState(() => initialized = true);
  }

  Future<void> _enterApp() async {
    final state = AppState(api);
    await state.start();
    if (mounted) setState(() => appState = state);
  }

  Future<void> _login() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      await api.login(username.text, password.text);
      password.clear();
      await _enterApp();
    } catch (e) {
      error = e.toString();
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!initialized) return _loadingView();
    if (api.token == null || appState == null) return _loginView();
    return AppShell(
      state: appState!,
      onLoggedOut: () => setState(() {
        appState?.stop();
        appState = null;
      }),
    );
  }

  Widget _loadingView() => const Scaffold(body: Center(child: CircularProgressIndicator()));

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
                  decoration: BoxDecoration(color: const Color(0xffdedafe), borderRadius: BorderRadius.circular(14)),
                  child: const Text('SF', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
                ),
                const SizedBox(height: 22),
                const Text('Software Factory', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                const Text('Login op het bridge-dashboard', style: TextStyle(color: Colors.black54)),
                const SizedBox(height: 20),
                TextField(controller: username, decoration: const InputDecoration(labelText: 'Gebruikersnaam')),
                const SizedBox(height: 12),
                TextField(
                  controller: password,
                  decoration: const InputDecoration(labelText: 'Wachtwoord'),
                  obscureText: true,
                  onSubmitted: (_) => loading ? null : _login(),
                ),
                const SizedBox(height: 20),
                FilledButton(onPressed: loading ? null : _login, child: Text(loading ? 'Inloggen...' : 'Inloggen')),
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(error!, style: const TextStyle(color: Colors.red)),
                  ),
                const SizedBox(height: 8),
                Text('Build: $buildSha · $buildTimestamp', style: const TextStyle(fontSize: 11, color: Colors.black45)),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

