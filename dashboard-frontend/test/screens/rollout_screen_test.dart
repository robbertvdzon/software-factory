import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/rollout_screen.dart';
import 'package:softwarefactory_dashboard/screens/story_detail_screen.dart';

void main() {
  MockClient buildClient(Map<String, dynamic> rolloutResponse) => MockClient((request) async {
    if (request.url.path.endsWith('/api/v1/rollout')) {
      return http.Response(jsonEncode(rolloutResponse), 200);
    }
    if (request.url.path.endsWith('/api/v1/stories/SF-500')) {
      return http.Response(
        jsonEncode({
          'issue': {'key': 'SF-500', 'summary': 'Test', 'status': 'Done', 'fields': {}},
          'errors': <String>[],
        }),
        200,
      );
    }
    return http.Response('Not found', 404);
  });

  final runWithData = {
    'run': {
      'id': 1,
      'storyKey': 'SF-500',
      'targetRepo': 'git@github.com:robbert/sf.git',
      'endedAt': '2026-07-20T10:00:00Z',
      'prNumber': 42,
      'prUrl': 'https://github.example/pr/42',
    },
    'targets': [
      {'name': 'backend', 'live': true},
      {'name': 'frontend', 'live': false},
    ],
  };

  testWidgets('toont een lege staat als er geen stories op live-bevestiging wachten', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: RolloutScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => buildClient({'items': <Map<String, dynamic>>[], 'errors': <String>[]}));

    expect(find.textContaining('Geen stories in afwachting'), findsOneWidget);
  });

  testWidgets('toont per deploy-doel een live/nog-niet-live-badge', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: RolloutScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => buildClient({
          'items': [runWithData],
          'errors': <String>[],
        }));

    expect(find.text('SF-500'), findsOneWidget);
    expect(find.textContaining('backend: live'), findsOneWidget);
    expect(find.textContaining('frontend: nog niet live'), findsOneWidget);
  });

  testWidgets('toont "status onbekend" als targets niet bepaald kon worden', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: RolloutScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => buildClient({
          'items': [
            {
              'run': {
                'id': 1,
                'storyKey': 'SF-501',
                'targetRepo': 'git@github.com:robbert/sf.git',
                'endedAt': '2026-07-20T10:00:00Z',
                'prNumber': null,
                'prUrl': null,
              },
              'targets': null,
            },
          ],
          'errors': <String>[],
        }));

    expect(find.text('status onbekend'), findsOneWidget);
  });

  testWidgets('tikken op een story-tile navigeert naar de story-detailpagina', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: RolloutScreen(state: state)));
      await tester.pumpAndSettle();

      await tester.tap(find.text('SF-500'));
      await tester.pumpAndSettle();

      expect(find.byType(StoryDetailScreen), findsOneWidget);
    }, () => buildClient({
          'items': [runWithData],
          'errors': <String>[],
        }));
  });
}
