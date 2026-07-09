import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/builds_screen.dart';

void main() {
  testWidgets('Builds-scherm toont per repo de laatste workflow-runs met filter-pills', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/builds')) {
        return http.Response(
          jsonEncode({
            'repos': [
              {
                'projectKey': 'SF',
                'repository': 'robbert/softwarefactory',
                'runs': [
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
              },
              {
                'projectKey': 'PNF',
                'repository': 'robbert/personal-feed',
                'runs': <Map<String, dynamic>>[],
              },
            ],
            'errors': <String>[],
          }),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: BuildsScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.text('SF'), findsWidgets);
    expect(find.text('Workflow'), findsOneWidget);
    expect(find.text('Resultaat'), findsOneWidget);
    expect(find.text('Branch'), findsOneWidget);
    expect(find.text('Event'), findsOneWidget);
    expect(find.text('Duur'), findsOneWidget);
    expect(find.text('Build'), findsOneWidget);
    expect(find.text('success'), findsOneWidget);
    expect(find.text('1m28s'), findsOneWidget);
    expect(
      find.text('No GitHub Actions workflows found. This repository can still be handled by the '
          'factory, but it has no visible buildstraat yet.'),
      findsOneWidget,
    );

    // Filter op project 'PNF' verbergt de SF-workflow.
    await tester.tap(find.widgetWithText(ChoiceChip, 'PNF'));
    await tester.pumpAndSettle();
    expect(find.text('Build'), findsNothing);
  });

  testWidgets('Builds-scherm toont een lege staat zonder geconfigureerde repos', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/builds')) {
        return http.Response(jsonEncode({'repos': <Map<String, dynamic>>[], 'errors': <String>[]}), 200);
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: BuildsScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.text('No GitHub Actions workflows found for the configured repositories.'), findsOneWidget);
  });
}
