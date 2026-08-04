import 'dart:async';
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
    List<String> runningKinds = const [],
    Map<String, dynamic> runNowResponse = const {'started': true, 'status': 'started'},
    List<String>? postedBodies,
    Completer<void>? runNowGate,
  }) =>
      MockClient((request) async {
        requestedUris?.add('${request.url.path}${request.url.hasQuery ? '?${request.url.query}' : ''}');
        if (request.url.path.endsWith('/api/v1/maintenance/run')) {
          postedBodies?.add(request.body);
          // Met een gate blijft de POST hangen tot de test 'm vrijgeeft — zo is te zien dat de
          // knoppen uit staan zolang het verzoek loopt.
          if (runNowGate != null) await runNowGate.future;
          return http.Response(jsonEncode(runNowResponse), 200);
        }
        if (request.url.path.endsWith('/api/v1/maintenance/cleanups')) {
          final kind = request.url.queryParameters['kind'];
          final body = kind == null ? runs : (runsByKind[kind] ?? const <Map<String, dynamic>>[]);
          return http.Response(
            jsonEncode({'runs': body, 'errors': <String>[], 'runningKinds': runningKinds}),
            200,
          );
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
    String trigger = 'scheduled',
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
        'trigger': trigger,
      };

  /// Pompt het scherm en draait [body] binnen dezelfde `runWithClient`-zone: ook de calls ná het
  /// aantikken van een rij of het wisselen van het filter moeten de mock zien.
  ///
  /// [settle] uit als de backend een draaiende ronde meldt: het scherm pollt dan door, en
  /// `pumpAndSettle` zou op die periodieke timer blijven wachten.
  Future<void> pumpScreen(
    WidgetTester tester,
    MockClient client, [
    Future<void> Function()? body,
    bool settle = true,
  ]) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());
    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: MaintenanceScreen(state: state)));
      if (settle) {
        await tester.pumpAndSettle();
      } else {
        await tester.pump();
        await tester.pump();
      }
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

  testWidgets('de knoppenrij heeft per soort een knop plus "Alles draaien"', (tester) async {
    await pumpScreen(tester, buildClient(runs: [run()]));

    expect(find.text('Nu draaien:'), findsOneWidget);
    for (final kind in cleanupKinds) {
      expect(find.widgetWithText(TextButton, kind), findsOneWidget);
    }
    expect(find.widgetWithText(TextButton, 'Alles draaien'), findsOneWidget);
  });

  testWidgets('klikken doet de POST, zet de knoppen uit tijdens het verzoek en meldt het resultaat',
      (tester) async {
    final posted = <String>[];
    final uris = <String>[];
    final gate = Completer<void>();
    final client = buildClient(
      runs: [run()],
      requestedUris: uris,
      postedBodies: posted,
      runNowGate: gate,
      runNowResponse: const {
        'started': true,
        'status': 'started',
        'kinds': {'agent-events': 'started'},
      },
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.widgetWithText(TextButton, 'agent-events'));
      await tester.pump();

      // Verzoek loopt: geen enkele knop is nog aan te klikken, dus een tweede snelle klik kan geen
      // tweede ronde starten.
      for (final button in tester.widgetList<TextButton>(find.byType(TextButton))) {
        expect(button.onPressed, isNull);
      }

      gate.complete();
      await tester.pumpAndSettle();
    });

    expect(posted, [jsonEncode({'kind': 'agent-events'})]);
    expect(uris, contains('/api/v1/maintenance/run'));
    expect(find.text('Opruimronde gestart voor agent-events.'), findsOneWidget);
    // Na afloop is de lijst opnieuw opgehaald, zodat de nieuwe ronde vanzelf verschijnt.
    expect(uris.where((uri) => uri == '/api/v1/maintenance/cleanups').length, greaterThan(1));
  });

  testWidgets('"Alles draaien" post de verzamelsoort en noemt gestarte en overgeslagen soorten',
      (tester) async {
    final posted = <String>[];
    final client = buildClient(
      runs: [run()],
      postedBodies: posted,
      runNowResponse: const {
        'started': true,
        'status': 'started',
        'kinds': {
          'github-releases': 'started',
          'agent-events': 'already_running',
          'agent-runs': 'disabled',
        },
      },
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.widgetWithText(TextButton, 'Alles draaien'));
      await tester.pumpAndSettle();
    });

    expect(posted, [jsonEncode({'kind': 'all'})]);
    expect(
      find.text('Gestart: github-releases. Overgeslagen: agent-events (draait al), agent-runs (uit).'),
      findsOneWidget,
    );
  });

  testWidgets('een tweede klik op een draaiende soort meldt "draait al"', (tester) async {
    final client = buildClient(
      runs: [run()],
      runNowResponse: const {'started': false, 'status': 'already_running', 'kinds': {}},
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.widgetWithText(TextButton, 'workspaces'));
      await tester.pumpAndSettle();
    });

    expect(find.text('De opruimronde voor workspaces draait al.'), findsOneWidget);
  });

  testWidgets('een uitgezette opruimer meldt dat de knop niets gestart heeft', (tester) async {
    final client = buildClient(
      runs: [run()],
      runNowResponse: const {'started': false, 'status': 'disabled', 'kinds': {}},
    );

    await pumpScreen(tester, client, () async {
      await tester.tap(find.widgetWithText(TextButton, 'agent-runs'));
      await tester.pumpAndSettle();
    });

    expect(find.text('De opruimer agent-runs staat uit; er is niets gestart.'), findsOneWidget);
  });

  testWidgets('een soort die volgens de backend draait heeft een uitgeschakelde knop', (tester) async {
    final client = buildClient(runs: [run()], runningKinds: const ['agent-events']);

    await pumpScreen(tester, client, () async {
      final running = tester.widget<TextButton>(find.widgetWithText(TextButton, 'agent-events'));
      expect(running.onPressed, isNull);
      // De andere soorten blijven gewoon te starten, net als "Alles draaien".
      final free = tester.widget<TextButton>(find.widgetWithText(TextButton, 'workspaces'));
      expect(free.onPressed, isNotNull);
      final all = tester.widget<TextButton>(find.widgetWithText(TextButton, 'Alles draaien'));
      expect(all.onPressed, isNotNull);

      // Opruimen: het scherm pollt zolang er iets draait; disposen stopt die timer.
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    }, false);
  });

  testWidgets('het scherm pollt door zolang er een ronde draait en stopt daarna', (tester) async {
    final uris = <String>[];
    // Dezelfde lijst gaat mee in de mock: leegmaken bootst na dat de ronde klaar is.
    final running = <String>['agent-events'];
    final client = buildClient(runs: [run()], requestedUris: uris, runningKinds: running);

    await pumpScreen(tester, client, () async {
      final afterFirstLoad = uris.length;

      // Ronde draait nog: de poll-tick haalt de lijst opnieuw op.
      await tester.pump(const Duration(seconds: 4));
      await tester.pump();
      expect(uris.length, greaterThan(afterFirstLoad));

      // Ronde klaar: de volgende tick ziet een lege runningKinds en zet het pollen stil.
      running.clear();
      await tester.pump(const Duration(seconds: 4));
      await tester.pump();
      final afterStop = uris.length;
      await tester.pump(const Duration(seconds: 30));
      expect(uris.length, afterStop);
    }, false);
    // Blijft er toch een timer lopen, dan faalt deze test op "A Timer is still pending".
  });

  testWidgets('een mislukte start toont de foutmelding uit de backend', (tester) async {
    final client = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/maintenance/run')) {
        return http.Response('{"error":"opruimen mislukt"}', 500);
      }
      return http.Response(
        jsonEncode({'runs': [run()], 'errors': <String>[], 'runningKinds': <String>[]}),
        200,
      );
    });

    await pumpScreen(tester, client, () async {
      await tester.tap(find.widgetWithText(TextButton, 'workspaces'));
      await tester.pumpAndSettle();
    });

    expect(find.textContaining('opruimen mislukt'), findsOneWidget);
  });

  testWidgets('een handmatige ronde krijgt de handmatig-badge in de lijst en op het detail',
      (tester) async {
    final client = buildClient(
      runs: [run(id: 9, trigger: 'manual', failed: true)],
      detail: {
        'id': 9,
        'kind': 'github-releases',
        'project': 'SF',
        'startedAt': '2026-08-01T02:00:00Z',
        'finishedAt': '2026-08-01T02:01:00Z',
        'itemsDeleted': 0,
        'itemsKept': 0,
        'dryRun': false,
        'error': 'GitHub gaf 500',
        'releasesDeleted': 0,
        'releasesKept': 0,
        'packagesDeleted': 0,
        'packagesKept': 0,
        'deletedReleaseTags': <String>[],
        'deletedPackageVersions': <String>[],
        'trigger': 'manual',
      },
    );

    await pumpScreen(tester, client, () async {
      expect(find.text('handmatig'), findsOneWidget);
      expect(find.text('fout'), findsOneWidget);

      await tester.tap(find.byType(ListTile).first);
      await tester.pumpAndSettle();
    });

    // Ook op de detailpagina, naast de foutmelding van de mislukte ronde.
    expect(find.text('handmatig'), findsOneWidget);
    expect(find.text('GitHub gaf 500'), findsOneWidget);
  });

  testWidgets('een geplande ronde krijgt geen handmatig-badge', (tester) async {
    await pumpScreen(tester, buildClient(runs: [run()]));

    expect(find.text('handmatig'), findsNothing);
  });
}
