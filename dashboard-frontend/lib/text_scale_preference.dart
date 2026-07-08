import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// App-brede "Grote letters"-voorkeur (SF-846): een vaste, gematigde schaalfactor die de tekst
/// op alle schermen (incl. login, dat vóór [AppState] bestaat) direct vergroot. Losstaand van
/// [AppState] zodat `main.dart` 'm al vóór inloggen kan toepassen. Lokale, per-device opslag via
/// `shared_preferences`, zelfde patroon als `stories_filter_*` in `stories_screen.dart`.
class TextScalePreference extends ChangeNotifier {
  static const _prefsKey = 'large_text_enabled';
  static const largeScaleFactor = 1.2;

  bool _enabled = false;
  bool get enabled => _enabled;

  double get scaleFactor => _enabled ? largeScaleFactor : 1.0;

  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    _enabled = prefs.getBool(_prefsKey) ?? false;
    notifyListeners();
  }

  Future<void> setEnabled(bool value) async {
    if (_enabled == value) return;
    _enabled = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_prefsKey, value);
  }
}
