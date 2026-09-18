import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/pending_action.dart';

void main() {
  for (final choice in {
    'Toch doorgaan': 'test-approved',
    'Gericht herstel aanvragen': 'test-repair-requested',
    'Parkeren': 'pause',
  }.entries) {
    testWidgets('${choice.key} sends an explicit decision with a reason', (
      tester,
    ) async {
      SharedPreferences.setMockInitialValues({});
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        return http.Response('{}', 200);
      });
      var completed = false;
      await http.runWithClient(() async {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: PendingActionCard(
                state: AppState(ApiClient()),
                issueKey: 'SF-2',
                isStory: false,
                action: pendingActionFor(
                  isStory: false,
                  phase: 'test-decision-needed',
                )!,
                question:
                    'Integratietests groen; livecontrole kan pas na merge.',
                onDone: () => completed = true,
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(
          find.text('Integratietests groen; livecontrole kan pas na merge.'),
          findsOneWidget,
        );
        await tester.tap(find.text(choice.key));
        await tester.pumpAndSettle();
        expect(requests, isEmpty);
        await tester.enterText(
          find.byType(TextField),
          'Mijn concrete afweging',
        );
        await tester.pump();
        await tester.tap(find.text(choice.key));
        await tester.pumpAndSettle();
        expect(completed, isTrue);
        final request = requests.single;
        if (choice.value == 'pause') {
          expect(request.url.path, '/api/v1/stories/SF-2/command/pause');
          expect(jsonDecode(request.body)['reason'], 'Mijn concrete afweging');
        } else {
          expect(request.url.path, '/api/v1/subtasks/SF-2/test-decision');
          expect(jsonDecode(request.body), {
            'phase': choice.value,
            'comment': 'Mijn concrete afweging',
          });
        }
      }, () => client);
    });
  }
}
