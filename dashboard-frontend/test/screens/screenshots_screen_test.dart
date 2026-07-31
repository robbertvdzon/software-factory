import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/screenshots_screen.dart';

Map<String, dynamic> _shot(String id, String name) => {'id': id, 'name': name};

void main() {
  // De screenshot-plaatjes zelf laden in de testbinding nooit (Flutter's test-HttpClient geeft 400),
  // dus elke pagina valt terug op de errorBuilder. Dat is precies wat criterium 8 verwacht en het
  // laat de rest van de viewer (titel, teller, pijlen, swipe) gewoon testbaar.
  Future<void> openViewer(
    WidgetTester tester, {
    required List<Map<String, dynamic>> screenshots,
    required String tapOn,
    required Future<void> Function() interact,
  }) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      return http.Response(jsonEncode({'screenshots': screenshots}), 200);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: ScreenshotsScreen(state: state, storyKey: 'SF-1')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text(tapOn));
      await tester.pumpAndSettle();
      await interact();
    }, () => mockClient);
  }

  final three = [_shot('1', 'alpha'), _shot('2', 'beta'), _shot('3', 'gamma')];

  testWidgets('viewer opent op het aangetikte screenshot en swipet heen en terug', (tester) async {
    await openViewer(
      tester,
      screenshots: three,
      tapOn: 'beta',
      interact: () async {
        // Criterium 1 + 3: opent op kaartje 2, naam en teller horen bij die pagina.
        expect(find.text('beta'), findsOneWidget);
        expect(find.text('2 / 3'), findsOneWidget);

        await tester.drag(find.byType(PageView), const Offset(-500, 0));
        await tester.pumpAndSettle();
        expect(find.text('gamma'), findsOneWidget);
        expect(find.text('3 / 3'), findsOneWidget);

        await tester.drag(find.byType(PageView), const Offset(500, 0));
        await tester.pumpAndSettle();
        expect(find.text('beta'), findsOneWidget);
        expect(find.text('2 / 3'), findsOneWidget);
      },
    );
  });

  testWidgets('pijlknoppen en pijltjestoetsen bladeren en stoppen aan de randen', (tester) async {
    await openViewer(
      tester,
      screenshots: three,
      tapOn: 'alpha',
      interact: () async {
        expect(find.text('1 / 3'), findsOneWidget);

        // Criterium 4: op de eerste pagina is "vorige" uitgeschakeld en doet de linkertoets niets.
        final previous = tester.widget<IconButton>(
          find.widgetWithIcon(IconButton, Icons.chevron_left),
        );
        expect(previous.onPressed, isNull);
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
        await tester.pumpAndSettle();
        expect(find.text('1 / 3'), findsOneWidget);

        // Criterium 5: knop en toets navigeren allebei, en de teller loopt mee.
        await tester.tap(find.widgetWithIcon(IconButton, Icons.chevron_right));
        await tester.pumpAndSettle();
        expect(find.text('beta'), findsOneWidget);
        expect(find.text('2 / 3'), findsOneWidget);

        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        expect(find.text('gamma'), findsOneWidget);
        expect(find.text('3 / 3'), findsOneWidget);

        // Laatste pagina: "volgende" doet niets, geen wrap naar het begin.
        final next = tester.widget<IconButton>(
          find.widgetWithIcon(IconButton, Icons.chevron_right),
        );
        expect(next.onPressed, isNull);
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        expect(find.text('3 / 3'), findsOneWidget);

        await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
        await tester.pumpAndSettle();
        expect(find.text('2 / 3'), findsOneWidget);
      },
    );
  });

  testWidgets('bij een enkel screenshot geen pijlen en geen teller', (tester) async {
    await openViewer(
      tester,
      screenshots: [_shot('1', 'solo')],
      tapOn: 'solo',
      interact: () async {
        // Criterium 6.
        expect(find.text('solo'), findsOneWidget);
        expect(find.text('1 / 1'), findsNothing);
        expect(find.widgetWithIcon(IconButton, Icons.chevron_left), findsNothing);
        expect(find.widgetWithIcon(IconButton, Icons.chevron_right), findsNothing);
        // Criterium 8: de mislukte netwerk-load toont de broken-image-fallback zonder crash.
        expect(find.byIcon(Icons.broken_image_outlined), findsWidgets);
      },
    );
  });
}
