import 'dart:convert';

import 'api_client.dart';

/// JSON-bewuste interpretatie van een `agent_events`-regel (SF-1061).
///
/// Elke regel is doorgaans een complete JSONL-regel geschreven door de Claude-
/// (`--output-format stream-json`) of Codex-CLI (`--json`), zie
/// `ClaudeStreamParser.kt`/`CodexAiClient.kt` (backend, buiten scope hier). Deze
/// pure helper is los van widgets zodat hij zonder flutter_test-harnas
/// unit-testbaar is.
const _previewLimit = 200;

enum AgentLogEventKind { assistantText, toolUse, toolResult, system, raw }

class AgentLogEvent {
  final AgentLogEventKind kind;
  final bool isStderr;

  /// Direct te tonen samenvatting: volledige tekst voor [assistantText]/[raw],
  /// korte preview (toolnaam + preview) voor [toolUse]/[toolResult]/[system].
  final String summary;

  /// Volledige, originele payload — alleen gezet voor initieel ingeklapte
  /// events ([toolUse]/[toolResult]), zodat ze uitklapbaar zijn.
  final String? detail;

  const AgentLogEvent({
    required this.kind,
    required this.summary,
    required this.isStderr,
    this.detail,
  });

  bool get collapsible => detail != null;
}

const _claudeTopLevelTypes = {'assistant', 'user', 'result', 'system'};

/// Parseert één opgeslagen `agent_events`-regel naar één of meer leesbare
/// [AgentLogEvent]s. Regels die niet als (object-)JSON te parsen zijn vallen
/// terug op de ruwe tekst ([AgentLogEventKind.raw]) i.p.v. te crashen.
List<AgentLogEvent> parseAgentLogEvent(Map<String, dynamic> line) {
  final rawText = text(line['text']);
  final isStderr = text(line['kind']) == 'docker-stderr';

  dynamic decoded;
  try {
    decoded = jsonDecode(rawText);
  } catch (_) {
    return [_raw(rawText, isStderr)];
  }
  if (decoded is! Map<String, dynamic>) {
    return [_raw(rawText, isStderr)];
  }

  final type = decoded['type']?.toString();
  if (type == null || type.isEmpty) {
    return [_raw(rawText, isStderr)];
  }

  if (_claudeTopLevelTypes.contains(type)) {
    return _parseClaudeEvent(type, decoded, isStderr, rawText);
  }
  return _parseCodexEvent(type, decoded, isStderr, rawText);
}

AgentLogEvent _raw(String rawText, bool isStderr) =>
    AgentLogEvent(kind: AgentLogEventKind.raw, summary: rawText, isStderr: isStderr);

List<AgentLogEvent> _parseClaudeEvent(String type, Map<String, dynamic> decoded, bool isStderr, String rawText) {
  switch (type) {
    case 'assistant':
    case 'user':
      final blocks = _asList(decoded['message']?['content']);
      if (blocks.isEmpty) {
        return [_systemEvent('$type-bericht', rawText, isStderr)];
      }
      return blocks.map((block) => _claudeContentBlock(block, isStderr, rawText)).toList();
    case 'result':
      final resultText = text(decoded['result']);
      final subtype = text(decoded['subtype'], fallback: 'onbekend');
      final summary = resultText.isNotEmpty ? resultText : 'Resultaat ($subtype)';
      return [AgentLogEvent(kind: AgentLogEventKind.assistantText, summary: summary, isStderr: isStderr)];
    case 'system':
      final subtype = text(decoded['subtype'], fallback: 'init');
      return [_systemEvent('Systeem: $subtype', rawText, isStderr)];
    default:
      return [_systemEvent(type, rawText, isStderr)];
  }
}

AgentLogEvent _claudeContentBlock(Map<String, dynamic> block, bool isStderr, String rawText) {
  final blockType = text(block['type']);
  switch (blockType) {
    case 'text':
      final blockText = text(block['text']);
      return AgentLogEvent(kind: AgentLogEventKind.assistantText, summary: blockText, isStderr: isStderr);
    case 'tool_use':
      final name = text(block['name'], fallback: 'tool');
      final input = block['input'];
      final preview = _preview(input is String ? input : jsonEncode(input ?? {}));
      return AgentLogEvent(
        kind: AgentLogEventKind.toolUse,
        summary: 'Tool-aanroep: $name — $preview',
        isStderr: isStderr,
        detail: const JsonEncoder.withIndent('  ').convert(block),
      );
    case 'tool_result':
      final content = block['content'];
      final preview = _preview(content is String ? content : jsonEncode(content ?? ''));
      return AgentLogEvent(
        kind: AgentLogEventKind.toolResult,
        summary: 'Tool-resultaat: $preview',
        isStderr: isStderr,
        detail: const JsonEncoder.withIndent('  ').convert(block),
      );
    default:
      return _systemEvent(blockType.isEmpty ? 'onderdeel' : blockType, rawText, isStderr);
  }
}

const _codexToolItemTypes = {
  'command_execution',
  'function_call',
  'local_shell_call',
  'mcp_tool_call',
  'tool_call',
};

const _codexToolResultItemTypes = {
  'command_execution_output',
  'function_call_output',
  'local_shell_call_output',
  'mcp_tool_call_output',
  'tool_call_output',
};

List<AgentLogEvent> _parseCodexEvent(String type, Map<String, dynamic> decoded, bool isStderr, String rawText) {
  if (type == 'turn.completed' || type == 'turn.started') {
    return [_systemEvent('Codex: $type', rawText, isStderr)];
  }

  final item = decoded['item'];
  if (item is! Map<String, dynamic>) {
    return [_systemEvent(type, rawText, isStderr)];
  }
  final itemType = text(item['type'], fallback: type);

  if (itemType == 'agent_message' || itemType == 'assistant_message') {
    final itemText = text(item['text'], fallback: text(item['content']));
    return [AgentLogEvent(kind: AgentLogEventKind.assistantText, summary: itemText, isStderr: isStderr)];
  }
  if (_codexToolItemTypes.contains(itemType)) {
    final name = text(item['name'], fallback: text(item['command'], fallback: itemType));
    final input = item['arguments'] ?? item['command'] ?? item['input'];
    final preview = _preview(input is String ? input : jsonEncode(input ?? {}));
    return [
      AgentLogEvent(
        kind: AgentLogEventKind.toolUse,
        summary: 'Tool-aanroep: $name — $preview',
        isStderr: isStderr,
        detail: const JsonEncoder.withIndent('  ').convert(item),
      ),
    ];
  }
  if (_codexToolResultItemTypes.contains(itemType)) {
    final output = item['aggregated_output'] ?? item['output'] ?? item['result'];
    final preview = _preview(output is String ? output : jsonEncode(output ?? ''));
    return [
      AgentLogEvent(
        kind: AgentLogEventKind.toolResult,
        summary: 'Tool-resultaat: $preview',
        isStderr: isStderr,
        detail: const JsonEncoder.withIndent('  ').convert(item),
      ),
    ];
  }
  return [_systemEvent(itemType, rawText, isStderr)];
}

AgentLogEvent _systemEvent(String label, String rawText, bool isStderr) => AgentLogEvent(
      kind: AgentLogEventKind.system,
      summary: label,
      isStderr: isStderr,
      detail: rawText,
    );

List<Map<String, dynamic>> _asList(dynamic value) {
  if (value is! List) return [];
  return value.whereType<Map>().map((item) => Map<String, dynamic>.from(item)).toList();
}

String _preview(String value) {
  final singleLine = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (singleLine.length <= _previewLimit) return singleLine;
  return '${singleLine.substring(0, _previewLimit)}…';
}
