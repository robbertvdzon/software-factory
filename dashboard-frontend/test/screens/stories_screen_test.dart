import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/stories_screen.dart';

/// SF-1776 — het "Nieuwe story"-dialoog moet de meldingen-as voorselecteren op
/// 'Als klaar en gedeployed' en die stand ook als zodanig versturen.
void main() {
  MockClient buildClient(List<Map<String, dynamic>> createBodies) => MockClient((request) async {
    if (request.method == 'GET' && request.url.path.endsWith('/api/v1/stories')) {
      return http.Response(
        jsonEncode({
          'issues': [
            {'key': 'SF-1', 'issueType': 'STORY', 'summary': 'Bestaande story', 'status': 'open', 'fields': {}},
          ],
          'repoNames': ['softwarefactory'],
          'runsByStory': <String, dynamic>{},
          'usageByStory': <String, dynamic>{},
          'mergedStoryKeys': <String>[],
        }),
        200,
      );
    }
    if (request.method == 'POST' && request.url.path.endsWith('/api/v1/stories')) {
      createBodies.add(jsonDecode(request.body) as Map<String, dynamic>);
      return http.Response('{}', 200);
    }
    return http.Response('Not found', 404);
  });

  /// Pompt het scherm, opent het aanmaakdialoog en draait [body] binnen dezelfde
  /// `runWithClient`-zone (de POST bij "Aanmaken" moet de mock ook zien).
  Future<void> openCreateDialog(
    WidgetTester tester,
    MockClient client,
    Future<void> Function() body,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());
    // Het AI-model-dropdownitem ('— automatisch (op AI-niveau) —') is met de testfont-metrieken
    // breder dan het 420px-brede dialoog en veroorzaakt een RenderFlex-overflow. Dat is een
    // bestaand layout-artefact van de testfont, los van deze story; alleen die melding negeren.
    final originalOnError = FlutterError.onError;
    FlutterError.onError = (details) {
      if (details.exceptionAsString().contains('A RenderFlex overflowed')) return;
      originalOnError?.call(details);
    };
    try {
      await http.runWithClient(() async {
        await tester.pumpWidget(MaterialApp(home: StoriesScreen(state: state)));
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Nieuwe story'));
        await tester.pumpAndSettle();
        await body();
      }, () => client);
    } finally {
      FlutterError.onError = originalOnError;
    }
  }

  testWidgets('aanmaakdialoog toont "Als klaar en gedeployed" als voorgeselecteerde meldingen-stand', (tester) async {
    await openCreateDialog(tester, buildClient([]), () async {
      expect(find.text('Nieuwe story'), findsWidgets);
      expect(
        find.widgetWithText(DropdownButtonFormField<String>, 'Als klaar en gedeployed'),
        findsOneWidget,
      );
    });
  });

  testWidgets('aanmaken zonder de meldingen-keuze aan te raken stuurt als-klaar-en-gedeployed mee', (tester) async {
    final bodies = <Map<String, dynamic>>[];
    await openCreateDialog(tester, buildClient(bodies), () async {
      await tester.enterText(find.widgetWithText(TextFormField, 'Titel'), 'Mijn nieuwe story');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Aanmaken'));
      await tester.pumpAndSettle();
    });

    expect(bodies, hasLength(1));
    expect(bodies.single['notifyMode'], 'als-klaar-en-gedeployed');
  });

  testWidgets('een expliciet gekozen "Als klaar" wordt ook echt als als-klaar verstuurd', (tester) async {
    final bodies = <Map<String, dynamic>>[];
    await openCreateDialog(tester, buildClient(bodies), () async {
      await tester.enterText(find.widgetWithText(TextFormField, 'Titel'), 'Story met eigen keuze');
      await tester.pumpAndSettle();

      final dropdown = find.widgetWithText(DropdownButtonFormField<String>, 'Als klaar en gedeployed');
      await tester.ensureVisible(dropdown);
      await tester.pumpAndSettle();
      await tester.tap(dropdown);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Als klaar').last);
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(FilledButton, 'Aanmaken'));
      await tester.pumpAndSettle();
    });

    expect(bodies, hasLength(1));
    expect(bodies.single['notifyMode'], 'als-klaar');
  });
}
