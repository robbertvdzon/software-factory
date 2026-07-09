import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/overview_screens.dart';
import 'package:softwarefactory_dashboard/text_scale_preference.dart';

void main() {
  testWidgets('Settings-scherm toont een GitHub Actions-knop naast de Versie-sectie', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);
    final textScale = TextScalePreference();
    await textScale.load();

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/settings')) {
        return http.Response(
          jsonEncode({
            'configuration': <String, dynamic>{},
            'version': <String, dynamic>{'branch': 'main', 'commitShort': 'abc1234'},
            'nightly': <String, dynamic>{},
          }),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(
          home: SettingsScreen(state: state, textScale: textScale),
        ),
      );
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.text('GitHub Actions'), findsOneWidget);
    expect(find.byIcon(Icons.open_in_new), findsOneWidget);

    final button = find.ancestor(
      of: find.text('GitHub Actions'),
      matching: find.byWidgetPredicate((widget) => widget is FilledButton),
    );
    expect(button, findsOneWidget);
    expect(tester.widget<FilledButton>(button).onPressed, isNotNull);
  });
}
