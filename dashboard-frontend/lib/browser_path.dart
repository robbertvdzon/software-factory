import 'package:flutter/services.dart';

/// Houdt de adresbalk gelijk aan het scherm dat de gebruiker ziet, zodat een refresh of een
/// gekopieerde link op dezelfde pagina uitkomt (zie [parseAppPath] in deep_link.dart voor de
/// omgekeerde richting bij een koude laadbeurt).
///
/// De app navigeert met de gewone Navigator (geen Router): de app-shell zet het "grondpad"
/// (`/stories`, `/settings`, ...) en een detailscherm dat erbovenop gepusht wordt, legt zijn
/// eigen pad tijdelijk over dat grondpad heen. Bij het sluiten van het detailscherm komt het
/// grondpad weer tevoorschijn. De adresbalk wordt altijd vervángen (`replace: true`), dus de
/// browsergeschiedenis krijgt geen extra items: de app-eigen terug-knop blijft de manier om
/// terug te gaan, precies zoals voorheen.
///
/// Op Android is de melding aan de engine een no-op; de aanroep is dezelfde die de Navigator
/// zelf voor benoemde routes doet.
class BrowserPath {
  BrowserPath._();

  static final _stack = <String>[];

  /// Het pad dat op dit moment in de adresbalk hoort te staan.
  static String? get current => _stack.isEmpty ? null : _stack.last;

  /// Zet het grondpad van de app-shell (het geselecteerde navigatie-item).
  static void showRoot(String path) {
    if (_stack.isEmpty) {
      _stack.add(path);
    } else {
      _stack[0] = path;
    }
    _apply();
  }

  /// Een gepusht scherm (bijv. story-detail) legt zijn pad over het grondpad heen.
  static void push(String path) {
    _stack.add(path);
    _apply();
  }

  /// Haalt het pad van een gesloten scherm weer weg; het onderliggende pad verschijnt weer.
  static void pop(String path) {
    final index = _stack.lastIndexOf(path);
    if (index <= 0) return; // het grondpad blijft altijd staan
    _stack.removeAt(index);
    _apply();
  }

  /// Alleen voor tests: begin met een lege stapel.
  static void reset() => _stack.clear();

  static void _apply() {
    final path = current;
    if (path == null) return;
    SystemNavigator.routeInformationUpdated(uri: Uri(path: path), replace: true);
  }
}
