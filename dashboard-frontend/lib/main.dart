import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';

import 'api_client.dart';
import 'app_shell.dart';
import 'app_state.dart';
import 'google_signin_button_stub.dart' if (dart.library.html) 'google_signin_button_web.dart' as gis_button;

void main() {
  runApp(const SoftwareFactoryDashboard());
}

const buildSha = String.fromEnvironment('BUILD_SHA', defaultValue: 'dev');
const buildTimestamp = String.fromEnvironment('BUILD_TIMESTAMP', defaultValue: 'dev');

/// De OAuth-web-client-ID komt via een build-time waarde (`--dart-define=GOOGLE_CLIENT_ID=...`);
/// het aanmaken van de OAuth-client in Google Cloud is een externe, handmatige stap.
const googleClientId = String.fromEnvironment('GOOGLE_CLIENT_ID', defaultValue: '');

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

/// Kleuren 1-op-1 overgenomen van het oude Kotlin-dashboard (`sf-ui.css` `:root`-variabelen)
/// — gedempte, "muted" tinten i.p.v. Material's felle default-paars.
class SfColors {
  static const bg = Color(0xfffcfcfb);
  static const ink = Color(0xff2b2a33);
  static const muted = Color(0xff75727f);
  static const faint = Color(0xffa6a2ad);
  static const line = Color(0xffecebe7);
  static const lineStrong = Color(0xffdedcd6);
  static const accent = Color(0xff3f3d56);
  static const accentSoft = Color(0xffecebf2);
  static const green = Color(0xff5a7d6f);
  static const greenSoft = Color(0xffe6efea);
  static const amber = Color(0xff9a7b4a);
  static const amberSoft = Color(0xfff7efde);
  static const red = Color(0xff9a6a6a);
  static const redSoft = Color(0xfff3e9e9);
  static const blue = Color(0xff5b6b86);
  static const blueSoft = Color(0xffe7eaf0);
}

class AppTheme {
  static ThemeData light() {
    final scheme = ColorScheme.fromSeed(seedColor: SfColors.accent, brightness: Brightness.light).copyWith(
      primary: SfColors.accent,
      onPrimary: Colors.white,
      secondary: SfColors.accentSoft,
      onSecondary: SfColors.accent,
      surface: Colors.white,
      onSurface: SfColors.ink,
      error: SfColors.red,
    );
    return ThemeData(
      colorScheme: scheme,
      scaffoldBackgroundColor: SfColors.bg,
      useMaterial3: true,
      textTheme: Typography.blackMountainView.apply(bodyColor: SfColors.ink, displayColor: SfColors.ink),
      appBarTheme: AppBarTheme(elevation: 0, centerTitle: false, backgroundColor: SfColors.bg, foregroundColor: SfColors.ink),
      cardTheme: CardThemeData(
        color: Colors.white,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: const BorderSide(color: SfColors.line)),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xfff1f0ec),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide.none),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: SfColors.accent,
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
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
  final googleSignIn = GoogleSignIn(
    clientId: googleClientId.isEmpty ? null : googleClientId,
    scopes: const ['email'],
  );
  var initialized = false;
  var loading = false;
  String? error;
  StreamSubscription<GoogleSignInAccount?>? _authSub;

  @override
  void initState() {
    super.initState();
    _restoreSession();
    // Op web moet de officiële GIS-knop gerenderd worden (zie google_signin_button_web.dart);
    // een geslaagde login komt dan binnen via deze stream i.p.v. via een directe signIn()-return.
    if (kIsWeb) {
      _authSub = googleSignIn.onCurrentUserChanged.listen(_handleGoogleAccount);
    }
  }

  @override
  void dispose() {
    _authSub?.cancel();
    appState?.stop();
    super.dispose();
  }

  Future<void> _handleGoogleAccount(GoogleSignInAccount? account) async {
    if (account == null || loading) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final auth = await account.authentication;
      final idToken = auth.idToken;
      if (idToken == null) {
        throw Exception('Geen Google ID-token ontvangen. Controleer de OAuth-client-ID.');
      }
      await api.loginWithGoogle(idToken);
      await _enterApp();
    } catch (e) {
      error = e.toString();
      await googleSignIn.signOut().catchError((_) => null);
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _restoreSession() async {
    await api.restoreSession();
    if (api.token != null) await _enterApp();
    if (!mounted) return;
    setState(() => initialized = true);
  }

  Future<void> _enterApp() async {
    final state = AppState(api);
    await state.start();
    if (mounted) setState(() => appState = state);
  }

  Future<void> _loginWithGoogle() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final account = await googleSignIn.signIn();
      if (account == null) {
        // Gebruiker heeft de Google-popup geannuleerd.
        return;
      }
      final auth = await account.authentication;
      final idToken = auth.idToken;
      if (idToken == null) {
        throw Exception('Geen Google ID-token ontvangen. Controleer de OAuth-client-ID.');
      }
      await api.loginWithGoogle(idToken);
      await _enterApp();
    } catch (e) {
      error = e.toString();
      // Voorkom dat een half-afgeronde Google-sessie een nieuwe poging blokkeert.
      await googleSignIn.signOut().catchError((_) => null);
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
                  decoration: BoxDecoration(color: SfColors.accent, borderRadius: BorderRadius.circular(14)),
                  child: const Text('SF', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: Colors.white)),
                ),
                const SizedBox(height: 22),
                const Text('Software Factory', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                const Text('Login op het bridge-dashboard', style: TextStyle(color: Colors.black54)),
                const SizedBox(height: 24),
                if (kIsWeb)
                  // GoogleSignIn.signIn() is op web deprecated en werkt niet meer betrouwbaar
                  // (GIS vereist de eigen gerenderde knop); zie google_signin_button_web.dart.
                  Center(
                    child: loading
                        ? const Padding(
                            padding: EdgeInsets.symmetric(vertical: 10),
                            child: SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2)),
                          )
                        : SizedBox(height: 40, child: gis_button.renderGoogleButton()),
                  )
                else
                  FilledButton.icon(
                    onPressed: loading ? null : _loginWithGoogle,
                    icon: loading
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Icon(Icons.login),
                    label: Text(loading ? 'Inloggen...' : 'Inloggen met Google'),
                  ),
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

