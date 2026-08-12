import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/roadmap_screen.dart';

void main() {
  final payload = {
    'customerWeightPercentage': 75,
    'processWeightPercentage': 25,
    'epics': [
      {
        'id': 1,
        'title': 'Betaalinfrastructuur',
        'description': 'Legt de basis.',
        'status': 'planned',
        'customerRank': 2,
        'processRank': 1,
        'roadmapRank': 1,
        'dependencyIds': <int>[],
        'blockedByIds': <int>[],
        'blocksIds': [2],
      },
      {
        'id': 2,
        'title': 'Mobiele betalingen',
        'description': 'Betalen via de app.',
        'status': 'planned',
        'customerRank': 1,
        'processRank': 2,
        'roadmapRank': 2,
        'dependencyIds': [1],
        'blockedByIds': [1],
        'blocksIds': <int>[],
        'rankExplanation': 'Wacht op Betaalinfrastructuur.',
      },
    ],
  };

  testWidgets('toont epics als blokken met ranks en dependency-status', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final client = MockClient(
      (_) async => http.Response(jsonEncode(payload), 200),
    );

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: RoadmapScreen(state: AppState(ApiClient()))),
      );
      await tester.pumpAndSettle();
    }, () => client);

    expect(find.byKey(const Key('roadmap-graph')), findsOneWidget);
    expect(find.text('Betaalinfrastructuur'), findsOneWidget);
    expect(find.text('Mobiele betalingen'), findsOneWidget);
    expect(find.text('Klant 1  ·  Proces 2'), findsOneWidget);
    expect(find.text('Geblokkeerd'), findsOneWidget);
  });

  testWidgets('detaildialoog laat de klant-rank aanpassen', (tester) async {
    tester.view.physicalSize = const Size(1400, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    Map<String, dynamic>? posted;
    final client = MockClient((request) async {
      if (request.method == 'POST') {
        posted = Map<String, dynamic>.from(jsonDecode(request.body) as Map);
        return http.Response('{}', 200);
      }
      return http.Response(jsonEncode(payload), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: RoadmapScreen(state: AppState(ApiClient()))),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('epic-2')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('customer-rank')), '2');
      await tester.tap(find.widgetWithText(FilledButton, 'Opslaan'));
      await tester.pumpAndSettle();
    }, () => client);

    expect(posted?['customerRank'], 2);
    expect((posted?['dependencyIds'] as List).toSet(), {1});
  });
}
