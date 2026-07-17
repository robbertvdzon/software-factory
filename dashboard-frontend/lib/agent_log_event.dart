import 'dart:convert';

/// Hoe een geparste log-regel getoond moet worden (SF-1047). Elke `docker-stdout`/
/// `docker-stderr`-regel is een los JSON-event uit de Claude- (`--output-format
/// stream-json`) of Codex- (`--json`) CLI-stream; we classificeren het event-type zodat
/// het scherm een leesbare samenvatting kan tonen i.p.v. de ruwe, geëscapete JSON.
enum AgentLogEventKind {
  /// Volledig leesbare assistent-tekst (Claude `assistant`-content-block of Codex
  /// `agent_message`/`assistant_message`-item); altijd uitgeklapt getoond.
  assistantText,

  /// Een tool-aanroep of tool-resultaat (Claude `tool_use`/`tool_result`, Codex
  /// `command_execution`/`file_change`/`mcp_tool_call`); standaard ingeklapt.
  toolActivity,

  /// Overig herkend maar minder relevant JSON-event (system-init, result-samenvatting,
  /// turn.completed, reasoning, ...); standaard ingeklapt.
  other,

  /// De regel kon niet als JSON geparsed worden; toon de ruwe tekst zoals voorheen.
  raw,
}

/// Vaste karakterlimiet voor de ingeklapte preview van tool-invoer/uitvoer (implementatiedetail,
/// zie Aannames in de story).
const previewCharLimit = 200;

class AgentLogEvent {
  final bool isStderr;
  final AgentLogEventKind kind;
  final String title;
  final String preview;
  final String fullText;

  const AgentLogEvent({
    required this.isStderr,
    required this.kind,
    required this.title,
    required this.preview,
    required this.fullText,
  });

  bool get isCollapsible => kind == AgentLogEventKind.toolActivity || kind == AgentLogEventKind.other;
}

String _truncate(String value, [int limit = previewCharLimit]) {
  final oneLine = value.replaceAll('\n', ' ').trim();
  if (oneLine.length <= limit) return oneLine;
  return '${oneLine.substring(0, limit)}…';
}

String _encodePretty(dynamic node) => const JsonEncoder.withIndent('  ').convert(node);

/// Haalt leesbare tekst uit een Claude content-blok (`{"type":"text","text":"..."}`) of
/// een Codex tool_result-content (string of lijst van content-blokken).
String _blockText(dynamic value) {
  if (value is String) return value;
  if (value is List) {
    return value.map((block) {
      if (block is Map && block['type'] == 'text') return block['text']?.toString() ?? '';
      return block?.toString() ?? '';
    }).join(' ');
  }
  return value?.toString() ?? '';
}

AgentLogEvent _claudeEvent(Map<String, dynamic> node, bool isStderr, String rawText) {
  final type = node['type']?.toString() ?? 'unknown';
  switch (type) {
    case 'assistant':
    case 'user':
      final content = node['message'] is Map ? node['message']['content'] : null;
      final blocks = content is List ? content : (content == null ? const [] : [content]);
      final textParts = <String>[];
      String? toolName;
      String toolPreview = '';
      for (final block in blocks) {
        if (block is! Map) continue;
        switch (block['type']) {
          case 'text':
            textParts.add(_blockText(block['text']));
          case 'tool_use':
            toolName = block['name']?.toString() ?? 'tool';
            toolPreview = _truncate(_encodePretty(block['input'] ?? {}));
          case 'tool_result':
            toolName = 'tool_result';
            toolPreview = _truncate(_blockText(block['content']));
        }
      }
      if (toolName != null) {
        return AgentLogEvent(
          isStderr: isStderr,
          kind: AgentLogEventKind.toolActivity,
          title: toolName,
          preview: toolPreview,
          fullText: rawText,
        );
      }
      final text = textParts.join('\n').trim();
      return AgentLogEvent(
        isStderr: isStderr,
        kind: AgentLogEventKind.assistantText,
        title: type == 'assistant' ? 'assistent' : 'gebruiker',
        preview: text,
        fullText: text.isEmpty ? rawText : text,
      );
    case 'result':
      final resultText = node['result']?.toString() ?? '';
      return AgentLogEvent(
        isStderr: isStderr,
        kind: AgentLogEventKind.other,
        title: 'resultaat',
        preview: _truncate(resultText),
        fullText: rawText,
      );
    case 'system':
      final subtype = node['subtype']?.toString() ?? '';
      return AgentLogEvent(
        isStderr: isStderr,
        kind: AgentLogEventKind.other,
        title: 'system${subtype.isEmpty ? '' : '/$subtype'}',
        preview: _truncate(_encodePretty(node)),
        fullText: rawText,
      );
    default:
      return AgentLogEvent(
        isStderr: isStderr,
        kind: AgentLogEventKind.other,
        title: type,
        preview: _truncate(_encodePretty(node)),
        fullText: rawText,
      );
  }
}

