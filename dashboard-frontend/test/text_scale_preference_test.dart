import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/main.dart';
import 'package:softwarefactory_dashboard/text_scale_preference.dart';

void main() {
  group('TextScalePreference', () {
    test('defaults to disabled/scale 1.0 when nothing is stored', () async {
      SharedPreferences.setMockInitialValues({});
      final pref = TextScalePreference();
      await pref.load();

      expect(pref.enabled, isFalse);
      expect(pref.scaleFactor, 1.0);
    });

    test('load() restores a previously saved "aan"-voorkeur', () async {
      SharedPreferences.setMockInitialValues({'large_text_enabled': true});
      final pref = TextScalePreference();
      await pref.load();

      expect(pref.enabled, isTrue);
      expect(pref.scaleFactor, TextScalePreference.largeScaleFactor);
    });

    test('setEnabled() notifies listeners and persists the voorkeur lokaal', () async {
      SharedPreferences.setMockInitialValues({});
      final pref = TextScalePreference();
      await pref.load();

      var notified = 0;
      pref.addListener(() => notified++);

      await pref.setEnabled(true);
      expect(pref.enabled, isTrue);
      expect(notified, 1);

      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getBool('large_text_enabled'), isTrue);

      await pref.setEnabled(false);
      expect(pref.enabled, isFalse);
      expect(notified, 2);
      expect(prefs.getBool('large_text_enabled'), isFalse);
    });
  });

  group('SoftwareFactoryDashboard', () {
    testWidgets('past de opgeslagen "Grote letters"-voorkeur app-breed toe', (WidgetTester tester) async {
      SharedPreferences.setMockInitialValues({'large_text_enabled': true});
      await tester.pumpWidget(const SoftwareFactoryDashboard());
      await tester.pumpAndSettle();

      final mediaQuery = MediaQuery.of(tester.element(find.text('Software Factory')));
      expect(mediaQuery.textScaler.scale(10), TextScaler.linear(TextScalePreference.largeScaleFactor).scale(10));
    });

    testWidgets('gebruikt de standaard tekstgrootte als de voorkeur uit staat', (WidgetTester tester) async {
      SharedPreferences.setMockInitialValues({'large_text_enabled': false});
      await tester.pumpWidget(const SoftwareFactoryDashboard());
      await tester.pumpAndSettle();

      final mediaQuery = MediaQuery.of(tester.element(find.text('Software Factory')));
      expect(mediaQuery.textScaler.scale(10), TextScaler.linear(1.0).scale(10));
    });
  });
}
