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
    // Vervolgacties (bv. een uitklap-tap die zelf weer een gemockte HTTP-call doet) moeten binnen
    // dezelfde runWithClient-zone lopen als de initiële pump — anders krijgt de test-runtime's eigen
    // (niet-gemockte) HttpClient de aanroep en faalt die met HTTP 400, zie Flutter's eigen waarschuwing.
    Future<void> Function(WidgetTester tester)? after,
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
      await after?.call(tester);
    }, () => mockClient);
  }

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

    expect(find.text('backend'), findsOneWidget);
    expect(find.text('66d1019'), findsOneWidget);
    expect(find.text('sinds 2u00m'), findsOneWidget);
    expect(find.text('In sync met main'), findsOneWidget);
  });

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

    expect(find.text('Build'), findsNothing);
    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    expect(find.text('Build'), findsOneWidget);
    expect(find.text('success'), findsOneWidget);
    expect(find.textContaining('software-factory-dashboard.apk'), findsOneWidget);
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

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    expect(find.text('Wind'), findsOneWidget);
    expect(find.text('Robberts Assistent'), findsOneWidget);
    expect(find.text('Notities'), findsOneWidget);
    expect(find.textContaining('app-release.apk'), findsNWidgets(3));
    expect(find.text('Download'), findsNWidgets(3));
  });

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

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    expect(find.text('Loopt achter op main'), findsOneWidget);
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

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    expect(find.text('In sync met main'), findsOneWidget);
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

    await tester.tap(find.text('Builds en downloads'));
    await tester.pumpAndSettle();

    expect(find.text('Geen productieversie beschikbaar'), findsOneWidget);
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

  testWidgets('Build-/deploystatus per branch wordt pas opgehaald na uitklappen', (tester) async {
    var requested = false;
    await pumpProjects(
      tester,
      minimalProject('SF'),
      onBranchTimelineRequested: (_) => requested = true,
      after: (tester) async {
        expect(requested, isFalse);

        await tester.tap(find.text('Build- en deploystatus per branch'));
        await tester.pumpAndSettle();

        expect(requested, isTrue);
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
      after: (tester) async {
        await tester.tap(find.text('Build- en deploystatus per branch'));
        // Geen pumpAndSettle: de "in_progress"-job rendert een oneindig herhalende pulse-animatie
        // (_PulsingDot), waar pumpAndSettle nooit op stopt. Een paar losse frames volstaan om de
        // uitklap-transitie en de async branch-timeline-fetch/parse af te ronden.
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
        await tester.pump(const Duration(milliseconds: 300));

        expect(find.text('main'), findsOneWidget);
        expect(find.text('fix/login-bug'), findsOneWidget);
        expect(find.text('PR #182'), findsOneWidget);
        expect(find.byTooltip('Build backend — success'), findsOneWidget);
        expect(find.byTooltip('Build wind — in_progress'), findsOneWidget);
        expect(find.byTooltip('Build notities — niet getriggerd voor deze commit'), findsOneWidget);
        expect(find.byTooltip('Build backend — failure'), findsOneWidget);
        // Deploy-bolletje verschijnt precies één keer — alleen op de main-rij, niet op de PR-rij.
        expect(find.byTooltip('backend · a1b2c3d · in sync met main'), findsOneWidget);

        final triggeredDot = tester.widget<InkWell>(
          find.descendant(of: find.byTooltip('Build backend — success'), matching: find.byType(InkWell)),
        );
        expect(triggeredDot.onTap, isNotNull);

        final notTriggeredDot = tester.widget<InkWell>(
          find.descendant(
            of: find.byTooltip('Build notities — niet getriggerd voor deze commit'),
            matching: find.byType(InkWell),
          ),
        );
        expect(notTriggeredDot.onTap, isNull);
      },
    );
  });
}
