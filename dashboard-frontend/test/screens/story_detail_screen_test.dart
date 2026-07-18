import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/story_detail_screen.dart';

Map<String, dynamic> _storyPayload({
  required String description,
  required String aiSupplier,
  required String aiModel,
}) => {
  'issue': {
    'key': 'SF-1',
    'issueType': 'STORY',
    'summary': 'Test story',
    'status': 'open',
    'description': description,
    'fields': {
      'storyPhase': 'in-progress',
      'aiSupplier': aiSupplier,
      'aiModel': aiModel,
    },
    'comments': <Map<String, dynamic>>[],
  },
  'run': <String, dynamic>{},
  'subtasks': <Map<String, dynamic>>[],
  'agentQuestions': <String, dynamic>{},
  'allAgentRuns': <Map<String, dynamic>>[],
};

void main() {
  testWidgets('Omschrijving en AI-model zijn selecteerbaar en AI-model staat in Details', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.method == 'GET' && request.url.path.endsWith('/api/v1/stories/SF-1')) {
        return http.Response(
          jsonEncode(_storyPayload(description: 'Oorspronkelijke omschrijving', aiSupplier: 'claude', aiModel: 'claude-sonnet-5')),
          200,
        );
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: StoryDetailScreen(state: state, storyKey: 'SF-1')));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.byType(SelectableText).evaluate().any((e) => (e.widget as SelectableText).data == 'Oorspronkelijke omschrijving'), true);
    expect(find.text('claude-sonnet-5'), findsOneWidget);
  });

  testWidgets('Omschrijving bewerken slaat op en toont de nieuwe tekst', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    var description = 'Oorspronkelijke omschrijving';
    var editCalls = 0;

    final mockClient = MockClient((request) async {
      if (request.method == 'GET' && request.url.path.endsWith('/api/v1/stories/SF-1')) {
        return http.Response(jsonEncode(_storyPayload(description: description, aiSupplier: 'claude', aiModel: 'claude-sonnet-5')), 200);
      }
      if (request.method == 'POST' && request.url.path.endsWith('/api/v1/stories/SF-1/edit')) {
        editCalls++;
        final body = jsonDecode(request.body) as Map<String, dynamic>;
        description = body['description'] as String;
        return http.Response('{}', 200);
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: StoryDetailScreen(state: state, storyKey: 'SF-1')));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithIcon(IconButton, Icons.edit).first);
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).first, 'Nieuwe omschrijving');
      await tester.tap(find.text('Opslaan'));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(editCalls, 1);
    expect(description, 'Nieuwe omschrijving');
    expect(find.byType(SelectableText).evaluate().any((e) => (e.widget as SelectableText).data == 'Nieuwe omschrijving'), true);
  });

  testWidgets('Mislukte edit-actie toont een foutmelding en laat de data ongewijzigd', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.method == 'GET' && request.url.path.endsWith('/api/v1/stories/SF-1')) {
        return http.Response(
          jsonEncode(_storyPayload(description: 'Oorspronkelijke omschrijving', aiSupplier: 'claude', aiModel: 'claude-sonnet-5')),
          200,
        );
      }
      if (request.method == 'POST' && request.url.path.endsWith('/api/v1/stories/SF-1/edit')) {
        return http.Response(jsonEncode({'message': 'kapot'}), 500);
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: StoryDetailScreen(state: state, storyKey: 'SF-1')));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithIcon(IconButton, Icons.edit).first);
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).first, 'Nieuwe omschrijving');
      await tester.tap(find.text('Opslaan'));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(find.byType(SnackBar), findsOneWidget);
    expect(find.byType(SelectableText).evaluate().any((e) => (e.widget as SelectableText).data == 'Oorspronkelijke omschrijving'), true);
  });

  testWidgets('AI-velden bewerken slaat supplier en model op', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    var aiSupplier = 'claude';
    var aiModel = 'claude-sonnet-5';
    Map<String, dynamic>? lastEditBody;

    final mockClient = MockClient((request) async {
      if (request.method == 'GET' && request.url.path.endsWith('/api/v1/stories/SF-1')) {
        return http.Response(jsonEncode(_storyPayload(description: 'Omschrijving', aiSupplier: aiSupplier, aiModel: aiModel)), 200);
      }
      if (request.method == 'POST' && request.url.path.endsWith('/api/v1/stories/SF-1/edit')) {
        lastEditBody = jsonDecode(request.body) as Map<String, dynamic>;
        if (lastEditBody!['aiSupplier'] != null) aiSupplier = lastEditBody!['aiSupplier'] as String;
        if (lastEditBody!['aiModel'] != null) aiModel = lastEditBody!['aiModel'] as String;
        return http.Response('{}', 200);
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: StoryDetailScreen(state: state, storyKey: 'SF-1')));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithIcon(IconButton, Icons.edit).last);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Opslaan'));
      await tester.pumpAndSettle();
    }, () => mockClient);

    expect(lastEditBody?['aiSupplier'], 'claude');
    expect(lastEditBody?['aiModel'], 'claude-sonnet-5');
  });
}
