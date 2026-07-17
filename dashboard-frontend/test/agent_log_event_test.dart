import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/agent_log_event.dart';

void main() {
  group('Claude-events', () {
    test('assistent-tekst wordt volledig leesbaar getoond', () {
      final raw = jsonEncode({
        'type': 'assistant',
        'message': {
          'role': 'assistant',
          'content': [
            {'type': 'text', 'text': 'Hallo, dit is leesbare tekst.'},
          ],
        },
      });

      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: raw);

      expect(event.kind, AgentLogEventKind.assistantText);
      expect(event.fullText, 'Hallo, dit is leesbare tekst.');
      expect(event.isCollapsible, isFalse);
      expect(event.isStderr, isFalse);
    });

    test('tool_use wordt een ingeklapte tool-call met naam en preview', () {
      final raw = jsonEncode({
        'type': 'assistant',
        'message': {
          'content': [
            {
              'type': 'tool_use',
              'name': 'Read',
              'input': {'file_path': '/tmp/foo.txt'},
            },
          ],
        },
      });

      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: raw);

      expect(event.kind, AgentLogEventKind.toolActivity);
      expect(event.title, 'Read');
      expect(event.preview, contains('foo.txt'));
      expect(event.isCollapsible, isTrue);
      expect(event.fullText, raw);
    });

    test('tool_result wordt herkend en de volledige payload blijft opvraagbaar', () {
      final raw = jsonEncode({
        'type': 'user',
        'message': {
          'content': [
            {
              'type': 'tool_result',
              'tool_use_id': 'abc',
              'content': 'de volledige inhoud van het bestand' * 10,
            },
          ],
        },
      });

      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: raw);

      expect(event.kind, AgentLogEventKind.toolActivity);
      expect(event.title, 'tool_result');
      expect(event.preview.length, lessThanOrEqualTo(previewCharLimit + 1));
      expect(event.fullText, raw);
    });

    test('result-event geeft een korte samenvatting', () {
      final raw = jsonEncode({'type': 'result', 'subtype': 'success', 'result': 'Klaar.'});

      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: raw);

      expect(event.kind, AgentLogEventKind.other);
      expect(event.preview, 'Klaar.');
    });
  });

  group('Codex-events', () {
    test('agent_message-item wordt volledig leesbare assistent-tekst', () {
      final raw = jsonEncode({
        'type': 'item.completed',
        'item': {'type': 'agent_message', 'text': 'Codex zegt hallo.'},
      });

      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: raw);

      expect(event.kind, AgentLogEventKind.assistantText);
      expect(event.fullText, 'Codex zegt hallo.');
    });

    test('command_execution-item wordt een ingeklapte tool-activiteit', () {
      final raw = jsonEncode({
        'type': 'item.completed',
        'item': {
          'type': 'command_execution',
          'command': 'ls -la /work/repo',
          'exit_code': 0,
        },
      });

      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: raw);

      expect(event.kind, AgentLogEventKind.toolActivity);
      expect(event.title, 'command');
      expect(event.preview, contains('ls -la'));
      expect(event.fullText, raw);
    });

    test('turn.completed wordt herkend als overig event', () {
      final raw = jsonEncode({
        'type': 'turn.completed',
        'usage': {'input_tokens': 10, 'output_tokens': 5},
      });

      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: raw);

      expect(event.kind, AgentLogEventKind.other);
      expect(event.isCollapsible, isTrue);
    });
  });

  group('Fallback', () {
    test('niet-JSON tekst valt terug op de ruwe regel zonder crash', () {
      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: 'gewone tekst zonder JSON');

      expect(event.kind, AgentLogEventKind.raw);
      expect(event.fullText, 'gewone tekst zonder JSON');
    });

    test('JSON-array (geen object) valt ook terug op ruwe tekst', () {
      final event = parseAgentLogEvent(kind: 'docker-stdout', rawText: '[1,2,3]');

      expect(event.kind, AgentLogEventKind.raw);
      expect(event.fullText, '[1,2,3]');
    });

    test('docker-stderr wordt gemarkeerd als stderr', () {
      final event = parseAgentLogEvent(kind: 'docker-stderr', rawText: 'foutmelding');

      expect(event.isStderr, isTrue);
    });
  });
}
