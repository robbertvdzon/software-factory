import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/pending_action.dart';
import 'package:softwarefactory_dashboard/screens/my_actions_screen.dart';

void main() {
  test('documenter-wachtfasen hebben de juiste vervolgfasen', () {
    final question = pendingActionFor(
      isStory: false,
      phase: 'documentation-with-questions',
      subtaskType: 'documentation',
    );
    final approval = pendingActionFor(
      isStory: false,
      phase: 'documented',
      subtaskType: 'documentation',
    );

    expect(question?.kind, PendingKind.question);
    expect(question?.approveTarget, 'documentation-questions-answered');
    expect(approval?.kind, PendingKind.approval);
    expect(approval?.approveTarget, 'documentation-approved');
    expect(approval?.rejectTarget, isNull);
  });

  testWidgets('My actions toont beide documenter-wachtfasen', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final payload = {
      'groups': [
        {
          'storyKey': 'SF-1',
          'storySummary': 'Story met documentatie',
          'items': [
            {
              'issue': {
                'key': 'SF-2',
                'issueType': 'SUBTASK',
                'fields': {
                  'subtaskPhase': 'documentation-with-questions',
                  'subtaskType': 'documentation',
                },
              },
              'question': 'Welke handleiding moet ik bijwerken?',
              'agentGaveNoDecision': false,
            },
            {
              'issue': {
                'key': 'SF-3',
                'issueType': 'SUBTASK',
                'fields': {
                  'subtaskPhase': 'documented',
                  'subtaskType': 'documentation',
                },
              },
              'agentGaveNoDecision': false,
            },
          ],
        },
      ],
    };
    final mockClient = MockClient(
      (request) async => http.Response(jsonEncode(payload), 200),
    );

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: MyActionsScreen(state: AppState(ApiClient()))),
      );
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.text('Vraag van de documenter'), findsOneWidget);
    expect(find.text('Welke handleiding moet ik bijwerken?'), findsOneWidget);
    expect(find.text('Documentatie beoordelen'), findsOneWidget);
  });
}
