import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/agent_log_screen.dart';
import 'package:softwarefactory_dashboard/screens/agents_screen.dart';

void main() {
  MockClient buildClient({
    required List<Map<String, dynamic>> active,
    required List<Map<String, dynamic>> recent,
    Map<String, dynamic>? eventsResponse,
  }) =>
      MockClient((request) async {
        if (request.url.path.endsWith('/api/v1/agents')) {
          return http.Response(
            jsonEncode({
              'activeAgentRuns': active,
              'recentAgentRuns': recent,
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
        if (request.url.path.contains('/api/v1/agents/') && request.url.path.endsWith('/events')) {
          return http.Response(
            jsonEncode(eventsResponse ?? {'agentRunId': 1, 'lines': <Map<String, dynamic>>[], 'errors': <String>[]}),
            200,
          );
        }
        return http.Response('Not found', 404);
      });

  final runningAgent = {
    'id': 7,
    'storyKey': 'SF-1038',
    'role': 'developer',
    'outcome': null,
    'startedAt': DateTime.now().toUtc().subtract(const Duration(minutes: 1, seconds: 5)).toIso8601String(),
    'endedAt': null,
    'durationMs': 0,
  };

  final finishedAgent = {
    'id': 8,
    'storyKey': 'SF-900',
    'role': 'tester',
    'outcome': 'developed',
    'startedAt': '2026-07-17T09:00:00Z',
    'endedAt': '2026-07-17T09:05:28Z',
    'durationMs': 328000,
  };

  // Een actieve tile start een eigen Timer.periodic (elke seconde) om de looptijd bij te tellen;
  // pumpAndSettle() blijft dan hangen (nooit-idle timers), dus deze test pumpt gericht i.p.v. te
  // wachten tot alles stil valt.
  testWidgets('toont starttijd en looptijd op een actieve agent-tile', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: AgentsScreen(state: state)));
      await tester.pump();
      await tester.pump();
    }, () => buildClient(active: [runningAgent], recent: []));

    expect(find.textContaining('Gestart'), findsOneWidget);
    expect(find.textContaining('looptijd 1m'), findsOneWidget);
  });

  testWidgets('toont de vaste looptijd op een afgeronde agent-tile in de geschiedenis', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: AgentsScreen(state: state)));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Toon geschiedenis'));
      await tester.pumpAndSettle();
    }, () => buildClient(active: [], recent: [finishedAgent]));

    expect(find.textContaining('Gestart'), findsOneWidget);
    expect(find.textContaining('looptijd 5m28s'), findsOneWidget);
  });

  testWidgets('een agent-tile is klikbaar en navigeert naar de log-detailweergave', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: AgentsScreen(state: state)));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Toon geschiedenis'));
      await tester.pumpAndSettle();

      await tester.tap(find.textContaining('SF-900'));
      await tester.pumpAndSettle();

      expect(find.byType(AgentLogScreen), findsOneWidget);
      expect(find.text('hallo wereld'), findsOneWidget);
    }, () => buildClient(
          active: [],
          recent: [finishedAgent],
          eventsResponse: {
            'agentRunId': 8,
            'lines': [
              {'kind': 'docker-stdout', 'text': 'hallo wereld'},
            ],
            'errors': <String>[],
          },
        ));
  });
}
