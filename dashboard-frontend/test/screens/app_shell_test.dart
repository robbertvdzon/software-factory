import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_shell.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/text_scale_preference.dart';

/// De navigatie zelf staat hier centraal, niet de schermen erachter: elk verzoek faalt bewust,
/// zodat het geselecteerde scherm alleen zijn foutmelding toont en de test niet van API-vormen
/// afhangt.
void main() {
  Future<void> pumpShell(WidgetTester tester, {required Size size}) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final state = AppState(ApiClient());
    final client = MockClient((request) async => http.Response('Not found', 404));
    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: AppShell(state: state, textScale: TextScalePreference())),
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
}
