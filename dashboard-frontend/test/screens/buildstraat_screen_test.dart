import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/buildstraat_screen.dart';

void main() {
  Future<void> pumpBuildstraat(
    WidgetTester tester, {
    required Map<String, dynamic> branchTimeline,
    String branchName = 'ai/SF-1281',
    int? prNumber,
    // Geen pumpAndSettle als een job 'in_progress' is: dat rendert een oneindig herhalende
    // pulse-animatie (_PulsingDot) waar pumpAndSettle nooit op stopt.
    bool settle = true,
  }) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/branch-timeline')) {
        return http.Response(jsonEncode(branchTimeline), 200);
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(
          home: BuildstraatScreen(
            state: state,
            projectName: 'SF',
            branchName: branchName,
            prNumber: prNumber,
          ),
        ),
      );
      if (settle) {
        await tester.pumpAndSettle();
      } else {
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
        await tester.pump(const Duration(milliseconds: 300));
      }
    }, () => mockClient);
  }

  testWidgets('Toont de eigen branch/PR-rij als die nog open staat', (tester) async {
    await pumpBuildstraat(
      tester,
      branchName: 'ai/SF-1281',
      prNumber: 21,
      branchTimeline: {
        'rows': [
          {
            'kind': 'pull_request',
            'branchName': 'ai/SF-1281',
            'commitShortSha': 'f05686d',
            'commitMessage': 'fix: cache-buster',
            'commitDate': '2026-07-22T10:00:00Z',
            'prNumber': 21,
            'prUrl': 'https://github.com/robbert/sf/pull/21',
            'jobs': <Map<String, dynamic>>[],
            'liveComponents': <Map<String, dynamic>>[],
          },
          {
            'kind': 'main',
            'branchName': 'main',
            'commitShortSha': 'abc1234',
            'commitMessage': 'ci: bump',
            'commitDate': '2026-07-25T10:00:00Z',
            'jobs': <Map<String, dynamic>>[],
            'liveComponents': <Map<String, dynamic>>[],
          },
        ],
        'errors': <String>[],
      },
    );

    expect(find.text('ai/SF-1281'), findsNWidgets(2));
    expect(find.text('PR #21'), findsOneWidget);
    expect(find.textContaining('waarschijnlijk gemerged'), findsNothing);
  });

  testWidgets('Valt terug op main zodra de story-branch (gemerged) niet meer bestaat', (tester) async {
    await pumpBuildstraat(
      tester,
      branchName: 'ai/SF-1281',
      prNumber: null,
      settle: false,
      branchTimeline: {
        'rows': [
          {
            'kind': 'main',
            'branchName': 'main',
            'commitShortSha': 'eb4048d',
            'commitMessage': 'ci: bump dashboard-frontend to sha-eb4048d',
            'commitDate': '2026-07-25T14:40:00Z',
            'jobs': [
              {
                'workflowName': 'Build dashboard-backend image',
                'status': 'in_progress',
                'conclusion': null,
                'htmlUrl': 'https://x/1',
              },
            ],
            'liveComponents': [
              {
                'label': 'backend',
                'shortSha': 'eb4048d',
                'syncStatus': 'IN_SYNC',
                'consoleUrl': 'https://console/backend',
              },
            ],
          },
        ],
        'errors': <String>[],
      },
    );

    // Duidelijke uitleg dat dit niet meer de story-branch zelf is, maar de main-status.
    expect(find.textContaining("Branch 'ai/SF-1281' niet gevonden"), findsOneWidget);
    expect(find.textContaining('waarschijnlijk gemerged'), findsOneWidget);
    // En de main-rij zelf, zodat je ziet dat 'ie aan het bouwen/deployen is.
    expect(find.text('main'), findsOneWidget);
    expect(find.text('Build dashboard-backend image'), findsOneWidget);
    expect(find.text('Bezig'), findsOneWidget);
    expect(find.text('backend'), findsOneWidget);
    expect(find.text('In sync met main'), findsOneWidget);
  });

  testWidgets('Toont een lege staat als er helemaal geen rijen zijn', (tester) async {
    await pumpBuildstraat(
      tester,
      branchTimeline: {'rows': <Map<String, dynamic>>[], 'errors': <String>[]},
    );

    expect(find.text('Nog geen build- of deploygegevens gevonden.'), findsOneWidget);
  });
}
