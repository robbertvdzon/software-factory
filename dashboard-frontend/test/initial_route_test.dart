import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/deep_link.dart';
import 'package:softwarefactory_dashboard/main.dart';

/// De adresbalk moet op het gevraagde deep-link-adres blijven staan (AC 1 en 3).
///
/// `MaterialApp` geeft de Navigator `initialRoute = platformDispatcher.defaultRouteName`
/// mee met `reportsRouteUpdateToEngine: true`. Zonder eigen `onGenerateInitialRoutes`
/// vindt `Navigator.defaultGenerateInitialRoutes` geen route voor `/changelog/<project>`,
/// valt terug op de home-route met naam `/` en meldt díé naam aan de engine — waarna
/// `usePathUrlStrategy()` de browser-URL met een `replaceState` terugzet naar `/`.
/// Deze test asserteert de aangemelde routenaam, zonder browser.
void main() {
  Future<List<MethodCall>> announcedRoutes(WidgetTester tester, String path) async {
    SharedPreferences.setMockInitialValues({'software_factory_dashboard_token': 'test-token'});
    tester.binding.platformDispatcher.defaultRouteNameTestValue = path;
    addTearDown(tester.binding.platformDispatcher.clearDefaultRouteNameTestValue);

    final calls = <MethodCall>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.navigation, (call) async {
      calls.add(call);
      return null;
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.navigation, null),
    );

    final mockClient = MockClient((request) async {
      if (request.url.toString().contains('/api/v1/changelog/')) {
        return http.Response(jsonEncode({'entries': <Map<String, dynamic>>[], 'errors': <String>[]}), 200);
      }
      return http.Response(jsonEncode({'connected': true, 'since': null, 'factoryVersion': null}), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(SoftwareFactoryDashboard(initialDestination: parseDeepLink(path)));
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
      // Dispose binnen dezelfde zone zodat de status-timer van AppState gecanceld wordt.
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    }, () => mockClient);
    return calls;
  }

  String? reportedUri(List<MethodCall> calls) {
    for (final call in calls) {
      if (call.method == 'routeInformationUpdated') {
        return (call.arguments as Map)['uri'] as String?;
      }
    }
    return null;
  }

  testWidgets('een changelog-deep-link houdt het gevraagde adres in de adresbalk', (tester) async {
    final calls = await announcedRoutes(tester, '/changelog/demo');

    expect(reportedUri(calls), '/changelog/demo');
  });

  testWidgets('de root-URL blijft ongewijzigd aangemeld als /', (tester) async {
    final calls = await announcedRoutes(tester, '/');

    expect(reportedUri(calls), anyOf(isNull, '/'));
  });
}
