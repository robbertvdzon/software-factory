import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/overview_screens.dart';

import '../pump_utils.dart';

void main() {
  Map<String, dynamic> agentRun({
    required String id,
    required String storyKey,
    required String role,
    String? outcome,
    String? endedAt,
    int durationMs = 0,
  }) => {
    'id': id,
    'storyKey': storyKey,
    'role': role,
    'startedAt': '2026-07-17T08:00:00Z',
    'endedAt': endedAt,
    'outcome': outcome,
    'durationMs': durationMs,
  };

  testWidgets('Agents-tab toont starttijd en looptijd op een tile', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/agents')) {
        return http.Response(
          jsonEncode({
            'activeAgentRuns': <Map<String, dynamic>>[],
            'recentAgentRuns': [
              agentRun(
                id: '7',
                storyKey: 'SF-1',
                role: 'developer',
                outcome: 'developed',
                endedAt: '2026-07-17T08:05:28Z',
                durationMs: 328000,
              ),
            ],
            'errors': <String>[],
          }),
          200,
        );
      }
      if (request.url.path.endsWith('/api/v1/assistant/status')) {
        return http.Response(
          jsonEncode({'enabled': false, 'busy': false, 'activeChatCount': 0, 'lastActivityAt': null}),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: AgentsScreen(state: state)));
      await pumpUntilSettled(tester);

      // Toon geschiedenis om de recente run (met de vaste durationMs) te zien.
      await tester.tap(find.text('Toon geschiedenis'));
      await pumpUntilSettled(tester);

      expect(find.textContaining('5m28s'), findsOneWidget);

      await tester.tap(find.text('SF-1'));
      await pumpUntilSettled(tester);

      expect(find.text('SF-1 · developer'), findsOneWidget);

      // Widget tree opruimen zodat de looptijd-ticker (Timer.periodic) netjes wordt geannuleerd.
      await tester.pumpWidget(const SizedBox.shrink());
    }, () => mockClient);
  });

  testWidgets('Tikken op een agent-tile opent de log-detailweergave', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/agents')) {
        return http.Response(
          jsonEncode({
            'activeAgentRuns': [agentRun(id: '9', storyKey: 'SF-2', role: 'tester')],
            'recentAgentRuns': <Map<String, dynamic>>[],
            'errors': <String>[],
          }),
          200,
        );
      }
      if (request.url.path.endsWith('/api/v1/assistant/status')) {
        return http.Response(
          jsonEncode({'enabled': false, 'busy': false, 'activeChatCount': 0, 'lastActivityAt': null}),
          200,
        );
      }
      if (request.url.path.endsWith('/api/v1/agents/9/log')) {
        return http.Response(
          jsonEncode({'agentRunId': 9, 'lines': <Map<String, dynamic>>[], 'outcome': null, 'ended': false}),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: AgentsScreen(state: state)));
      await pumpUntilSettled(tester);

      await tester.tap(find.text('SF-2'));
      await pumpUntilSettled(tester);

      expect(find.text('SF-2 · tester'), findsOneWidget);
      expect(find.text('Nog geen log-events voor deze agent-run.'), findsOneWidget);

      await tester.pageBack();
      await pumpUntilSettled(tester);
      await tester.pumpWidget(const SizedBox.shrink());
    }, () => mockClient);
  });
}
