import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/agent_log_screen.dart';

void main() {
  Future<AppState> setUpState() async {
    SharedPreferences.setMockInitialValues({});
    return AppState(ApiClient());
  }

  testWidgets('toont een expliciete lege staat als er nog geen log-regels zijn', (tester) async {
    final state = await setUpState();
    final mockClient = MockClient((request) async {
      return http.Response(jsonEncode({'agentRunId': 1, 'lines': <Map<String, dynamic>>[], 'errors': <String>[]}), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(
        home: AgentLogScreen(state: state, agentRunId: 1, storyKey: 'SF-1', role: 'developer', active: false),
      ));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.text('Nog geen log-regels beschikbaar voor deze run.'), findsOneWidget);
  });

  testWidgets('toont de volledige log van een afgeronde run zonder verdere updates', (tester) async {
    final state = await setUpState();
    var requestCount = 0;
    final mockClient = MockClient((request) async {
      requestCount++;
      return http.Response(
        jsonEncode({
          'agentRunId': 1,
          'lines': [
            {'kind': 'docker-stdout', 'text': 'regel 1'},
            {'kind': 'docker-stderr', 'text': 'regel 2'},
          ],
          'errors': <String>[],
        }),
        200,
      );
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(
        home: AgentLogScreen(state: state, agentRunId: 1, storyKey: 'SF-1', role: 'developer', active: false),
      ));
      await tester.pumpAndSettle();

      expect(find.text('regel 1'), findsOneWidget);
      expect(find.text('regel 2'), findsOneWidget);
      expect(requestCount, 1);

      // Geen actieve poller voor een afgeronde run: wachten levert geen extra requests op.
      await tester.pump(const Duration(seconds: 5));
      expect(requestCount, 1);
    }, () => mockClient);
  });

  // Een actieve run pollt periodiek (Timer.periodic); pumpAndSettle() zou daardoor nooit stil
  // vallen, dus deze test pumpt gericht i.p.v. te wachten tot alles idle is.
  testWidgets('werkt de log van een nog actieve run automatisch bij zonder handmatige herlaad-actie', (tester) async {
    final state = await setUpState();
    var requestCount = 0;
    final mockClient = MockClient((request) async {
      requestCount++;
      final lines = requestCount == 1
          ? [
              {'kind': 'docker-stdout', 'text': 'eerste regel'},
            ]
          : [
              {'kind': 'docker-stdout', 'text': 'eerste regel'},
              {'kind': 'docker-stdout', 'text': 'nieuwe regel'},
            ];
      return http.Response(jsonEncode({'agentRunId': 1, 'lines': lines, 'errors': <String>[]}), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(
        home: AgentLogScreen(state: state, agentRunId: 1, storyKey: 'SF-1', role: 'developer', active: true),
      ));
      await tester.pump();
      await tester.pump();

      expect(find.text('eerste regel'), findsOneWidget);
      expect(find.text('nieuwe regel'), findsNothing);

      await tester.pump(const Duration(seconds: 3));
      await tester.pump();

      expect(find.text('nieuwe regel'), findsOneWidget);

      // Wissel naar een lege afgeronde-run-widget zodat de actieve poller opgeruimd wordt
      // (dispose annuleert de Timer) voordat de test eindigt.
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }, () => mockClient);
  });

  testWidgets('toont een foutmelding als het ophalen van de log mislukt', (tester) async {
    final state = await setUpState();
    final mockClient = MockClient((request) async => http.Response('boom', 500));

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(
        home: AgentLogScreen(state: state, agentRunId: 1, storyKey: 'SF-1', role: 'developer', active: false),
      ));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.textContaining('HTTP 500'), findsOneWidget);
  });
}