AgentLogEvent _codexEvent(Map<String, dynamic> node, bool isStderr, String rawText) {
  final type = node['type']?.toString() ?? 'unknown';
  switch (type) {
    case 'item.completed':
    case 'item.started':
    case 'item.updated':
      final item = node['item'] is Map ? node['item'] as Map : const {};
      final itemType = item['type']?.toString() ?? 'unknown';
      switch (itemType) {
        case 'agent_message':
        case 'assistant_message':
          final text = (item['text']?.toString() ?? item['content']?.toString() ?? '').trim();
          return AgentLogEvent(
            isStderr: isStderr,
            kind: AgentLogEventKind.assistantText,
            title: 'assistent',
            preview: text,
            fullText: text.isEmpty ? rawText : text,
          );
        case 'reasoning':
          final text = (item['text']?.toString() ?? '').trim();
          return AgentLogEvent(
            isStderr: isStderr,
            kind: AgentLogEventKind.other,
            title: 'reasoning',
            preview: _truncate(text),
            fullText: rawText,
          );
        case 'command_execution':
          return AgentLogEvent(
            isStderr: isStderr,
            kind: AgentLogEventKind.toolActivity,
            title: 'command',
            preview: _truncate(item['command']?.toString() ?? _encodePretty(item)),
            fullText: rawText,
          );
        case 'file_change':
          return AgentLogEvent(
            isStderr: isStderr,
            kind: AgentLogEventKind.toolActivity,
            title: 'file_change',
            preview: _truncate(_encodePretty(item['changes'] ?? item)),
            fullText: rawText,
          );
        case 'mcp_tool_call':
          return AgentLogEvent(
            isStderr: isStderr,
            kind: AgentLogEventKind.toolActivity,
            title: item['tool']?.toString() ?? 'mcp_tool_call',
            preview: _truncate(_encodePretty(item['arguments'] ?? item)),
            fullText: rawText,
          );
        default:
          return AgentLogEvent(
            isStderr: isStderr,
            kind: AgentLogEventKind.toolActivity,
            title: itemType,
            preview: _truncate(_encodePretty(item)),
            fullText: rawText,
          );
      }
    case 'turn.completed':
    case 'turn.failed':
    case 'error':
      return AgentLogEvent(
        isStderr: isStderr,
        kind: AgentLogEventKind.other,
        title: type,
        preview: _truncate(_encodePretty(node)),
        fullText: rawText,
      );
    default:
      return AgentLogEvent(
        isStderr: isStderr,
        kind: AgentLogEventKind.other,
        title: type,
        preview: _truncate(_encodePretty(node)),
        fullText: rawText,
      );
  }
}

const _claudeTypes = {'assistant', 'user', 'result', 'system'};

/// Parseert één opgeslagen `agent_events`-regel (`kind` + `text`) tot een [AgentLogEvent].
/// Regels die niet als geldige JSON-object te parsen zijn vallen terug op de ruwe tekst
/// (`AgentLogEventKind.raw`) zodat het scherm nooit crasht op onverwachte content.
AgentLogEvent parseAgentLogEvent({required String kind, required String rawText}) {
  final isStderr = kind == 'docker-stderr';
  dynamic decoded;
  try {
    decoded = jsonDecode(rawText);
  } on FormatException {
    decoded = null;
  }
  if (decoded is! Map) {
    return AgentLogEvent(
      isStderr: isStderr,
      kind: AgentLogEventKind.raw,
      title: '',
      preview: '',
      fullText: rawText,
    );
  }
  final node = decoded.cast<String, dynamic>();
  final type = node['type']?.toString() ?? '';
  if (_claudeTypes.contains(type)) {
    return _claudeEvent(node, isStderr, rawText);
  }
  return _codexEvent(node, isStderr, rawText);
}
