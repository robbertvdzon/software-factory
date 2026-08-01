import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/audit_screen.dart';

void main() {
  /// Alle tips van alle audits in één respons — de pagina filtert client-side op
  /// (project, auditType). [deleted] verzamelt de sleutels die via de delete-endpoint
  /// zijn weggehaald, zodat een herlaadronde de verwijderde tip niet meer teruggeeft.
  MockClient buildClient({required List<String> deleted, List<String>? requestedPaths}) =>
      MockClient((request) async {
        requestedPaths?.add(request.url.path);
        if (request.url.path.endsWith('/api/v1/audit-memory')) {
          final notes = [
            {'project': 'SF', 'auditType': 'security', 'key': 'tip-1', 'content': 'Let op secrets in logs.'},
            {'project': 'SF', 'auditType': 'security', 'key': 'tip-2', 'content': 'Controleer tokenrotatie.'},
            {'project': 'SF', 'auditType': 'docs', 'key': 'andere-audit', 'content': 'Niet tonen.'},
            {'project': 'OTHER', 'auditType': 'security', 'key': 'ander-project', 'content': 'Niet tonen.'},
          ].where((note) => !deleted.contains(note['key'])).toList();
          return http.Response(jsonEncode({'notes': notes, 'errors': <String>[]}), 200);
        }
        if (request.url.path.endsWith('/api/v1/audit-memory/delete')) {
          deleted.add((jsonDecode(request.body) as Map<String, dynamic>)['key'] as String);
          return http.Response(jsonEncode({'ok': true}), 200);
        }
        return http.Response('Not found', 404);
      });

  /// Pompt de pagina en draait [body] binnen dezelfde `runWithClient`-zone: ook interacties
  /// ná het openen (verwijderen, opslaan) doen HTTP-calls en moeten dus de mock zien.
  Future<void> pumpScreen(
    WidgetTester tester,
    MockClient client, [
    Future<void> Function()? body,
  ]) async {
    SharedPreferences.setMockInitialValues({});
    final state = AppState(ApiClient());
    await http.runWithClient(() async {
      await tester.pumpWidget(
        MaterialApp(home: AuditMemoryScreen(state: state, project: 'SF', auditType: 'security')),
      );
      await tester.pumpAndSettle();
      if (body != null) await body();
    }, () => client);
  }

  testWidgets('memory-pagina toont alleen de tips van deze (project, auditType)', (tester) async {
    await pumpScreen(tester, buildClient(deleted: []));

    expect(find.byType(AppBar), findsOneWidget);
    expect(find.text('Memory — security'), findsOneWidget);
    expect(find.text('tip-1'), findsOneWidget);
    expect(find.text('tip-2'), findsOneWidget);
    expect(find.text('andere-audit'), findsNothing);
    expect(find.text('ander-project'), findsNothing);
    // Geen popup meer: de tips staan op een volwaardige pagina.
    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets('zonder tips voor deze audit toont de pagina de lege staat', (tester) async {
    await pumpScreen(tester, buildClient(deleted: ['tip-1', 'tip-2']));

    expect(find.text('Nog geen memory-tips.'), findsOneWidget);
  });

  testWidgets('verwijderen post naar de delete-endpoint en ververst de lijst', (tester) async {
    final paths = <String>[];
    await pumpScreen(tester, buildClient(deleted: [], requestedPaths: paths), () async {
      await tester.tap(find.byIcon(Icons.delete_outline).first);
      await tester.pumpAndSettle();
    });

    expect(paths.where((path) => path.endsWith('/api/v1/audit-memory/delete')), hasLength(1));
    expect(find.text('tip-1'), findsNothing);
    expect(find.text('tip-2'), findsOneWidget);
  });

  testWidgets('de +-knop in de AppBar opent het formulier voor een nieuwe tip', (tester) async {
    await pumpScreen(tester, buildClient(deleted: []), () async {
      await tester.tap(find.byIcon(Icons.add));
      await tester.pumpAndSettle();
    });

    expect(find.text('Nieuwe memory-tip'), findsOneWidget);
  });
}
