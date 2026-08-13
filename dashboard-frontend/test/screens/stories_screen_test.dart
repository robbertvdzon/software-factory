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
  test('melding-presets vertalen exact naar concrete eventsets', () {
    expect(notificationEventsForPreset('Alleen als ik nodig ben'), {
      'QUESTION',
      'MANUAL_ACTION_REQUIRED',
      'ERROR',
    });
    expect(notificationEventsForPreset('Als deployed'), {
      'DEPLOYED',
      'QUESTION',
      'MANUAL_ACTION_REQUIRED',
      'ERROR',
    });
    expect(notificationEventsForPreset('Na elke stap'), {
      'QUESTION',
      'APPROVAL_REQUIRED',
      'MANUAL_ACTION_REQUIRED',
      'QUOTA_WAIT',
      'ERROR',
      'STEP_COMPLETED',
      'WORKFLOW_COMPLETED',
      'DEPLOYED',
    });
  });

  testWidgets(
    'nieuwe story verstuurt standaard de concrete Als deployed-eventset',
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

        expect(find.text('Als deployed'), findsOneWidget);
        final approvalDropdown = find.widgetWithText(
          DropdownButtonFormField<String>,
          'Automatisch',
        );
        await tester.ensureVisible(approvalDropdown);
        await tester.tap(approvalDropdown);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Elke stap').last);
        await tester.pumpAndSettle();
        expect(
          find.byKey(const Key('approval-notification-warning')),
          findsOneWidget,
        );
        await tester.enterText(
          find.widgetWithText(TextFormField, 'Titel'),
          'Nieuwe story',
        );
        await tester.tap(find.widgetWithText(FilledButton, 'Aanmaken'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }, () => client);

      expect(postedBody?.containsKey('notifyMode'), false);
      expect((postedBody?['notificationEvents'] as List).toSet(), {
        'DEPLOYED',
        'QUESTION',
        'MANUAL_ACTION_REQUIRED',
        'ERROR',
      });
      expect(postedBody?['approvalMode'], 'elke-stap');
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

  // SF-2137 — het overzicht sorteert aflopend op aanmaakmoment; de storynummers staan hier bewust
  // in een andere volgorde dan de createdAt-waarden, zodat de test niet vals-groen kan zijn met de
  // oude sortering op storynummer.
  testWidgets('storyoverzicht sorteert aflopend op createdAt', (tester) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = const Size(1200, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final payload = _storiesPayload([
      _story('SF-10', 'Oudste behouden', createdAt: '2026-08-01T09:00:00Z'),
      _story('SF-30', 'Middelste weggefilterd', createdAt: '2026-08-02T09:00:00Z'),
      _story('SF-20', 'Nieuwste behouden', createdAt: '2026-08-03T09:00:00Z'),
    ]);
    final client = MockClient(
      (request) async => http.Response(jsonEncode(payload), 200),
    );

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: StoriesScreen(state: AppState(ApiClient()))),
      );
      await tester.pumpAndSettle();
      expect(_shownStoryKeys(tester), ['SF-20', 'SF-30', 'SF-10']);

      // Filteren mag alleen regels weglaten, niet de onderlinge volgorde veranderen.
      await tester.enterText(
        find.widgetWithText(TextField, 'Zoek in story-titel of storynummer'),
        'behouden',
      );
      await tester.pumpAndSettle();
      expect(_shownStoryKeys(tester), ['SF-20', 'SF-10']);
    }, () => client);
  });

  // SF-2137 — terugvalgedrag: een onbruikbaar createdAt mag geen exception geven en hoort onderaan.
  testWidgets(
    'stories zonder bruikbaar createdAt staan onderaan op storynummer',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      tester.view.physicalSize = const Size(1200, 1600);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final payload = _storiesPayload([
        _story('SF-1', 'Zonder createdAt-veld'),
        _story('SF-2', 'Leeg createdAt', createdAt: ''),
        _story('SF-3', 'Onparseerbaar createdAt', createdAt: 'gisteren'),
        _story('SF-4', 'Oud maar geldig', createdAt: '2020-01-01T00:00:00Z'),
      ]);
      final client = MockClient(
        (request) async => http.Response(jsonEncode(payload), 200),
      );

      await http.runWithClient(() async {
        await tester.pumpWidget(
          MaterialApp(home: StoriesScreen(state: AppState(ApiClient()))),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(_shownStoryKeys(tester), ['SF-4', 'SF-3', 'SF-2', 'SF-1']);
      }, () => client);
    },
  );

  // SF-2137 — een gelijk createdAt moet deterministisch aflopend op storynummer uitkomen
  // (`List.sort` in Dart is niet gegarandeerd stabiel).
  testWidgets('gelijk createdAt valt terug op storynummer aflopend', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = const Size(1200, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final payload = _storiesPayload([
      _story('SF-5', 'Eerste', createdAt: '2026-08-01T09:00:00Z'),
      _story('SF-7', 'Tweede', createdAt: '2026-08-01T09:00:00Z'),
      _story('SF-6', 'Derde', createdAt: '2026-08-01T09:00:00Z'),
    ]);
    final client = MockClient(
      (request) async => http.Response(jsonEncode(payload), 200),
    );

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: StoriesScreen(state: AppState(ApiClient()))),
      );
      await tester.pumpAndSettle();
      expect(_shownStoryKeys(tester), ['SF-7', 'SF-6', 'SF-5']);
    }, () => client);
  });
}

/// Story-issue zoals `/api/v1/stories` het levert. `createdAt` blijft weg als het niet is
/// meegegeven, zodat de terugval op een ontbrekend veld getest kan worden.
Map<String, dynamic> _story(String key, String summary, {String? createdAt}) => {
  'key': key,
  'issueType': 'STORY',
  'summary': summary,
  'status': 'open',
  'fields': {
    'storyPhase': 'todo',
    'repo': 'software-factory',
    if (createdAt != null) 'createdAt': createdAt,
  },
};

Map<String, dynamic> _storiesPayload(List<Map<String, dynamic>> issues) => {
  'issues': issues,
  'repoNames': ['software-factory'],
  'runsByStory': <String, dynamic>{},
  'usageByStory': <String, dynamic>{},
  'mergedStoryKeys': <String>[],
  'quotaRetryAfterByStory': <String, dynamic>{},
};

/// De storysleutels in de volgorde waarin het scherm ze rendert (widget-boom is depth-first, dus
/// dat is de rijvolgorde van boven naar beneden).
List<String> _shownStoryKeys(WidgetTester tester) {
  final keyPattern = RegExp(r'^SF-\d+$');
  return tester
      .widgetList<Text>(find.byType(Text))
      .map((t) => t.data ?? '')
      .where(keyPattern.hasMatch)
      .toList();
}
