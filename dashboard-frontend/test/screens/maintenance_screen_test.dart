import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/maintenance_screen.dart';

void main() {
  /// Lijst + detail van de maintenance-endpoints. [requestedUris] legt vast wat er is opgehaald,
  /// zodat zowel de detail-drilldown als het `kind`-filter aantoonbaar op de URL landen.
  MockClient buildClient({
    required List<Map<String, dynamic>> runs,
    List<String>? requestedUris,
    Map<String, dynamic>? detail,
    Map<String, List<Map<String, dynamic>>> runsByKind = const {},
  }) =>
      MockClient((request) async {
        requestedUris?.add('${request.url.path}${request.url.hasQuery ? '?${request.url.query}' : ''}');
        if (request.url.path.endsWith('/api/v1/maintenance/cleanups')) {
          final kind = request.url.queryParameters['kind'];
          final body = kind == null ? runs : (runsByKind[kind] ?? const <Map<String, dynamic>>[]);
          return http.Response(jsonEncode({'runs': body, 'errors': <String>[]}), 200);
        }
        if (request.url.path.contains('/api/v1/maintenance/cleanups/')) {
          if (detail == null) return http.Response('Not found', 404);
          return http.Response(jsonEncode(detail), 200);
        }
        return http.Response('Not found', 404);
      });

  Map<String, dynamic> run({
    int id = 1,
    String kind = 'github-releases',
    String? project = 'SF',
    String startedAt = '2026-08-01T02:00:00Z',
    int itemsDeleted = 3,
    int itemsKept = 12,
    bool dryRun = false,
    bool failed = false,
  }) =>
      {
        'id': id,
        'kind': kind,
        'project': project,
        'startedAt': startedAt,
        'finishedAt': '2026-08-01T02:01:00Z',
        'itemsDeleted': itemsDeleted,
        'itemsKept': itemsKept,
        'dryRun': dryRun,
        'failed': failed,
      };

  /// Pompt het scherm en draait [body] binnen dezelfde `runWithClient`-zone: ook de calls ná het
  /// aantikken van een rij of het wisselen van het filter moeten de mock zien.
  Future<void> pumpScreen(
    WidgetTester tester,
    MockClient client, [
    Future<void> Function()? body,
  ]) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());
    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: MaintenanceScreen(state: state)));
      await tester.pumpAndSettle();
      if (body != null) await body();
    }, () => client);
  }

  testWidgets('lijst toont per ronde datum/tijd, soort, project en de aantallen', (tester) async {
    await pumpScreen(
      tester,
      buildClient(runs: [
        run(),
        run(id: 2, kind: 'agent-runs', project: null, itemsDeleted: 0, itemsKept: 0),
      ]),
    );

    expect(find.text('Opruimen'), findsWidgets);
    expect(find.textContaining('SF — 3 opgeruimd / 12 bewaard'), findsOneWidget);
    // Factory-brede ronde: geen project vóór het streepje.
    expect(find.text('0 opgeruimd / 0 bewaard'), findsOneWidget);
    expect(find.text('github-releases'), findsWidgets);
    // 'agent-runs' staat zowel op de badge als in het filter-dropdown.
    expect(find.text('agent-runs'), findsWidgets);
    // Ook een ronde zonder verwijderingen staat in de lijst: bewijs dat de opruimer gedraaid heeft.
    expect(find.byType(ListTile), findsNWidgets(2));
  });

  testWidgets('dry-run en mislukte ronde krijgen een badge', (tester) async {
    await pumpScreen(
      tester,
      buildClient(runs: [run(dryRun: true), run(id: 2, failed: true)]),
    );

    expect(find.text('dry-run'), findsOneWidget);
    expect(find.text('fout'), findsOneWidget);
    // Bij een dry-run is er niets echt verwijderd; de tekst mag dat niet claimen.
    expect(find.textContaining('zou worden opgeruimd'), findsOneWidget);
  });

  testWidgets('zonder rondes toont het scherm de lege staat', (tester) async {
    await pumpScreen(tester, buildClient(runs: []));

    expect(find.text('Nog geen opruimrondes.'), findsOneWidget);
    expect(find.byType(ListTile), findsNothing);
  });

  testWidgets('het soort-filter staat op "alle soorten" en haalt zonder kind-param op', (tester) async {
    final uris = <String>[];
    await pumpScreen(tester, buildClient(runs: [run()], requestedUris: uris));

    expect(find.text('alle soorten'), findsOneWidget);
    expect(uris, contains('/api/v1/maintenance/cleanups'));
    expect(uris.where((uri) => uri.contains('kind=')), isEmpty);
  });

  testWidgets('een soort kiezen filtert de lijst via de kind-queryparameter', (tester) async {
    final uris = <String>[];
    final client = buildClient(
      runs: [run(), run(id: 2, kind: 'agent-runs', project: null, itemsDeleted: 9, itemsKept: 0)],
      requestedUris: uris,
      runsByKind: {
        'agent-runs': [run(id: 2, kind: 'agent-runs', project: null, itemsDeleted: 9, itemsKept: 0)],
      },
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.byType(DropdownButton<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('agent-runs').last);
      await tester.pumpAndSettle();
    });

    expect(uris, contains('/api/v1/maintenance/cleanups?kind=agent-runs'));
    // Alleen de agent-runs-ronde is overgebleven — bewijst dat de nieuwe respons getoond wordt.
    expect(find.byType(ListTile), findsOneWidget);
    expect(find.text('9 opgeruimd / 0 bewaard'), findsOneWidget);
  });

  testWidgets('een github-releases-ronde aantikken opent het detail met de verwijderde items', (tester) async {
    final uris = <String>[];
    final client = buildClient(
      runs: [run(id: 7)],
      requestedUris: uris,
      detail: {
        'id': 7,
        'kind': 'github-releases',
        'project': 'SF',
        'startedAt': '2026-08-01T02:00:00Z',
        'finishedAt': '2026-08-01T02:01:00Z',
        'itemsDeleted': 3,
        'itemsKept': 14,
        'dryRun': false,
        'error': 'GitHub gaf 500',
        'releasesDeleted': 2,
        'releasesKept': 5,
        'packagesDeleted': 1,
        'packagesKept': 9,
        'deletedReleaseTags': ['v1.0.0', 'v1.0.1'],
        'deletedPackageVersions': ['sha-abc123'],
      },
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.byType(ListTile).first);
      await tester.pumpAndSettle();
    });

    expect(uris, contains('/api/v1/maintenance/cleanups/7'));
    expect(find.text('Opruimronde'), findsOneWidget);
    expect(find.text('• v1.0.0'), findsOneWidget);
    expect(find.text('• v1.0.1'), findsOneWidget);
    expect(find.text('• sha-abc123'), findsOneWidget);
    expect(find.textContaining('Opgeruimd: 3, 14 bewaard'), findsOneWidget);
    expect(find.textContaining('Releases: 2 opgeruimd, 5 bewaard'), findsOneWidget);
    expect(find.textContaining('Package-versions: 1 opgeruimd, 9 bewaard'), findsOneWidget);
    expect(find.text('GitHub gaf 500'), findsOneWidget);
  });

  testWidgets('een factory-brede ronde toont geen project en geen release-uitsplitsing', (tester) async {
    final client = buildClient(
      runs: [run(id: 8, kind: 'agent-runs', project: null)],
      detail: {
        'id': 8,
        'kind': 'agent-runs',
        'project': null,
        'startedAt': '2026-08-01T02:00:00Z',
        'finishedAt': '2026-08-01T02:01:00Z',
        'itemsDeleted': 42,
        'itemsKept': 0,
        'dryRun': false,
        'error': null,
        'releasesDeleted': 0,
        'releasesKept': 0,
        'packagesDeleted': 0,
        'packagesKept': 0,
        'deletedReleaseTags': <String>[],
        'deletedPackageVersions': <String>[],
      },
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.byType(ListTile).first);
      await tester.pumpAndSettle();
    });

    expect(find.textContaining('Opgeruimd: 42, 0 bewaard'), findsOneWidget);
    expect(find.textContaining('Project:'), findsNothing);
    expect(find.textContaining('Releases:'), findsNothing);
    expect(find.text('Verwijderde releases'), findsNothing);
  });
}
