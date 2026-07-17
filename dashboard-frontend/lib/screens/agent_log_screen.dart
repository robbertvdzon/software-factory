import 'dart:async';

import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';

/// Detailweergave van een agent-run (SF-1010): toont de gecapturede docker-stdout/stderr-log
/// (`GET /api/v1/agents/{agentRunId}/log`). Pollt periodiek zolang de run nog geen outcome heeft
/// (`ended == false`); stopt vanzelf zodra de run afgerond is.
class AgentLogScreen extends StatefulWidget {
  final AppState state;
  final String agentRunId;
  final String storyKey;
  final String role;

  const AgentLogScreen({
    super.key,
    required this.state,
    required this.agentRunId,
    required this.storyKey,
    required this.role,
  });

  @override
  State<AgentLogScreen> createState() => _AgentLogScreenState();
}

class _AgentLogScreenState extends State<AgentLogScreen> {
  static const _pollInterval = Duration(seconds: 3);

  Map<String, dynamic>? _data;
  String? _error;
  bool _loading = true;
  Timer? _timer;
  final _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(_pollInterval, (_) => _load());
  }

  Future<void> _load() async {
    try {
      final result = await widget.state.api.getJson(
        '/api/v1/agents/${widget.agentRunId}/log',
      );
      if (!mounted) return;
      setState(() {
        _data = result;
        _loading = false;
        _error = null;
      });
      _scrollToBottom();
      if (boolValue(result['ended'])) {
        _timer?.cancel();
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final lines = asList(_data?['lines']);
    final ended = boolValue(_data?['ended']);
    return Scaffold(
      appBar: AppBar(
        title: Text('${widget.storyKey} · ${widget.role}'),
        actions: [
          if (_data != null && !ended)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Center(
                child: SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            ),
        ],
      ),
      body: Column(
        children: [
          OfflineBanner(state: widget.state),
          Expanded(child: _body(lines)),
        ],
      ),
    );
  }

  Widget _body(List<Map<String, dynamic>> lines) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(_error!));
    }
    if (lines.isEmpty) {
      return const EmptyState('Nog geen log-events voor deze agent-run.');
    }
    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.all(12),
      itemCount: lines.length,
      itemBuilder: (context, index) {
        final line = lines[index];
        final isError = text(line['kind']) == 'docker-stderr';
        return Text(
          text(line['line']),
          style: TextStyle(
            fontFamily: 'monospace',
            fontSize: 12,
            color: isError ? Colors.red.shade700 : Colors.black87,
          ),
        );
      },
    );
  }
}
