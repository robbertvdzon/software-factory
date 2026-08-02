import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/stories_screen.dart';

void main() {
  testWidgets(
    'storyoverzicht toont quota-wachtstatus die van een subtaak is afgeleid',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final payload = {
        'issues': [
          {
            'key': 'SF-1',
            'issueType': 'STORY',
            'summary': 'Story met wachtende implementatie',
            'status': 'open',
            'fields': {
              'storyPhase': 'in-progress',
              'repo': 'software-factory',
              'createdAt': '2026-08-02T10:00:00Z',
            },
          },
        ],
        'runsByStory': <String, dynamic>{},
        'usageByStory': <String, dynamic>{},
        'mergedStoryKeys': <String>[],
        'quotaRetryAfterByStory': {'SF-1': '2026-08-02T12:30:00Z'},
      };
      final client = MockClient(
        (request) async => http.Response(jsonEncode(payload), 200),
      );

      await http.runWithClient(() async {
        await tester.pumpWidget(
          MaterialApp(home: StoriesScreen(state: AppState(ApiClient()))),
        );
        await tester.pumpAndSettle();
      }, () => client);

      expect(find.text('quota-wacht'), findsOneWidget);
      expect(
        find.textContaining(
          'Gepauzeerd wegens Claude-quota tot 2026-08-02 12:30',
        ),
        findsOneWidget,
      );
      expect(find.text('blocked'), findsNothing);
    },
  );
}
