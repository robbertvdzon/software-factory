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
  /// Lijst + detail van de maintenance-endpoints. [requestedPaths] legt vast welke paden zijn
  /// opgehaald, zodat de detail-drilldown aantoonbaar het `{id}`-endpoint gebruikt.
  MockClient buildClient({
    required List<Map<String, dynamic>> runs,
    List<String>? requestedPaths,
    Map<String, dynamic>? detail,
  }) =>
      MockClient((request) async {
        requestedPaths?.add(request.url.path);
        if (request.url.path.endsWith('/api/v1/maintenance/cleanups')) {
          return http.Response(jsonEncode({'runs': runs, 'errors': <String>[]}), 200);
        }
        if (request.url.path.contains('/api/v1/maintenance/cleanups/')) {
          if (detail == null) return http.Response('Not found', 404);
          return http.Response(jsonEncode(detail), 200);
        }
        return http.Response('Not found', 404);
      });

  Map<String, dynamic> run({
    int id = 1,
    String project = 'SF',
    String startedAt = '2026-08-01T02:00:00Z',
    int releasesDeleted = 3,
    int packagesDeleted = 12,
    bool dryRun = false,
    bool failed = false,
  }) =>
      {
        'id': id,
        'project': project,
        'startedAt': startedAt,
        'finishedAt': '2026-08-01T02:01:00Z',
        'releasesDeleted': releasesDeleted,
        'packagesDeleted': packagesDeleted,
        'dryRun': dryRun,
        'failed': failed,
      };

  /// Pompt het scherm en draait [body] binnen dezelfde `runWithClient`-zone: ook de detail-call
  /// ná het aantikken van een rij moet de mock zien.
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

  testWidgets('lijst toont per ronde datum/tijd, project en de aantallen', (tester) async {
    await pumpScreen(tester, buildClient(runs: [run(), run(id: 2, project: 'OTHER', releasesDeleted: 0, packagesDeleted: 0)]));

    expect(find.text('Maintenance'), findsWidgets);
    expect(find.textContaining('SF — 3 releases / 12 package-versions opgeruimd'), findsOneWidget);
    expect(find.textContaining('OTHER — 0 releases / 0 package-versions opgeruimd'), findsOneWidget);
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

  testWidgets('een ronde aantikken opent de detailpagina met de verwijderde items', (tester) async {
    final paths = <String>[];
    final client = buildClient(
      runs: [run(id: 7)],
      requestedPaths: paths,
      detail: {
        'id': 7,
        'project': 'SF',
        'startedAt': '2026-08-01T02:00:00Z',
        'finishedAt': '2026-08-01T02:01:00Z',
        'releasesDeleted': 2,
        'releasesKept': 5,
        'packagesDeleted': 1,
        'packagesKept': 9,
        'dryRun': false,
        'error': 'GitHub gaf 500',
        'deletedReleaseTags': ['v1.0.0', 'v1.0.1'],
        'deletedPackageVersions': ['sha-abc123'],
      },
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.byType(ListTile).first);
      await tester.pumpAndSettle();
    });

    expect(paths, contains('/api/v1/maintenance/cleanups/7'));
    expect(find.text('Opruimronde'), findsOneWidget);
    expect(find.text('• v1.0.0'), findsOneWidget);
    expect(find.text('• v1.0.1'), findsOneWidget);
    expect(find.text('• sha-abc123'), findsOneWidget);
    expect(find.textContaining('Releases: 2 opgeruimd, 5 bewaard'), findsOneWidget);
    expect(find.textContaining('Package-versions: 1 opgeruimd, 9 bewaard'), findsOneWidget);
    expect(find.text('GitHub gaf 500'), findsOneWidget);
  });
}
