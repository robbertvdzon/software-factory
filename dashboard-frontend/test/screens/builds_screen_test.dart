import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/builds_screen.dart';

Map<String, dynamic> _commit(String sha, {String deployed = 'UNAVAILABLE'}) => {
  'sha': sha,
  'shortSha': sha.substring(0, 7),
  'message': 'ci: bump dashboard-backend to sha-$sha',
  'date': '2026-07-24T10:00:00Z',
  'jobs': [
    {
      'workflowName': 'Repository verification',
      'status': 'completed',
      'conclusion': 'success',
      'startedAt': '2026-07-24T10:00:00Z',
      'finishedAt': '2026-07-24T10:05:00Z',
    },
  ],
  'deployed': deployed,
};

void main() {
  // Alle interactie (taps/pumpAndSettle) moet binnen de runWithClient-zone blijven — elke
  // ApiClient.getJson-call na de initiële pump (project- of branch-selectie, "Meer") gaat anders
  // buiten de mock-zone om en krijgt van Flutter's test-binding een harde HTTP 400.
  Future<void> runBuildsTest(
    WidgetTester tester, {
    required Map<String, dynamic> Function(Uri url) respond,
    required Future<void> Function() interact,
  }) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      return http.Response(jsonEncode(respond(request.url)), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: BuildsScreen(state: state)));
      await tester.pumpAndSettle();
      await interact();
    }, () => mockClient);
  }

  testWidgets('Kiezen van project en branch toont de commit-historie', (tester) async {
    await runBuildsTest(
      tester,
      respond: (url) {
        if (url.path == '/api/v1/projects') {
          return {
            'projects': [
              {'name': 'softwarefactory', 'hasDeployConfig': true},
            ],
            'buildsRepos': <Map<String, dynamic>>[],
            'downloads': <Map<String, dynamic>>[],
          };
        }
        if (url.path.endsWith('/branch-timeline')) {
          return {
            'rows': [
              {'kind': 'main', 'branchName': 'main', 'jobs': <Map<String, dynamic>>[]},
              {
                'kind': 'pull_request',
                'branchName': 'ai/SF-1281',
                'prNumber': 1281,
                'jobs': <Map<String, dynamic>>[],
              },
            ],
            'errors': <String>[],
          };
        }
        if (url.path.endsWith('/build-history')) {
          final page = int.parse(url.queryParameters['page'] ?? '1');
          if (page == 1) {
            return {
              'branch': 'main',
              'commits': [
                _commit('eb4048deadbeef', deployed: 'IN_SYNC'),
                _commit('4b430c3cafebabe'),
              ],
              'hasMore': true,
              'errors': <String>[],
            };
          }
          return {
            'branch': 'main',
            'commits': [_commit('aaa1111bbb2222')],
            'hasMore': false,
            'errors': <String>[],
          };
        }
        return {};
      },
      interact: () async {
        // Project kiezen.
        await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Project'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('softwarefactory').last);
        await tester.pumpAndSettle();

        // Branch-dropdown verschijnt met main + de open PR.
        expect(find.widgetWithText(DropdownButtonFormField<String>, 'Branch'), findsOneWidget);
        await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Branch'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('main').last);
        await tester.pumpAndSettle();

        // Eerste pagina (2 commits) staat er, met een echte sync-badge op de eerste.
        expect(find.text('eb4048d'), findsOneWidget);
        expect(find.text('4b430c3'), findsOneWidget);
        expect(find.text('In sync met main'), findsOneWidget);
        expect(find.text('Repository verification'), findsNWidgets(2));
        expect(find.text('Meer'), findsOneWidget);

        // "Meer" laadt de volgende pagina en de knop verdwijnt (hasMore=false).
        await tester.tap(find.text('Meer'));
        await tester.pumpAndSettle();

        expect(find.text('aaa1111'), findsOneWidget);
        expect(find.text('Meer'), findsNothing);
        // Eerdere commits blijven staan (append, geen vervanging).
        expect(find.text('eb4048d'), findsOneWidget);
      },
    );
  });

  testWidgets('Toont een lege staat als de branch geen commits heeft', (tester) async {
    await runBuildsTest(
      tester,
      respond: (url) {
        if (url.path == '/api/v1/projects') {
          return {
            'projects': [
              {'name': 'softwarefactory', 'hasDeployConfig': true},
            ],
          };
        }
        if (url.path.endsWith('/branch-timeline')) {
          return {
            'rows': [
              {'kind': 'main', 'branchName': 'main', 'jobs': <Map<String, dynamic>>[]},
            ],
            'errors': <String>[],
          };
        }
        return {'branch': 'main', 'commits': <Map<String, dynamic>>[], 'hasMore': false, 'errors': <String>[]};
      },
      interact: () async {
        await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Project'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('softwarefactory').last);
        await tester.pumpAndSettle();
        await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Branch'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('main').last);
        await tester.pumpAndSettle();

        expect(find.text('Geen commits gevonden op deze branch.'), findsOneWidget);
      },
    );
  });
}
