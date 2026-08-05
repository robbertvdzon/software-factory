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
    'nieuwe story verstuurt als-klaar-en-gedeployed zonder meldingenkeuze aan te raken',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      tester.view.physicalSize = const Size(1200, 1400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final payload = {
        'issues': [
          {
            'key': 'SF-1',
            'issueType': 'STORY',
            'summary': 'Bestaande story',
            'status': 'open',
            'fields': {'storyPhase': 'todo', 'repo': 'software-factory'},
          },
        ],
        'repoNames': ['software-factory'],
        'runsByStory': <String, dynamic>{},
        'usageByStory': <String, dynamic>{},
        'mergedStoryKeys': <String>[],
        'quotaRetryAfterByStory': <String, dynamic>{},
      };
      Map<String, dynamic>? postedBody;
      final client = MockClient((request) async {
        if (request.method == 'POST') {
          postedBody = Map<String, dynamic>.from(
            jsonDecode(request.body) as Map,
          );
          return http.Response('{}', 200);
        }
        return http.Response(jsonEncode(payload), 200);
      });
      await http.runWithClient(() async {
        await tester.pumpWidget(
          MaterialApp(home: StoriesScreen(state: AppState(ApiClient()))),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.tap(find.byTooltip('Nieuwe story'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);

        expect(find.text('Als klaar en gedeployed'), findsOneWidget);
        await tester.enterText(
          find.widgetWithText(TextFormField, 'Titel'),
          'Nieuwe story',
        );
        await tester.tap(find.widgetWithText(FilledButton, 'Aanmaken'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }, () => client);

      expect(postedBody?['notifyMode'], 'als-klaar-en-gedeployed');
      // SF-1959 — zonder de schakelaar aan te raken is een story nooit een hotfix.
      expect(postedBody?['hotfix'], false);
    },
  );

  testWidgets('hotfix-schakelaar in de aanmaakdialoog stuurt hotfix true mee', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = const Size(1200, 1400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final payload = {
      'issues': [
        {
          'key': 'SF-1',
          'issueType': 'STORY',
          'summary': 'Bestaande story',
          'status': 'open',
          'fields': {'storyPhase': 'todo', 'repo': 'software-factory'},
        },
      ],
      'repoNames': ['software-factory'],
      'runsByStory': <String, dynamic>{},
      'usageByStory': <String, dynamic>{},
      'mergedStoryKeys': <String>[],
      'quotaRetryAfterByStory': <String, dynamic>{},
    };
    Map<String, dynamic>? postedBody;
    final client = MockClient((request) async {
      if (request.method == 'POST') {
        postedBody = Map<String, dynamic>.from(jsonDecode(request.body) as Map);
        return http.Response('{}', 200);
      }
      return http.Response(jsonEncode(payload), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: StoriesScreen(state: AppState(ApiClient()))),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Nieuwe story'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Titel'),
        'Snelle fix',
      );
      final hotfixSwitch = find.byKey(const Key('create-story-hotfix'));
      await tester.ensureVisible(hotfixSwitch);
      await tester.pumpAndSettle();
      await tester.tap(hotfixSwitch);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Aanmaken'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }, () => client);

    expect(postedBody?['title'], 'Snelle fix');
    expect(postedBody?['hotfix'], true);
  });

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
