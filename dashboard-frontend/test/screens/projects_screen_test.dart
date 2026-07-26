import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/overview_screens.dart';

void main() {
  Future<void> pumpProjects(
    WidgetTester tester,
    Map<String, dynamic> project, {
    List<Map<String, dynamic>> buildRuns = const [],
    List<Map<String, dynamic>> downloads = const [],
    Map<String, dynamic>? branchTimeline,
    void Function(Uri url)? onBranchTimelineRequested,
    VoidCallback? onProjectsRequested,
    // Vervolgacties (bv. een uitklap-tap die zelf weer een gemockte HTTP-call doet) moeten binnen
    // dezelfde runWithClient-zone lopen als de initiële pump — anders krijgt de test-runtime's eigen
    // (niet-gemockte) HttpClient de aanroep en faalt die met HTTP 400, zie Flutter's eigen waarschuwing.
    Future<void> Function(WidgetTester tester, AppState state)? after,
  }) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/branch-timeline')) {
        onBranchTimelineRequested?.call(request.url);
        return http.Response(
          jsonEncode(branchTimeline ?? {'rows': <Map<String, dynamic>>[], 'errors': <String>[]}),
          200,
        );
      }
      if (request.url.path.endsWith('/api/v1/projects')) {
        onProjectsRequested?.call();
        return http.Response(jsonEncode({'projects': [project], 'errors': <String>[]}), 200);
      }
      if (request.url.path.endsWith('/api/v1/builds')) {
        return http.Response(
          jsonEncode({
            'repos': buildRuns.isEmpty
                ? <Map<String, dynamic>>[]
                : [
                    {'projectKey': project['name'], 'repository': 'robbert/x', 'runs': buildRuns},
                  ],
            'errors': <String>[],
          }),
          200,
        );
      }
      if (request.url.path.endsWith('/api/v1/downloads')) {
        return http.Response(jsonEncode({'downloads': downloads, 'errors': <String>[]}), 200);
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: ProjectsScreen(state: state)));
      await tester.pumpAndSettle();
      await after?.call(tester, state);
    }, () => mockClient);
  }

  /// Een project staat standaard ingeklapt tot alleen de naam (accordion — "gewoon als knop");
  /// pas na deze tap zie je chips, build-/deploystatus, live-componenten en de branch-timeline.
  Future<void> expandProject(WidgetTester tester, String projectName) async {
    await tester.tap(find.text(projectName));
    await tester.pumpAndSettle();
  }

  testWidgets('Project staat standaard ingeklapt tot alleen de naam', (tester) async {
    await pumpProjects(tester, {
      'name': 'SF',
      'repoUrl': 'https://github.com/robbert/softwarefactory',
      'storiesTodo': 3,
      'storiesInProgress': 0,
      'storiesDone': 0,
      'totalCostUsd': 0.0,
      'activeAgentCount': 0,
      'prdVersion': null,
      'hasDeployConfig': false,
      'buildStatus': {
        'lastMainBuildAt': null,
        'mainBuildActive': false,
        'prBuildActive': false,
        'syncStatus': 'UNAVAILABLE',
      },
    });

    expect(find.text('SF'), findsOneWidget);
    expect(find.textContaining('todo: 3'), findsNothing);
    expect(find.text('https://github.com/robbert/softwarefactory'), findsNothing);

    await expandProject(tester, 'SF');

    expect(find.textContaining('todo: 3'), findsOneWidget);
    expect(find.text('https://github.com/robbert/softwarefactory'), findsOneWidget);
  });

  testWidgets('Projects-scherm toont in-sync badge en actieve main-build', (tester) async {
    await pumpProjects(tester, {
      'name': 'SF',
      'repoUrl': 'https://github.com/robbert/softwarefactory',
      'storiesTodo': 0,
      'storiesInProgress': 0,
      'storiesDone': 0,
      'totalCostUsd': 0.0,
      'activeAgentCount': 0,
      'prdVersion': {'commitShort': 'deadbee', 'commitDate': '2026-07-08', 'branch': 'main'},
      'hasDeployConfig': true,
      'buildStatus': {
        'lastMainBuildAt': '2026-07-08T10:05:00Z',
        'mainBuildActive': true,
        'prBuildActive': false,
        'syncStatus': 'IN_SYNC',
      },
    });
    await expandProject(tester, 'SF');

    expect(find.text('In sync met main'), findsOneWidget);
    expect(find.text('Main-build actief'), findsOneWidget);
    expect(find.text('PR-build actief'), findsNothing);
  });

  testWidgets('Projects-scherm toont out-of-sync badge en geen actieve build', (tester) async {
    await pumpProjects(tester, {
      'name': 'SF',
      'repoUrl': 'https://github.com/robbert/softwarefactory',
      'storiesTodo': 0,
      'storiesInProgress': 0,
      'storiesDone': 0,
      'totalCostUsd': 0.0,
      'activeAgentCount': 0,
      'prdVersion': {'commitShort': 'cafebab', 'commitDate': '2026-07-08', 'branch': 'main'},
      'hasDeployConfig': true,
      'buildStatus': {
        'lastMainBuildAt': '2026-07-08T10:05:00Z',
        'mainBuildActive': false,
        'prBuildActive': false,
        'syncStatus': 'OUT_OF_SYNC',
      },
    });
    await expandProject(tester, 'SF');

    expect(find.text('Loopt achter op main'), findsOneWidget);
    expect(find.text('Geen actieve build'), findsOneWidget);
  });

  testWidgets('Projects-scherm zonder deploy-configuratie toont "geen productieversie beschikbaar"', (tester) async {
    await pumpProjects(tester, {
      'name': 'PNF',
      'repoUrl': 'https://github.com/robbert/personal-feed',
      'storiesTodo': 0,
      'storiesInProgress': 0,
      'storiesDone': 0,
      'totalCostUsd': 0.0,
      'activeAgentCount': 0,
      'prdVersion': null,
      'hasDeployConfig': false,
      'buildStatus': {
        'lastMainBuildAt': null,
        'mainBuildActive': false,
        'prBuildActive': false,
        'syncStatus': 'UNAVAILABLE',
      },
    });
    await expandProject(tester, 'PNF');

    expect(find.text('Geen productieversie beschikbaar'), findsOneWidget);
    expect(find.text('Laatste main-build: onbekend'), findsOneWidget);
  });

  testWidgets('Projects-scherm toont live-componenten met sha, uptime en sync-badge', (tester) async {
    await pumpProjects(tester, {
      'name': 'SF',
      'repoUrl': 'https://github.com/robbert/softwarefactory',
      'storiesTodo': 0,
      'storiesInProgress': 0,
      'storiesDone': 0,
      'totalCostUsd': 0.0,
      'activeAgentCount': 0,
      'prdVersion': null,
      'hasDeployConfig': false,
      'buildStatus': {
        'lastMainBuildAt': null,
        'mainBuildActive': false,
        'prBuildActive': false,
        'syncStatus': 'UNAVAILABLE',
      },
      'liveComponents': [
        {
          'label': 'backend',
          'shortSha': '66d1019',
          'podStartedAt': '2026-07-09T10:03:58Z',
          'uptimeSeconds': 7200,
          'syncStatus': 'IN_SYNC',
        },
      ],
    });
    await expandProject(tester, 'SF');

    expect(find.text('backend'), findsOneWidget);
    expect(find.text('66d1019'), findsOneWidget);
    expect(find.text('sinds 2u00m'), findsOneWidget);
    expect(find.text('In sync met main'), findsOneWidget);
  });

  testWidgets(
    'Toont apk-sync-status meteen zodra het project-paneel open is, zonder "Builds en downloads" uit te klappen',
    (tester) async {
      await pumpProjects(
        tester,
        {
          'name': 'robberts-assistent',
          'repoUrl': 'https://github.com/robbert/robberts-assistent',
          'storiesTodo': 0,
          'storiesInProgress': 0,
          'storiesDone': 0,
          'totalCostUsd': 0.0,
          'activeAgentCount': 0,
          'prdVersion': null,
          'hasDeployConfig': false,
          'buildStatus': {
            'lastMainBuildAt': null,
            'mainBuildActive': false,
            'prBuildActive': false,
            'syncStatus': 'UNAVAILABLE',
          },
        },
        downloads: [
          {
            'projectKey': 'robberts-assistent',
            'name': 'app-release.apk',
            'size': 43500000,
            'createdAt': '2026-07-11T23:18:00Z',
            'downloadUrl': 'https://example.com/wind.apk',
            'releaseTag': 'wind-latest',
            'commitSha': 'deadbeefcafebabe',
            'syncStatus': 'IN_SYNC',
          },
        ],
      );
      await expandProject(tester, 'robberts-assistent');

      // "Builds en downloads" is nog dicht — toch al zichtbaar dat de Wind-apk in sync is.
      expect(find.text('Download'), findsNothing);
      expect(find.text('Wind'), findsOneWidget);
      expect(find.text('deadbee'), findsOneWidget);
      expect(find.text('In sync met main'), findsOneWidget);
    },
  );

  testWidgets('Uitklappen van "Builds en downloads" toont workflow-run en apk-download', (tester) async {
    await pumpProjects(
      tester,
      {
        'name': 'SF',
        'repoUrl': 'https://github.com/robbert/softwarefactory',
        'storiesTodo': 0,
        'storiesInProgress': 0,
        'storiesDone': 0,
        'totalCostUsd': 0.0,
        'activeAgentCount': 0,
        'prdVersion': null,
        'hasDeployConfig': false,
        'buildStatus': {
          'lastMainBuildAt': null,
          'mainBuildActive': false,
          'prBuildActive': false,
          'syncStatus': 'UNAVAILABLE',
        },
      },
      buildRuns: [
        {
          'workflowName': 'Build',
          'status': 'completed',
          'conclusion': 'success',
          'branch': 'main',
          'event': 'push',
          'durationSeconds': 88,
          'htmlUrl': 'https://github.com/robbert/softwarefactory/actions/runs/1',
        },
      ],
      downloads: [
        {
          'projectKey': 'SF',
          'name': 'software-factory-dashboard.apk',
          'size': 51600000,
          'createdAt': '2026-07-09T18:58:00Z',
          'downloadUrl': 'https://example.com/app.apk',
        },
      ],
    );
    await expandProject(tester, 'SF');

    // "Builds en downloads" blijft standaard ingeklapt, ook nadat het project-paneel zelf open is.
    expect(find.text('Build'), findsNothing);
    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    expect(find.text('Build'), findsOneWidget);
    expect(find.text('success'), findsOneWidget);
    // Ook in de altijd-zichtbare apk-sync-rij (zie de compacte "APK's"-lijst boven "Builds en
    // downloads"), dus twee keer i.p.v. één.
    expect(find.textContaining('software-factory-dashboard.apk'), findsNWidgets(2));
    expect(find.text('Download'), findsOneWidget);
  });

  testWidgets('Meerdere apk\'s met dezelfde bestandsnaam tonen elk hun eigen app-naam (uit releaseTag)', (tester) async {
    await pumpProjects(
      tester,
      {
        'name': 'robberts-assistent',
        'repoUrl': 'https://github.com/robbert/robberts-assistent',
        'storiesTodo': 1,
        'storiesInProgress': 0,
        'storiesDone': 1,
        'totalCostUsd': 4.04,
        'activeAgentCount': 0,
        'prdVersion': null,
        'hasDeployConfig': false,
        'buildStatus': {
          'lastMainBuildAt': null,
          'mainBuildActive': false,
          'prBuildActive': false,
          'syncStatus': 'UNAVAILABLE',
        },
      },
      downloads: [
        {
          'projectKey': 'robberts-assistent',
          'name': 'app-release.apk',
          'size': 43500000,
          'createdAt': '2026-07-11T23:18:00Z',
          'downloadUrl': 'https://example.com/wind.apk',
          'releaseTag': 'wind-latest',
        },
        {
          'projectKey': 'robberts-assistent',
          'name': 'app-release.apk',
          'size': 50400000,
          'createdAt': '2026-07-12T09:15:00Z',
          'downloadUrl': 'https://example.com/assistent.apk',
          'releaseTag': 'robberts-assistent-latest',
        },
        {
          'projectKey': 'robberts-assistent',
          'name': 'app-release.apk',
          'size': 49100000,
          'createdAt': '2026-07-11T23:20:00Z',
          'downloadUrl': 'https://example.com/notities.apk',
          'releaseTag': 'notities-latest',
        },
      ],
    );
    await expandProject(tester, 'robberts-assistent');

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    // Elke naam staat zowel in de compacte "APK's"-sync-lijst als in de uitgeklapte download-rij.
    expect(find.text('Wind'), findsNWidgets(2));
    expect(find.text('Robberts Assistent'), findsNWidgets(2));
    expect(find.text('Notities'), findsNWidgets(2));
    expect(find.textContaining('app-release.apk'), findsNWidgets(3));
    expect(find.text('Download'), findsNWidgets(3));
  });

  testWidgets(
    'Meerdere releases van dezelfde app (uniek getagd per push) tonen maar één rij in de compacte apk-lijst',
    (tester) async {
      await pumpProjects(
        tester,
        {
          'name': 'SF',
          'repoUrl': 'https://github.com/robbert/softwarefactory',
          'storiesTodo': 0,
          'storiesInProgress': 0,
          'storiesDone': 0,
          'totalCostUsd': 0.0,
          'activeAgentCount': 0,
          'prdVersion': null,
          'hasDeployConfig': false,
          'buildStatus': {
            'lastMainBuildAt': null,
            'mainBuildActive': false,
            'prBuildActive': false,
            'syncStatus': 'UNAVAILABLE',
          },
        },
        downloads: [
          {
            'projectKey': 'SF',
            'name': 'software-factory-dashboard-20260709.apk',
            'size': 51600000,
            'createdAt': '2026-07-09T18:58:00Z',
            'downloadUrl': 'https://example.com/old.apk',
            'releaseTag': 'dashboard-apk-20260709-185800-aaa1111',
            'commitSha': 'aaa1111',
            'syncStatus': 'OUT_OF_SYNC',
          },
          {
            'projectKey': 'SF',
            'name': 'software-factory-dashboard-20260726.apk',
            'size': 51700000,
            'createdAt': '2026-07-26T08:36:23Z',
            'downloadUrl': 'https://example.com/new.apk',
            'releaseTag': 'dashboard-apk-20260726-083623-cfe0132',
            'commitSha': 'cfe0132',
            'syncStatus': 'IN_SYNC',
          },
        ],
      );
      await expandProject(tester, 'SF');

      // Compacte lijst: alleen de nieuwste release van deze app (herkenbaar aan de sha),
      // ondanks dat beide downloads bij dezelfde app horen (releaseTag met een unieke
      // datum/tijd/sha-staart per push, zoals softwarefactory's eigen dashboard-apk).
      expect(find.text('cfe0132'), findsOneWidget);
      expect(find.text('aaa1111'), findsNothing);
      expect(find.text('In sync met main'), findsOneWidget);
      expect(find.text('Loopt achter op main'), findsNothing);
    },
  );

  testWidgets('Een apk die achterloopt op main toont de "Loopt achter op main"-badge', (tester) async {
    await pumpProjects(
      tester,
      {
        'name': 'robberts-assistent',
        'repoUrl': 'https://github.com/robbert/robberts-assistent',
        'storiesTodo': 0,
        'storiesInProgress': 0,
        'storiesDone': 0,
        'totalCostUsd': 0.0,
        'activeAgentCount': 0,
        'prdVersion': null,
        'hasDeployConfig': false,
        'buildStatus': {
          'lastMainBuildAt': null,
          'mainBuildActive': false,
          'prBuildActive': false,
          'syncStatus': 'UNAVAILABLE',
        },
      },
      downloads: [
        {
          'projectKey': 'robberts-assistent',
          'name': 'app-release.apk',
          'size': 43500000,
          'createdAt': '2026-07-11T23:18:00Z',
          'downloadUrl': 'https://example.com/wind.apk',
          'releaseTag': 'wind-latest',
          'commitSha': 'cafebabe',
          'syncStatus': 'OUT_OF_SYNC',
        },
      ],
    );
    await expandProject(tester, 'robberts-assistent');

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    // Compacte "APK's"-sync-lijst + de uitgeklapte download-rij.
    expect(find.text('Loopt achter op main'), findsNWidgets(2));
  });

  testWidgets('Een up-to-date apk toont de "In sync met main"-badge, geen waarschuwing', (tester) async {
    await pumpProjects(
      tester,
      {
        'name': 'robberts-assistent',
        'repoUrl': 'https://github.com/robbert/robberts-assistent',
        'storiesTodo': 0,
        'storiesInProgress': 0,
        'storiesDone': 0,
        'totalCostUsd': 0.0,
        'activeAgentCount': 0,
        'prdVersion': null,
        'hasDeployConfig': false,
        'buildStatus': {
          'lastMainBuildAt': null,
          'mainBuildActive': false,
          'prBuildActive': false,
          'syncStatus': 'UNAVAILABLE',
        },
      },
      downloads: [
        {
          'projectKey': 'robberts-assistent',
          'name': 'app-release.apk',
          'size': 43500000,
          'createdAt': '2026-07-11T23:18:00Z',
          'downloadUrl': 'https://example.com/wind.apk',
          'releaseTag': 'wind-latest',
          'commitSha': 'deadbeef',
          'syncStatus': 'IN_SYNC',
        },
      ],
    );
    await expandProject(tester, 'robberts-assistent');

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    // Compacte "APK's"-sync-lijst + de uitgeklapte download-rij.
    expect(find.text('In sync met main'), findsNWidgets(2));
    expect(find.text('Loopt achter op main'), findsNothing);
  });

  testWidgets('Een apk zonder main-build-referentie toont de neutrale badge, geen foutmelding', (tester) async {
    await pumpProjects(
      tester,
      {
        'name': 'robberts-assistent',
        'repoUrl': 'https://github.com/robbert/robberts-assistent',
        'storiesTodo': 0,
        'storiesInProgress': 0,
        'storiesDone': 0,
        'totalCostUsd': 0.0,
        'activeAgentCount': 0,
        // prdVersion + hasDeployConfig zetten hier bewust op "wel beschikbaar" zodat de
        // buildStatus-badge zelf niet ook UNAVAILABLE toont — anders matcht de assertie op
        // "Geen productieversie beschikbaar" toevallig twee widgets (buildStatus én download) en
        // toetst deze test niet meer specifiek de download-rij.
        'prdVersion': {'commitShort': 'deadbee', 'commitDate': '2026-07-08', 'branch': 'main'},
        'hasDeployConfig': true,
        'buildStatus': {
          'lastMainBuildAt': '2026-07-08T10:05:00Z',
          'mainBuildActive': false,
          'prBuildActive': false,
          'syncStatus': 'IN_SYNC',
        },
      },
      downloads: [
        {
          'projectKey': 'robberts-assistent',
          'name': 'app-release.apk',
          'size': 43500000,
          'createdAt': '2026-07-11T23:18:00Z',
          'downloadUrl': 'https://example.com/wind.apk',
          'releaseTag': 'wind-latest',
          'syncStatus': 'UNAVAILABLE',
        },
      ],
    );
    await expandProject(tester, 'robberts-assistent');

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    // Compacte "APK's"-sync-lijst + de uitgeklapte download-rij.
    expect(find.text('Geen productieversie beschikbaar'), findsNWidgets(2));
    expect(find.text('Loopt achter op main'), findsNothing);
  });

  Map<String, dynamic> minimalProject(String name) => {
    'name': name,
    'repoUrl': 'https://github.com/robbert/$name',
    'storiesTodo': 0,
    'storiesInProgress': 0,
    'storiesDone': 0,
    'totalCostUsd': 0.0,
    'activeAgentCount': 0,
    'prdVersion': null,
    'hasDeployConfig': false,
    'buildStatus': {
      'lastMainBuildAt': null,
      'mainBuildActive': false,
      'prBuildActive': false,
      'syncStatus': 'UNAVAILABLE',
    },
  };

  testWidgets('Build-/deploystatus per branch wordt opgehaald zodra het project-paneel wordt uitgeklapt', (tester) async {
    var requested = false;
    await pumpProjects(
      tester,
      minimalProject('SF'),
      onBranchTimelineRequested: (_) => requested = true,
      after: (tester, state) async {
        expect(requested, isFalse);

        await expandProject(tester, 'SF');

        expect(requested, isTrue);
        // Geen eigen uitklap-toggle meer: de tekst staat er meteen, zonder tweede tik.
        expect(find.text('Build- en deploystatus per branch'), findsOneWidget);
      },
    );
  });

  testWidgets('Projects-pagina herlaadt niet vanzelf op een "changed"-tick (geen opdringerige auto-refresh)', (
    tester,
  ) async {
    var projectsRequestCount = 0;
    await pumpProjects(
      tester,
      minimalProject('SF'),
      onProjectsRequested: () => projectsRequestCount++,
      after: (tester, state) async {
        expect(projectsRequestCount, 1);

        state.simulateChanged();
        await tester.pumpAndSettle();

        expect(projectsRequestCount, 1);
      },
    );
  });

  testWidgets('Branch-timeline toont per-job-bolletjes en deploy-bolletje alleen op de main-rij', (tester) async {
    await pumpProjects(
      tester,
      minimalProject('SF'),
      branchTimeline: {
        'rows': [
          {
            'kind': 'main',
            'branchName': 'main',
            'commitShortSha': 'a1b2c3d',
            'commitMessage': 'Update UI',
            'commitDate': '2026-07-24T10:34:00Z',
            'jobs': [
              {
                'workflowName': 'Build backend',
                'status': 'completed',
                'conclusion': 'success',
                'htmlUrl': 'https://x/1',
              },
              {
                'workflowName': 'Build wind',
                'status': 'in_progress',
                'conclusion': null,
                'htmlUrl': 'https://x/2',
              },
              {
                'workflowName': 'Build notities',
                'status': null,
                'conclusion': null,
                'htmlUrl': null,
              },
            ],
            'liveComponents': [
              {
                'label': 'backend',
                'shortSha': 'a1b2c3d',
                'syncStatus': 'IN_SYNC',
                'consoleUrl': 'https://console/backend',
              },
            ],
          },
          {
            'kind': 'pull_request',
            'branchName': 'fix/login-bug',
            'commitShortSha': 'e4f5a6b',
            'commitMessage': 'Fix login bug',
            'commitDate': '2026-07-24T09:00:00Z',
            'prNumber': 182,
            'prUrl': 'https://github.com/robbert/sf/pull/182',
            'jobs': [
              {
                'workflowName': 'Build backend',
                'status': 'completed',
                'conclusion': 'failure',
                'htmlUrl': 'https://x/3',
              },
            ],
            'liveComponents': <Map<String, dynamic>>[],
          },
        ],
        'errors': <String>[],
      },
      after: (tester, state) async {
        await tester.tap(find.text('SF'));
        // Geen pumpAndSettle: de "in_progress"-job rendert een oneindig herhalende pulse-animatie
        // (_PulsingDot), waar pumpAndSettle nooit op stopt. Een paar losse frames volstaan om de
        // uitklap en de async branch-timeline-fetch/parse af te ronden.
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
        await tester.pump(const Duration(milliseconds: 300));

        expect(find.text('main'), findsOneWidget);
        expect(find.text('fix/login-bug'), findsOneWidget);
        expect(find.text('PR #182'), findsOneWidget);
        // Workflow-namen zijn nu leesbaar in de lijst (niet alleen als tooltip op een bolletje);
        // 'Build backend' staat op zowel de main- als de PR-rij.
        expect(find.text('Build backend'), findsNWidgets(2));
        expect(find.text('Build wind'), findsOneWidget);
        expect(find.text('Build notities'), findsOneWidget);
        // Tekstuele statusbadges per job — leesbaar zonder te hoeven hoveren.
        expect(find.text('Geslaagd'), findsOneWidget);
        expect(find.text('Bezig'), findsOneWidget);
        expect(find.text('Niet getriggerd'), findsOneWidget);
        expect(find.text('Mislukt'), findsOneWidget);
        // Deploy-regel verschijnt precies één keer — alleen op de main-rij, niet op de PR-rij.
        expect(find.text('backend'), findsOneWidget);
        expect(find.text('In sync met main'), findsOneWidget);

        // Alleen jobs mét htmlUrl (3 van de 4) tonen een open-link-knop; 'Build notities' (geen run
        // voor deze commit, dus geen htmlUrl) niet.
        expect(find.byTooltip('Open workflow-run'), findsNWidgets(3));
        expect(find.byTooltip('Open OpenShift-console'), findsOneWidget);
      },
    );
  });
}
