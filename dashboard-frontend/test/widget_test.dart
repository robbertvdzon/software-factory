import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/main.dart';

void main() {
  testWidgets('shows login screen', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const SoftwareFactoryDashboard());
    await tester.pumpAndSettle();

    expect(find.text('Software Factory'), findsOneWidget);
    expect(find.text('Inloggen met Google'), findsOneWidget);
  });

  testWidgets('large-text preference defaults off and scales MediaQuery text when loaded', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const SoftwareFactoryDashboard());
    await tester.pumpAndSettle();

    expect(TextScalePreference.enabled.value, isFalse);
    final loginScaler = MediaQuery.of(tester.element(find.text('Software Factory'))).textScaler;
    expect(loginScaler.scale(10), 10);

    await TextScalePreference.setEnabled(true);
    await tester.pumpAndSettle();

    final scaledScaler = MediaQuery.of(tester.element(find.text('Software Factory'))).textScaler;
    expect(scaledScaler.scale(10), 10 * largeTextScale);
  });

  testWidgets('large-text preference is loaded from and saved to shared_preferences', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({'large_text_enabled': true});
    await tester.pumpWidget(const SoftwareFactoryDashboard());
    await tester.pumpAndSettle();

    expect(TextScalePreference.enabled.value, isTrue);

    await TextScalePreference.setEnabled(false);
    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getBool('large_text_enabled'), isFalse);
  });
}
