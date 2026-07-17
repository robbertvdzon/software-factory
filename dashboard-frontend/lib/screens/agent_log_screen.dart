import 'dart:async';

import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
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
      color: Colors.black,
      padding: const EdgeInsets.all(12),
      child: ListView.builder(
        controller: _scrollController,
        itemCount: _lines.length,
        itemBuilder: (context, index) {
          final line = _lines[index];
          final isStderr = text(line['kind']) == 'docker-stderr';
          return Text(
            text(line['text']),
            style: TextStyle(
              color: isStderr ? Colors.redAccent : Colors.greenAccent,
              fontFamily: 'monospace',
              fontSize: 12,
            ),
          );
        },
      ),
    );
  }
}
