import 'dart:async';

import 'package:flutter/material.dart';

import '../agent_log_event.dart';
import '../api_client.dart';
import '../app_state.dart';
import '../main.dart';
import '../widgets/common.dart';

/// Detailweergave van de log van één agent-run (SF-1038). Bevraagt
/// `GET /api/v1/agents/{agentRunId}/events` (begrensde `docker-stdout`/`docker-stderr`-regels).
/// Voor een nog actieve run wordt periodiek opnieuw geladen en automatisch meegescrold; voor een
/// afgeronde run wordt de log eenmalig geladen zonder verdere updates.
class AgentLogScreen extends StatefulWidget {
  final AppState state;
  final int agentRunId;
  final String storyKey;
  final String role;
  final bool active;

  const AgentLogScreen({
    super.key,
    required this.state,
    required this.agentRunId,
    required this.storyKey,
    required this.role,
    required this.active,
  });

  @override
  State<AgentLogScreen> createState() => _AgentLogScreenState();
}

class _AgentLogScreenState extends State<AgentLogScreen> {
  static const _pollInterval = Duration(seconds: 3);

  final _scrollController = ScrollController();
  Timer? _poller;
  List<Map<String, dynamic>> _lines = [];
  String? _error;
  var _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
    if (widget.active) {
      _poller = Timer.periodic(_pollInterval, (_) => _load());
    }
  }

  @override
  void dispose() {
    _poller?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final data = await widget.state.api.getJson(
        '/api/v1/agents/${widget.agentRunId}/events',
      );
      final lines = asList(data['lines']);
      if (!mounted) return;
      setState(() {
        _lines = lines;
        _loading = false;
        _error = null;
      });
      _scrollToEnd();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('${widget.storyKey} · ${widget.role}')),
      body: Column(
        children: [
          OfflineBanner(state: widget.state),
          Expanded(child: _body()),
        ],
      ),
    );
  }

  Widget _body() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(_error!));
    }
    if (_lines.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: EmptyState('Nog geen log-regels beschikbaar voor deze run.'),
      );
    }
    return Container(
      color: SfColors.bg,
      padding: const EdgeInsets.all(12),
      child: ListView.builder(
        controller: _scrollController,
        itemCount: _lines.length,
        itemBuilder: (context, index) {
          final line = _lines[index];
          final event = parseAgentLogEvent(kind: text(line['kind']), rawText: text(line['text']));
          return _AgentLogEventTile(event: event);
        },
      ),
    );
  }
}

/// Toont één geparsed log-event: assistent-tekst volledig leesbaar, overige
/// events als ingeklapte, uitklapbare samenvatting (toolnaam + preview), en
/// niet-parsebare regels als ruwe tekst (fallback, geen crash).
class _AgentLogEventTile extends StatelessWidget {
  final AgentLogEvent event;
  const _AgentLogEventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final accent = event.isStderr ? SfColors.red : SfColors.ink;

    if (event.kind == AgentLogEventKind.raw) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Text(
          event.fullText,
          style: TextStyle(color: accent, fontFamily: 'monospace', fontSize: 12),
        ),
      );
    }

    if (event.kind == AgentLogEventKind.assistantText) {
      if (event.fullText.trim().isEmpty) return const SizedBox.shrink();
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Text(
          event.fullText,
          style: TextStyle(color: accent, fontSize: 13),
        ),
      );
    }

    return Theme(
      data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
      child: ExpansionTile(
        tilePadding: EdgeInsets.zero,
        childrenPadding: const EdgeInsets.only(left: 12, bottom: 8),
        title: Row(
          children: [
            Text(
              event.title,
              style: TextStyle(color: accent, fontWeight: FontWeight.w600, fontSize: 12),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                event.preview,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: SfColors.muted, fontSize: 12),
              ),
            ),
          ],
        ),
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(color: SfColors.line, borderRadius: BorderRadius.circular(8)),
            child: SelectableText(
              event.fullText,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12, color: SfColors.ink),
            ),
          ),
        ],
      ),
    );
  }
}
