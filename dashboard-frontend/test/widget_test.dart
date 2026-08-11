import 'dart:convert';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/deep_link.dart';
import 'package:softwarefactory_dashboard/main.dart';

void main() {
  testWidgets('shows login screen', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const SoftwareFactoryDashboard());
    await tester.pumpAndSettle();

    expect(find.text('Software Factory'), findsOneWidget);
    expect(find.text('Inloggen met Google'), findsOneWidget);
  });

  testWidgets('deep link toont bij een geldige sessie direct de changelog i.p.v. de app-shell', (tester) async {
    SharedPreferences.setMockInitialValues({'software_factory_dashboard_token': 'test-token'});
    final mockClient = MockClient((request) async {
      if (request.url.toString().contains('/api/v1/changelog/')) {
        return http.Response(jsonEncode({'entries': <Map<String, dynamic>>[], 'errors': <String>[]}), 200);
      }
      return http.Response(jsonEncode({'connected': true, 'since': null, 'factoryVersion': null}), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        const SoftwareFactoryDashboard(initialDestination: ChangelogDestination('demo')),
      );
      // Geen pumpAndSettle: AppState pollt met een periodieke timer (zie tip
      // polling-timer-breaks-pumpandsettle-recipe).
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }

      expect(find.text('Changelog — demo'), findsOneWidget);
      expect(find.text('Nog geen changelog-items voor dit project.'), findsOneWidget);

      // Dispose binnen dezelfde zone zodat de status-timer gecanceld wordt.
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    }, () => mockClient);
  });

  testWidgets('zonder deep link blijft het bestaande app-shell-gedrag ongewijzigd', (tester) async {
    SharedPreferences.setMockInitialValues({'software_factory_dashboard_token': 'test-token'});
    final mockClient = MockClient(
      (request) async => http.Response(jsonEncode({'connected': true, 'errors': <String>[]}), 200),
    );

    await http.runWithClient(() async {
      await tester.pumpWidget(const SoftwareFactoryDashboard());
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }

      expect(find.text('Changelog — demo'), findsNothing);

      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    }, () => mockClient);
  });
}
