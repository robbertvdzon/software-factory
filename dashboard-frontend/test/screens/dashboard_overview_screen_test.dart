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
  testWidgets('Dashboard toont een aandacht-sectie voor gefaalde default-branch builds', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/dashboard')) {
        return http.Response(
          jsonEncode({
            'issues': <Map<String, dynamic>>[],
            'activeRuns': <Map<String, dynamic>>[],
            'recentRuns': <Map<String, dynamic>>[],
            'activeAgentRuns': <Map<String, dynamic>>[],
            'errors': <String>[],
            'attentionBuilds': [
              {
                'projectKey': 'SF',
                'workflowName': 'Build',
                'branch': 'main',
                'conclusion': 'failure',
                'htmlUrl': 'https://github.com/robbert/softwarefactory/actions/runs/9',
              },
            ],
          }),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: DashboardOverviewScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.text('Aandacht nodig'), findsOneWidget);
    expect(find.textContaining('SF · Build gefaald op main'), findsOneWidget);
  });

  testWidgets('Dashboard toont geen aandacht-sectie zonder gefaalde builds', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/dashboard')) {
        return http.Response(
          jsonEncode({
            'issues': <Map<String, dynamic>>[],
            'activeRuns': <Map<String, dynamic>>[],
            'recentRuns': <Map<String, dynamic>>[],
            'activeAgentRuns': <Map<String, dynamic>>[],
            'errors': <String>[],
            'attentionBuilds': <Map<String, dynamic>>[],
          }),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: DashboardOverviewScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.text('Aandacht nodig'), findsNothing);
  });
}
