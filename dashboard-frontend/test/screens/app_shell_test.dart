import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_shell.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/browser_path.dart';
import 'package:softwarefactory_dashboard/text_scale_preference.dart';

/// De navigatie zelf staat hier centraal, niet de schermen erachter: elk verzoek faalt bewust,
/// zodat het geselecteerde scherm alleen zijn foutmelding toont en de test niet van API-vormen
/// afhangt.
void main() {
  Future<void> pumpShell(WidgetTester tester, {required Size size, String? initialSection}) async {
    SharedPreferences.setMockInitialValues({});
    BrowserPath.reset();
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final state = AppState(ApiClient());
    final client = MockClient((request) async => http.Response('Not found', 404));
    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: AppShell(state: state, textScale: TextScalePreference(), initialSection: initialSection)),
      );
      await tester.pumpAndSettle();
    }, () => client);
  }

  testWidgets('bottom-nav toont Stories, My actions, Agents en Meer — geen Dashboard', (tester) async {
    await pumpShell(tester, size: const Size(420, 900));

    final bar = tester.widget<NavigationBar>(find.byType(NavigationBar));
    final labels = bar.destinations.cast<NavigationDestination>().map((d) => d.label).toList();
    expect(labels, ['Stories', 'My actions', 'Agents', 'Meer']);
  });

  testWidgets('NavigationRail toont alle secties zonder Dashboard', (tester) async {
    await pumpShell(tester, size: const Size(1200, 900));

    final rail = tester.widget<NavigationRail>(find.byType(NavigationRail));
    final labels = rail.destinations.map((d) => (d.label as Text).data).toList();
    expect(labels, [
      'Stories',
      'My actions',
      'Agents',
      'Projects',
      'Builds',
      'App-updates',
      'Audits',
      'Opruimen',
      'Settings',
    ]);
  });

  testWidgets('"Meer" opent de secundaire secties en navigeert naar Audits', (tester) async {
    await pumpShell(tester, size: const Size(420, 900));

    await tester.tap(find.text('Meer'));
    await tester.pumpAndSettle();
    expect(find.text('Audits'), findsOneWidget);

    await tester.tap(find.text('Audits'));
    await tester.pumpAndSettle();
    expect(find.text('Audits'), findsWidgets);
  });

  /// Vangt wat de app aan de engine meldt (BrowserPath → `routeInformationUpdated`).
  List<MethodCall> captureNavigation(WidgetTester tester) {
    final calls = <MethodCall>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.navigation, (call) async {
      calls.add(call);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.navigation, null));
    return calls;
  }

  List<String> reportedPaths(List<MethodCall> calls) => [
    for (final call in calls)
      if (call.method == 'routeInformationUpdated') (call.arguments as Map)['uri'] as String,
  ];

  testWidgets('een sectiekeuze zet het pad van die sectie in de adresbalk', (tester) async {
    final calls = captureNavigation(tester);
    await pumpShell(tester, size: const Size(1200, 900));
    expect(BrowserPath.current, '/stories');

    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();

    expect(BrowserPath.current, '/settings');
    expect(reportedPaths(calls), contains('/settings'));
  });

  testWidgets('een sectie uit het adres opent direct die sectie', (tester) async {
    await pumpShell(tester, size: const Size(1200, 900), initialSection: 'audits');

    final rail = tester.widget<NavigationRail>(find.byType(NavigationRail));
    expect(rail.selectedIndex, 6);
    expect(BrowserPath.current, '/audits');
  });

  testWidgets('een onbekende sectie valt terug op Stories', (tester) async {
    await pumpShell(tester, size: const Size(1200, 900), initialSection: 'bestaat-niet');

    final rail = tester.widget<NavigationRail>(find.byType(NavigationRail));
    expect(rail.selectedIndex, 0);
    expect(BrowserPath.current, '/stories');
  });
}
