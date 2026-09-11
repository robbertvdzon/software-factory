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
  testWidgets(
    'Settings-scherm toont een GitHub Actions-knop naast de Versie-sectie',
    (tester) async {
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
              'version': <String, dynamic>{
                'branch': 'main',
                'commitShort': 'abc1234',
              },
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
    },
  );

  testWidgets('AI-uitvoering kan per rol vanuit Settings worden opgeslagen', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1200, 1000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);
    final textScale = TextScalePreference();
    await textScale.load();
    Map<String, dynamic>? saved;

    final mockClient = MockClient((request) async {
      if (request.method == 'GET' &&
          request.url.path.endsWith('/api/v1/settings')) {
        return http.Response(
          jsonEncode({
            'configuration': <String, dynamic>{},
            'version': <String, dynamic>{},
            'agentExecutionConfigurations': [
              {
                'role': 'developer',
                'projectKey': null,
                'vendorId': 'codex',
                'model': 'gpt-5.6-sol',
                'mode': 'HIGH',
              },
            ],
            'agentExecutionOptions': [
              {
                'role': 'developer',
                'vendorId': 'codex',
                'model': 'gpt-5.6-sol',
                'mode': 'HIGH',
                'available': true,
              },
            ],
            'agentExecutionProjects': ['softwarefactory'],
          }),
          200,
        );
      }
      if (request.method == 'POST' &&
          request.url.path.endsWith('/api/v1/settings/agent-execution')) {
        saved = jsonDecode(request.body) as Map<String, dynamic>;
        return http.Response('{}', 200);
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
      final save = find.byKey(
        const ValueKey('agent-execution-save-developer-default'),
      );
      await tester.ensureVisible(save);
      await tester.tap(save);
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(saved, {
      'role': 'developer',
      'projectKey': null,
      'vendorId': 'codex',
      'model': 'gpt-5.6-sol',
      'mode': 'HIGH',
    });
  });

  testWidgets('AI-uitvoering licht nieuwe en lopende agentjobs toe', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(600, 1000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
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
            'version': <String, dynamic>{},
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

    const nextJobMessage =
        'Wijzigingen gelden vanaf de eerstvolgende agentjob.';
    const runningJobsMessage =
        'Reeds lopende agentjobs behouden hun huidige provider, model en mode.';

    expect(find.text(nextJobMessage), findsOneWidget);
    expect(find.text(nextJobMessage).hitTestable(), findsOneWidget);
    expect(find.text(runningJobsMessage), findsOneWidget);
    expect(find.text(runningJobsMessage).hitTestable(), findsOneWidget);
  });
}
