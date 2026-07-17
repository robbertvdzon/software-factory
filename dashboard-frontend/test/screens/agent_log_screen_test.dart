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
  testWidgets('Toont een lege staat als er nog geen log-events zijn', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/agents/1/log')) {
        return http.Response(
          jsonEncode({'agentRunId': 1, 'lines': <Map<String, dynamic>>[], 'outcome': null, 'ended': false}),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(
          home: AgentLogScreen(state: state, agentRunId: '1', storyKey: 'SF-1', role: 'developer'),
        ),
      );
      await pumpUntilSettled(tester);

      expect(find.text('Nog geen log-events voor deze agent-run.'), findsOneWidget);

      await tester.pumpWidget(const SizedBox.shrink());
    }, () => mockClient);
  });

  testWidgets('Toont de gelogde regels van een afgeronde run zonder pollen', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);
    var requestCount = 0;

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/agents/2/log')) {
        requestCount++;
        return http.Response(
          jsonEncode({
            'agentRunId': 2,
            'lines': [
              {'id': 1, 'kind': 'docker-stdout', 'line': 'Starting agent...'},
              {'id': 2, 'kind': 'docker-stderr', 'line': 'warning: iets ging bijna mis'},
            ],
            'outcome': 'developed',
            'ended': true,
          }),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(
          home: AgentLogScreen(state: state, agentRunId: '2', storyKey: 'SF-2', role: 'developer'),
        ),
      );
      await pumpUntilSettled(tester);

      expect(find.text('Starting agent...'), findsOneWidget);
      expect(find.text('warning: iets ging bijna mis'), findsOneWidget);
      expect(requestCount, 1, reason: 'ended=true stopt de poll-timer na de eerste load');

      await tester.pumpWidget(const SizedBox.shrink());
    }, () => mockClient);
  });

  testWidgets('Toont een foutmelding als het endpoint faalt', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async => http.Response('boom', 500));

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(
          home: AgentLogScreen(state: state, agentRunId: '3', storyKey: 'SF-3', role: 'tester'),
        ),
      );
      await pumpUntilSettled(tester);

      expect(find.textContaining('HTTP 500'), findsOneWidget);

      await tester.pumpWidget(const SizedBox.shrink());
    }, () => mockClient);
  });
}
