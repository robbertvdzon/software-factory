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
  Future<void> pumpProjects(WidgetTester tester, Map<String, dynamic> project) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/projects')) {
        return http.Response(
          jsonEncode({
            'projects': [project],
            'errors': <String>[],
          }),
          200,
        );
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
}
