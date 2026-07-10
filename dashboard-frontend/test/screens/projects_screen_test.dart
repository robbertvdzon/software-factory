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
  }) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
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
}
