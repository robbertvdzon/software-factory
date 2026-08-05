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
  /// Lijst + samenvatting + detail van de maintenance-endpoints. [requestedUris] legt vast wat er is
  /// opgehaald, zodat zowel de detail-drilldown als de `kind`-queryparameter van het runs-scherm
  /// aantoonbaar op de URL landen.
  MockClient buildClient({
    List<Map<String, dynamic>> runs = const [],
    List<Map<String, dynamic>> summary = const [],
    List<String>? requestedUris,
    Map<String, dynamic>? detail,
    Map<String, List<Map<String, dynamic>>> runsByKind = const {},
    List<String> runningKinds = const [],
    List<String> errors = const [],
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
            jsonEncode({
              'runs': body,
              'errors': errors,
              'runningKinds': runningKinds,
              'summary': summary,
            }),
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
    String finishedAt = '2026-08-01T02:01:00Z',
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
        'finishedAt': finishedAt,
        'itemsDeleted': itemsDeleted,
        'itemsKept': itemsKept,
        'dryRun': dryRun,
        'failed': failed,
        'trigger': trigger,
      };

  /// Pompt het scherm en draait [body] binnen dezelfde `runWithClient`-zone: ook de calls ná het
  /// aantikken van een knop of een rij moeten de mock zien.
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

  /// Vijf actieblokken passen niet samen in het 800x600-testvenster: de onderste knoppen moeten
  /// eerst in beeld gescrold worden, anders mist `tap()` z'n doel.
  Future<void> tapKey(WidgetTester tester, String key) async {
    await tester.ensureVisible(find.byKey(Key(key)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(Key(key)));
  }

  testWidgets('het scherm toont een blok per opruimsoort met het resultaat van de laatste ronde',
      (tester) async {
    await pumpScreen(
      tester,
      buildClient(summary: [
        run(itemsDeleted: 3, itemsKept: 12, finishedAt: '2026-08-01T02:01:07Z'),
        run(id: 2, kind: 'agent-runs', project: null, itemsDeleted: 42, itemsKept: 7),
      ]),
    );

    expect(find.text('Opruimen'), findsWidgets);
    // Alle vijf de vaste soorten hebben een blok, ook de drie zonder gelogde ronde.
    for (final kind in cleanupKinds) {
      expect(find.text(kind), findsOneWidget);
      expect(find.byKey(Key('run-now-$kind')), findsOneWidget);
      expect(find.byKey(Key('view-runs-$kind')), findsOneWidget);
    }
    expect(find.text('verwijderd: 3'), findsOneWidget);
    expect(find.text('blijft staan: 12'), findsOneWidget);
    expect(find.text('duur: 1 m 7 s'), findsOneWidget);
    expect(find.text('verwijderd: 42'), findsOneWidget);
    expect(find.text('blijft staan: 7'), findsOneWidget);
    // De github-releases-regel noemt het project; de factory-brede soort niet.
    expect(find.text('SF'), findsOneWidget);
  });

  testWidgets('github-releases toont een regel per project met een gelogde ronde', (tester) async {
    await pumpScreen(
      tester,
      buildClient(summary: [
        run(id: 1, project: 'SF', itemsDeleted: 3),
        run(id: 2, project: 'personal-news-feed', itemsDeleted: 335, itemsKept: 15),
      ]),
    );

    expect(find.text('SF'), findsOneWidget);
    expect(find.text('personal-news-feed'), findsOneWidget);
    expect(find.text('verwijderd: 3'), findsOneWidget);
    expect(find.text('verwijderd: 335'), findsOneWidget);
    expect(find.text('blijft staan: 15'), findsOneWidget);
  });

  testWidgets('een soort zonder gelogde ronde toont een neutrale regel, geen foutmelding',
      (tester) async {
    await pumpScreen(tester, buildClient(summary: [run()]));

    // Vier soorten zonder samenvattingsregel; github-releases heeft er wel een.
    expect(find.text('laatste ronde: geen wijzigingen gelogd'), findsNWidgets(4));
    expect(find.text('fout'), findsNothing);
  });

  testWidgets('de duur is leesbaar, ook onder een seconde en zonder eindtijd', (tester) async {
    expect(formatCleanupDuration('2026-08-01T02:00:00Z', '2026-08-01T02:01:07Z'), '1 m 7 s');
    expect(formatCleanupDuration('2026-08-01T02:00:00Z', '2026-08-01T02:00:43Z'), '43 s');
    // Ronde onder één seconde: geen "0 s", want dan lijkt het alsof er niets gebeurd is.
    expect(formatCleanupDuration('2026-08-01T02:00:00.000Z', '2026-08-01T02:00:00.400Z'), '< 1 s');
    expect(formatCleanupDuration('2026-08-01T02:00:00Z', null), '-');
    expect(formatCleanupDuration(null, '2026-08-01T02:00:00Z'), '-');
  });

  testWidgets('een ronde zonder eindtijd toont een streepje als duur', (tester) async {
    await pumpScreen(
      tester,
      buildClient(summary: [
        {...run(), 'finishedAt': null},
      ]),
    );

    expect(find.text('duur: -'), findsOneWidget);
  });

  testWidgets('dry-run, handmatig en fout krijgen hun badge op de samenvattingsregel', (tester) async {
    await pumpScreen(
      tester,
      buildClient(summary: [
        run(dryRun: true, trigger: 'manual', failed: true),
        run(id: 2, kind: 'agent-runs', project: null),
      ]),
    );

    expect(find.text('dry-run'), findsOneWidget);
    expect(find.text('handmatig'), findsOneWidget);
    expect(find.text('fout'), findsOneWidget);
  });

  testWidgets('een geplande ronde krijgt geen handmatig-badge', (tester) async {
    await pumpScreen(tester, buildClient(summary: [run()]));

    expect(find.text('handmatig'), findsNothing);
  });

  testWidgets('foutbanners uit errors staan bovenaan het scherm', (tester) async {
    await pumpScreen(
      tester,
      buildClient(summary: [run()], errors: const ['database onbereikbaar']),
    );

    expect(find.text('database onbereikbaar'), findsOneWidget);
  });

  testWidgets('elk blok heeft een Nu draaien-knop; alleen "Alles draaien" staat bovenaan',
      (tester) async {
    await pumpScreen(tester, buildClient(summary: [run()]));

    expect(find.widgetWithText(TextButton, 'Nu draaien'), findsNWidgets(cleanupKinds.length));
    expect(find.widgetWithText(TextButton, 'Runs bekijken'), findsNWidgets(cleanupKinds.length));
    expect(find.widgetWithText(TextButton, 'Alles draaien'), findsOneWidget);
    // De vervallen soort-dropdown en de "Nu draaien:"-knoppenbalk zijn weg.
    expect(find.byType(DropdownButton<String>), findsNothing);
    expect(find.text('Nu draaien:'), findsNothing);
    expect(find.text('alle soorten'), findsNothing);
  });

  testWidgets('klikken doet de POST, zet de knoppen uit tijdens het verzoek en meldt het resultaat',
      (tester) async {
    final posted = <String>[];
    final uris = <String>[];
    final gate = Completer<void>();
    final client = buildClient(
      summary: [run()],
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
      await tapKey(tester, 'run-now-agent-events');
      await tester.pump();

      // Verzoek loopt: geen enkele "nu draaien"-knop is nog aan te klikken, dus een tweede snelle
      // klik kan geen tweede ronde starten.
      for (final kind in [...cleanupKinds, 'all']) {
        expect(tester.widget<TextButton>(find.byKey(Key('run-now-$kind'))).onPressed, isNull);
      }

      gate.complete();
      await tester.pumpAndSettle();
    });

    expect(posted, [jsonEncode({'kind': 'agent-events'})]);
    expect(uris, contains('/api/v1/maintenance/run'));
    expect(find.text('Opruimronde gestart voor agent-events.'), findsOneWidget);
    // Na afloop is de samenvatting opnieuw opgehaald, zodat de nieuwe ronde vanzelf verschijnt.
    expect(uris.where((uri) => uri == '/api/v1/maintenance/cleanups').length, greaterThan(1));
  });

  testWidgets('"Alles draaien" post de verzamelsoort en noemt gestarte en overgeslagen soorten',
      (tester) async {
    final posted = <String>[];
    final client = buildClient(
      summary: [run()],
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
      await tapKey(tester, 'run-now-all');
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
      summary: [run()],
      runNowResponse: const {'started': false, 'status': 'already_running', 'kinds': {}},
    );

    await pumpScreen(tester, client, () async {
      await tapKey(tester, 'run-now-workspaces');
      await tester.pumpAndSettle();
    });

    expect(find.text('De opruimronde voor workspaces draait al.'), findsOneWidget);
  });

  testWidgets('een uitgezette opruimer meldt dat de knop niets gestart heeft', (tester) async {
    final client = buildClient(
      summary: [run()],
      runNowResponse: const {'started': false, 'status': 'disabled', 'kinds': {}},
    );

    await pumpScreen(tester, client, () async {
      await tapKey(tester, 'run-now-agent-runs');
      await tester.pumpAndSettle();
    });

    expect(find.text('De opruimer agent-runs staat uit; er is niets gestart.'), findsOneWidget);
  });

  testWidgets('een soort die volgens de backend draait heeft een uitgeschakelde knop', (tester) async {
    final client = buildClient(summary: [run()], runningKinds: const ['agent-events']);

    await pumpScreen(tester, client, () async {
      expect(tester.widget<TextButton>(find.byKey(const Key('run-now-agent-events'))).onPressed, isNull);
      // De andere soorten blijven gewoon te starten, net als "Alles draaien".
      expect(tester.widget<TextButton>(find.byKey(const Key('run-now-workspaces'))).onPressed, isNotNull);
      expect(tester.widget<TextButton>(find.byKey(const Key('run-now-all'))).onPressed, isNotNull);
      // "Runs bekijken" is puur navigatie en blijft altijd beschikbaar.
      expect(tester.widget<TextButton>(find.byKey(const Key('view-runs-agent-events'))).onPressed, isNotNull);

      // Opruimen: het scherm pollt zolang er iets draait; disposen stopt die timer.
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    }, false);
  });

  testWidgets('het scherm pollt door zolang er een ronde draait en stopt daarna', (tester) async {
    final uris = <String>[];
    // Dezelfde lijst gaat mee in de mock: leegmaken bootst na dat de ronde klaar is.
    final running = <String>['agent-events'];
    final client = buildClient(summary: [run()], requestedUris: uris, runningKinds: running);

    await pumpScreen(tester, client, () async {
      final afterFirstLoad = uris.length;

      // Ronde draait nog: de poll-tick haalt de samenvatting opnieuw op.
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
        jsonEncode({
          'runs': <Map<String, dynamic>>[],
          'errors': <String>[],
          'runningKinds': <String>[],
          'summary': [run()],
        }),
        200,
      );
    });

    await pumpScreen(tester, client, () async {
      await tapKey(tester, 'run-now-workspaces');
      await tester.pumpAndSettle();
    });

    expect(find.textContaining('opruimen mislukt'), findsOneWidget);
  });

  testWidgets('"Runs bekijken" opent de historie van alléén die soort, nieuwste eerst',
      (tester) async {
    final uris = <String>[];
    final client = buildClient(
      summary: [run(id: 2, kind: 'agent-runs', project: null)],
      requestedUris: uris,
      runsByKind: {
        'agent-runs': [
          run(id: 5, kind: 'agent-runs', project: null, startedAt: '2026-08-02T02:00:00Z', itemsDeleted: 9,
              itemsKept: 0, trigger: 'manual'),
          run(id: 4, kind: 'agent-runs', project: null, startedAt: '2026-08-01T02:00:00Z', itemsDeleted: 1,
              itemsKept: 2, dryRun: true, failed: true),
        ],
      },
    );

    await pumpScreen(tester, client, () async {
      await tapKey(tester, 'view-runs-agent-runs');
      await tester.pumpAndSettle();
    });

    expect(uris, contains('/api/v1/maintenance/cleanups?kind=agent-runs'));
    expect(find.text('Rondes: agent-runs'), findsOneWidget);
    expect(find.byType(ListTile), findsNWidgets(2));
    expect(find.text('9 opgeruimd / 0 bewaard'), findsOneWidget);
    // Bij een dry-run is er niets echt verwijderd; de tekst mag dat niet claimen.
    expect(find.textContaining('zou worden opgeruimd'), findsOneWidget);
    expect(find.text('handmatig'), findsOneWidget);
    expect(find.text('dry-run'), findsOneWidget);
    expect(find.text('fout'), findsOneWidget);
  });

  testWidgets('een soort zonder rondes toont de lege staat op het runs-scherm', (tester) async {
    await pumpScreen(tester, buildClient(summary: [run()]), () async {
      await tapKey(tester, 'view-runs-workspaces');
      await tester.pumpAndSettle();
    });

    expect(find.text('Nog geen opruimrondes.'), findsOneWidget);
    expect(find.byType(ListTile), findsNothing);
  });

  testWidgets('een github-releases-ronde aantikken opent het detail met de verwijderde items',
      (tester) async {
    final uris = <String>[];
    final client = buildClient(
      summary: [run(id: 7)],
      requestedUris: uris,
      runsByKind: {
        'github-releases': [run(id: 7)],
      },
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
      await tapKey(tester, 'view-runs-github-releases');
      await tester.pumpAndSettle();
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

  testWidgets('een factory-brede ronde toont geen project en geen release-uitsplitsing',
      (tester) async {
    final client = buildClient(
      summary: [run(id: 8, kind: 'agent-runs', project: null)],
      runsByKind: {
        'agent-runs': [run(id: 8, kind: 'agent-runs', project: null)],
      },
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
      await tapKey(tester, 'view-runs-agent-runs');
      await tester.pumpAndSettle();
      await tester.tap(find.byType(ListTile).first);
      await tester.pumpAndSettle();
    });

    expect(find.textContaining('Opgeruimd: 42, 0 bewaard'), findsOneWidget);
    expect(find.textContaining('Project:'), findsNothing);
    expect(find.textContaining('Releases:'), findsNothing);
    expect(find.text('Verwijderde releases'), findsNothing);
  });

  testWidgets('de blokken blijven op een smal scherm binnen de schermbreedte', (tester) async {
    tester.view.physicalSize = const Size(400, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await pumpScreen(
      tester,
      buildClient(summary: [run(project: 'personal-news-feed-backend', itemsDeleted: 335)]),
    );

    // Een RenderFlex-overflow zou als testfout naar boven komen; de assertie hier bewijst dat de
    // inhoud daadwerkelijk gerenderd is op 400px breed.
    expect(tester.takeException(), isNull);
    expect(find.text('verwijderd: 335'), findsOneWidget);
    expect(find.text('personal-news-feed-backend'), findsOneWidget);
  });
}
