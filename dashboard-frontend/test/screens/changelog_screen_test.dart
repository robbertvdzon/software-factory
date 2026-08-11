import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/changelog_screen.dart';

Future<List<String>> _pumpChangelog(
  WidgetTester tester, {
  required String projectName,
  required List<Map<String, dynamic>> entries,
}) async {
  SharedPreferences.setMockInitialValues({});
  final state = AppState(ApiClient());
  final requestedPaths = <String>[];
  final mockClient = MockClient((request) async {
    requestedPaths.add(request.url.toString());
    return http.Response(jsonEncode({'entries': entries, 'errors': <String>[]}), 200);
  });

  await http.runWithClient(() async {
    await tester.pumpWidget(
      MaterialApp(home: ChangelogScreen(state: state, projectName: projectName)),
    );
    await tester.pumpAndSettle();
  }, () => mockClient);
  return requestedPaths;
}

void main() {
  testWidgets('changelog toont de items nieuwste-eerst zoals aangeleverd', (tester) async {
    await _pumpChangelog(
      tester,
      projectName: 'software-factory',
      entries: [
        {'timestamp': '2026-08-10T10:00:00Z', 'shortDescriptionSummary': 'Nieuwste wijziging'},
        {'timestamp': '2026-08-01T10:00:00Z', 'shortDescriptionSummary': 'Oudere wijziging'},
      ],
    );

    expect(find.text('Changelog — software-factory'), findsOneWidget);
    final newest = tester.getTopLeft(find.text('Nieuwste wijziging')).dy;
    final oldest = tester.getTopLeft(find.text('Oudere wijziging')).dy;
    expect(newest, lessThan(oldest));
  });

  testWidgets('lege changelog toont de bestaande lege-staat-melding', (tester) async {
    await _pumpChangelog(tester, projectName: 'software-factory', entries: const []);

    expect(find.text('Nog geen changelog-items voor dit project.'), findsOneWidget);
  });

  testWidgets('projectnaam wordt geëncodeerd in het API-pad', (tester) async {
    final paths = await _pumpChangelog(
      tester,
      projectName: 'Mijn Project & Co',
      entries: const [],
    );

    expect(paths, contains('/api/v1/changelog/Mijn%20Project%20%26%20Co'));
  });

  testWidgets('als root-pagina toont de changelog geen terug-knop', (tester) async {
    await _pumpChangelog(tester, projectName: 'demo', entries: const []);

    expect(find.byType(BackButton), findsNothing);
  });
}
