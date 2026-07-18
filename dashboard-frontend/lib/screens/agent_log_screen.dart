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
  var _isFirstLoad = true;
  final _expanded = <int>{};

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
      final shouldScrollToEnd = _isFirstLoad || _isNearBottom();
      setState(() {
        _lines = lines;
        _loading = false;
        _error = null;
      });
      _isFirstLoad = false;
      if (shouldScrollToEnd) {
        _scrollToEnd();
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  /// Bepaalt vóór het verwerken van nieuwe regels of de gebruiker al (ongeveer)
  /// onderaan stond, met een kleine pixel-tolerantie t.o.v. maxScrollExtent zodat
  /// kleine renderingsverschillen niet leiden tot onterecht wel/niet auto-scrollen.
  static const _bottomTolerance = 40.0;

  bool _isNearBottom() {
    if (!_scrollController.hasClients) return true;
    final position = _scrollController.position;
    return position.maxScrollExtent - position.pixels <= _bottomTolerance;
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
    final events = _lines.expand(parseAgentLogEvent).toList();
    return Container(
      color: SfColors.bg,
      padding: const EdgeInsets.all(12),
      child: Scrollbar(
        controller: _scrollController,
        thumbVisibility: true,
        child: ListView.builder(
          controller: _scrollController,
          itemCount: events.length,
          itemBuilder: (context, index) => _eventTile(index, events[index]),
        ),
      ),
    );
  }

  Widget _eventTile(int index, AgentLogEvent event) {
    final color = event.isStderr ? SfColors.red : SfColors.ink;
    if (!event.collapsible) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Text(
          event.summary,
          style: TextStyle(color: color, fontFamily: 'monospace', fontSize: 12),
        ),
      );
    }

    final isExpanded = _expanded.contains(index);
    final background = event.isStderr ? SfColors.redSoft : SfColors.accentSoft;
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 2),
      decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(8)),
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: () {
          setState(() {
            if (isExpanded) {
              _expanded.remove(index);
            } else {
              _expanded.add(index);
            }
          });
        },
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(isExpanded ? Icons.expand_less : Icons.expand_more, size: 16, color: color),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      event.summary,
                      style: TextStyle(color: color, fontFamily: 'monospace', fontSize: 12),
                    ),
                  ),
                ],
              ),
              if (isExpanded)
                Padding(
                  padding: const EdgeInsets.only(top: 8, left: 20),
                  child: SelectableText(
                    event.detail ?? '',
                    style: TextStyle(color: color, fontFamily: 'monospace', fontSize: 11),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
