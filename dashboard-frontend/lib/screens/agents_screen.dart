import 'dart:async';

import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'agent_log_screen.dart';
import 'data_screen.dart';

class AgentsScreen extends StatefulWidget {
  final AppState state;
  const AgentsScreen({super.key, required this.state});

  @override
  State<AgentsScreen> createState() => _AgentsScreenState();
}

class _AgentsScreenState extends State<AgentsScreen> {
  var _showRecent = false;
  Timer? _elapsedTicker;

  @override
  void initState() {
    super.initState();
    // Ververst alleen de op het scherm getoonde looptijd van actieve runs (geen refetch) zodat
    // die doorlopend meetelt i.p.v. alleen bij een refresh/poll.
    _elapsedTicker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _elapsedTicker?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: widget.state,
      title: 'Agents',
      fetch: (api) async {
        final results = await Future.wait([
          api.getJson('/api/v1/agents'),
          api.getJson('/api/v1/assistant/status'),
        ]);
        return {...results[0], 'assistantStatus': results[1]};
      },
      builder: (context, data) {
        final active = asList(data['activeAgentRuns']);
        final recent = asList(data['recentAgentRuns']);
        final assistant = Map<String, dynamic>.from(
          data['assistantStatus'] as Map? ?? {},
        );
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _AssistantStatusPanel(assistant: assistant),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Actief (${active.length})',
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 16,
                    ),
                  ),
                ),
                TextButton(
                  onPressed: () => setState(() => _showRecent = !_showRecent),
                  child: Text(
                    _showRecent ? 'Verberg geschiedenis' : 'Toon geschiedenis',
                  ),
                ),
              ],
            ),
            if (active.isEmpty)
              const EmptyState('Geen actieve agents.')
            else
              ...active.map(_agentTile),
            if (_showRecent) ...[
              const SizedBox(height: 20),
              const SectionTitle('Recent'),
              if (recent.isEmpty)
                const EmptyState('Geen recente runs.')
              else
                ...recent.map(_agentTile),
            ],
          ],
        );
      },
    );
  }

  Widget _agentTile(Map<String, dynamic> run) => Container(
    margin: const EdgeInsets.only(bottom: 6),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(10),
      border: Border.all(color: const Color(0x14000000)),
    ),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => AgentLogScreen(
            state: widget.state,
            agentRunId: text(run['id']),
            storyKey: text(run['storyKey']),
            role: text(run['role']),
          ),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text.rich(
                    TextSpan(
                      children: [
                        TextSpan(
                          text: text(run['storyKey']),
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        TextSpan(
                          text: '  ·  ${text(run['role'])}',
                          style: const TextStyle(color: Colors.black54),
                        ),
                      ],
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '${formatTimestamp(run['startedAt'])}  ·  ${_runDuration(run)}',
                    style: const TextStyle(color: Colors.black45, fontSize: 12),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            StatusBadge.fromPhase(text(run['outcome'], fallback: 'running')),
          ],
        ),
      ),
    ),
  );

  /// Looptijd: voor afgeronde runs de vaste `durationMs`; voor nog actieve runs de doorlopend
  /// bijgewerkte duur sinds `startedAt` (herberekend bij elke `_elapsedTicker`-tick).
  String _runDuration(Map<String, dynamic> run) {
    final endedAt = run['endedAt'];
    if (endedAt != null && text(endedAt) != '-') {
      final durationMs = run['durationMs'];
      final seconds = durationMs is num
          ? (durationMs / 1000).round()
          : int.tryParse(durationMs?.toString() ?? '') ?? 0;
      return formatDuration(seconds);
    }
    final startedAt = DateTime.tryParse(text(run['startedAt']));
    if (startedAt == null) return '-';
    final elapsedSeconds = DateTime.now().difference(startedAt).inSeconds;
    return formatDuration(elapsedSeconds);
  }
}

/// Toont of de Telegram-assistent draait — die is geen agent-run met een story-koppeling (geen
/// `agent_runs`-rij), dus stond hij nooit in de Agents-lijst zelf. Aparte, kleine status-operatie
/// (`assistant.status`, zie docs/ontwerp-bridge-dashboard.md §5).
class _AssistantStatusPanel extends StatelessWidget {
  final Map<String, dynamic> assistant;
  const _AssistantStatusPanel({required this.assistant});

  @override
  Widget build(BuildContext context) {
    final enabled = boolValue(assistant['enabled']);
    final busy = boolValue(assistant['busy']);
    final activeChatCount = number(assistant['activeChatCount']);
    final lastActivityAt = assistant['lastActivityAt'];
    final statusText = !enabled
        ? 'Uitgeschakeld (geen Claude-token)'
        : busy
        ? 'Bezig ($activeChatCount gesprek${activeChatCount == 1 ? '' : 'ken'})'
        : text(lastActivityAt) == '-'
        ? 'Idle'
        : 'Idle sinds ${formatTimestamp(lastActivityAt)}';
    return Panel(
      child: Row(
        children: [
          const Text(
            'Assistent',
            style: TextStyle(fontWeight: FontWeight.w700),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              statusText,
              style: const TextStyle(color: Colors.black54),
            ),
          ),
          StatusBadge(
            !enabled ? 'uit' : (busy ? 'bezig' : 'idle'),
            !enabled
                ? BadgeTone.neutral
                : (busy ? BadgeTone.warn : BadgeTone.good),
          ),
        ],
      ),
    );
  }
}
