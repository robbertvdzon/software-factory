import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/agent_log_event.dart';

Map<String, dynamic> _line(String kind, Object payload) => {
      'kind': kind,
      'text': payload is String ? payload : jsonEncode(payload),
    };

void main() {
  group('parseAgentLogEvent - fallback', () {
    test('niet-parsebare regel valt terug op ruwe tekst', () {
      final events = parseAgentLogEvent(_line('docker-stdout', 'geen json hier'));
      expect(events, hasLength(1));
      expect(events.single.kind, AgentLogEventKind.raw);
      expect(events.single.summary, 'geen json hier');
      expect(events.single.collapsible, isFalse);
    });

    test('JSON-array (geen object) valt terug op ruwe tekst', () {
      final events = parseAgentLogEvent(_line('docker-stdout', jsonEncode([1, 2, 3])));
      expect(events.single.kind, AgentLogEventKind.raw);
    });

    test('docker-stderr regel wordt als stderr gemarkeerd', () {
      final events = parseAgentLogEvent(_line('docker-stderr', 'boom'));
      expect(events.single.isStderr, isTrue);
    });
  });

  group('parseAgentLogEvent - Claude schema', () {
    test('assistant-tekstblok is volledig leesbaar zonder JSON-escaping', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'assistant',
        'message': {
          'content': [
            {'type': 'text', 'text': 'Hallo, dit is leesbare tekst.'},
          ],
        },
      }));
      expect(events, hasLength(1));
      expect(events.single.kind, AgentLogEventKind.assistantText);
      expect(events.single.summary, 'Hallo, dit is leesbare tekst.');
      expect(events.single.collapsible, isFalse);
    });

    test('tool_use-blok is standaard ingeklapt met toolnaam + preview, uitklapbaar naar volledige payload', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'assistant',
        'message': {
          'content': [
            {
              'type': 'tool_use',
              'name': 'Read',
              'input': {'file_path': '/tmp/some/very/long/path.txt'},
            },
          ],
        },
      }));
      expect(events, hasLength(1));
      final event = events.single;
      expect(event.kind, AgentLogEventKind.toolUse);
      expect(event.summary, contains('Read'));
      expect(event.collapsible, isTrue);
      expect(event.detail, contains('file_path'));
    });

    test('tool_result-blok is standaard ingeklapt met preview, uitklapbaar naar volledige payload', () {
      final longOutput = 'x' * 500;
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'user',
        'message': {
          'content': [
            {'type': 'tool_result', 'content': longOutput},
          ],
        },
      }));
      final event = events.single;
      expect(event.kind, AgentLogEventKind.toolResult);
      expect(event.summary.length, lessThan(longOutput.length));
      expect(event.collapsible, isTrue);
      expect(event.detail, contains(longOutput));
    });

    test('result-event toont het eindresultaat leesbaar', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'result',
        'subtype': 'success',
        'result': 'Klaar met de taak.',
      }));
      expect(events.single.kind, AgentLogEventKind.assistantText);
      expect(events.single.summary, 'Klaar met de taak.');
    });

    test('system-event blijft zichtbaar als kleine, uitklapbare regel', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'system',
        'subtype': 'init',
      }));
      expect(events.single.kind, AgentLogEventKind.system);
      expect(events.single.collapsible, isTrue);
    });
  });

  group('parseAgentLogEvent - Codex schema', () {
    test('agent_message-item is volledig leesbaar', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'item.completed',
        'item': {'type': 'agent_message', 'text': 'Codex leesbare tekst.'},
      }));
      expect(events.single.kind, AgentLogEventKind.assistantText);
      expect(events.single.summary, 'Codex leesbare tekst.');
    });

    test('command_execution-item is een ingeklapte tool-aanroep', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'item.completed',
        'item': {'type': 'command_execution', 'command': 'ls -la /tmp'},
      }));
      final event = events.single;
      expect(event.kind, AgentLogEventKind.toolUse);
      expect(event.summary, contains('ls -la /tmp'));
      expect(event.collapsible, isTrue);
    });

    test('command_execution_output-item is een ingeklapt tool-resultaat', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'item.completed',
        'item': {'type': 'command_execution_output', 'aggregated_output': 'bestand.txt\n'},
      }));
      final event = events.single;
      expect(event.kind, AgentLogEventKind.toolResult);
      expect(event.collapsible, isTrue);
    });

    test('turn.completed valt terug op een kort system-event', () {
      final events = parseAgentLogEvent(_line('docker-stdout', {
        'type': 'turn.completed',
        'usage': {'input_tokens': 10},
      }));
      expect(events.single.kind, AgentLogEventKind.system);
    });
  });
}
