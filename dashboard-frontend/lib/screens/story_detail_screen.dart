import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../main.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'screenshots_screen.dart';

/// Stagevolgorde van de subtaak-keten (zie core/SubtaskPhase.kt): development → review →
/// test → summary → documentation → manual-approve → merge → deploy (§9 keten-visualisatie).
const _chainStages = [
  ('development', 'Dev'),
  ('review', 'Review'),
  ('test', 'Test'),
  ('summary', 'Summary'),
  ('documentation', 'Docs'),
  ('manual', 'Approve'),
  ('merge', 'Merge'),
  ('deploy', 'Deploy'),
];

/// Commando's uit core/TrackerModels.kt FactoryCommand; destructief/onomkeerbaar (§8 fase D)
/// vraagt een bevestigingsdialoog vóór het versturen.
const _commands = [
  ('approve', 'Approve', false),
  ('reject', 'Reject', false),
  ('pause', 'Pause', false),
  ('resume', 'Resume', false),
  ('clear-error', 'Clear error', false),
  ('retry-current-step', 'Retry step', false),
  ('merge', 'Merge', false),
  ('re-implement', 'Re-implement', true),
  ('kill', 'Kill', true),
  ('delete', 'Delete', true),
];

/// Fasen die eindigen op `-with-questions` → rolnaam, voor het label van het vraag-paneel.
/// 1-op-1 met StoryPhase/SubtaskPhase (Kotlin) en ActionCards' per-fase answerCard-labels — geen
/// generiek "Vraag van de agent" meer, en alleen tonen als de fase dit ook echt aangeeft (anders
/// toonde de Flutter-app elke laatste agent-opmerking alsof het een vraag was, zie SF-663-feedback).
const _questionRoles = {
  'refined-with-questions': 'refiner',
  'planned-with-questions': 'planner',
  'developed-with-questions': 'developer',
  'reviewed-with-questions': 'reviewer',
  'tested-with-questions': 'tester',
  'summary-with-questions': 'summarizer',
  'documentation-with-questions': 'documenter',
};

/// Story/subtask heeft een fout — zelfde regel als StoryStatusPresenter.realStatus (Kotlin):
/// het eigen error-veld ÓF dat van een van de subtaken.
bool _hasError(Map<String, dynamic> fields, List<Map<String, dynamic>> subtasks) =>
    text(fields['error']).isNotEmpty ||
    subtasks.any((s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['error']).isNotEmpty);

class StoryDetailScreen extends StatefulWidget {
  final AppState state;
  final String storyKey;
  const StoryDetailScreen({super.key, required this.state, required this.storyKey});

  @override
  State<StoryDetailScreen> createState() => _StoryDetailScreenState();
}

class _StoryDetailScreenState extends State<StoryDetailScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  var _busy = false;

  Future<void> _runAction(Future<void> Function() action, {required String successMessage}) async {
    setState(() => _busy = true);
    try {
      await action();
      if (!mounted) return;
      showActionResult(context, success: true, message: successMessage);
      await _dataScreenKey.currentState?.reload();
    } catch (e) {
      if (!mounted) return;
      showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _command(String command, bool destructive) async {
    if (destructive) {
      final confirmed = await confirmDestructive(
        context,
        title: '$command bevestigen',
        message: 'Weet je zeker dat je "$command" wilt uitvoeren op ${widget.storyKey}? Dit kan niet ongedaan gemaakt worden.',
        confirmLabel: command,
      );
      if (!confirmed) return;
    }
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/command/$command'),
      successMessage: '$command uitgevoerd.',
    );
  }

  Future<void> _purge() async {
    final confirmed = await confirmDestructive(
      context,
      title: 'Story purgen',
      message:
          'Dit verwijdert ${widget.storyKey} volledig (issue, subtaken, branch en workspace). Dit kan niet ongedaan gemaakt worden.',
      confirmLabel: 'Purge',
    );
    if (!confirmed) return;
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/purge'),
      successMessage: '${widget.storyKey} gepurged.',
    );
    if (mounted) Navigator.of(context).pop();
  }

  Future<void> _openWorkspace() async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/open-workspace'),
      successMessage: 'Workspace geopend in IntelliJ.',
    );
  }

  Future<void> _toggleAutoApprove(bool enabled) async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/auto-approve', {'enabled': enabled}),
      successMessage: enabled ? 'Auto-approve ingeschakeld.' : 'Auto-approve uitgeschakeld.',
    );
  }

  Future<void> _startRefining() async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/start-refining'),
      successMessage: 'Refining gestart.',
    );
  }

  Future<void> _startDeveloping() async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/start-developing'),
      successMessage: 'Developing gestart.',
    );
  }

  Future<void> _open(String url) async {
    if (url.isEmpty) return;
    await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: widget.storyKey,
      fetch: (api) => api.getJson('/api/v1/stories/${widget.storyKey}'),
      builder: (context, data) {
        final issue = Map<String, dynamic>.from(data['issue'] as Map? ?? {});
        final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
        final run = Map<String, dynamic>.from(data['run'] as Map? ?? {});
        final subtasks = asList(data['subtasks']);
        final agentQuestions = Map<String, dynamic>.from(data['agentQuestions'] as Map? ?? {});
        final myQuestion = text(agentQuestions[widget.storyKey]);
        final isStory = text(issue['issueType']) == 'STORY';
        final currentPhase = text(isStory ? fields['storyPhase'] : fields['subtaskPhase']);
        final showQuestion = currentPhase.endsWith('-with-questions') && myQuestion.isNotEmpty;
        final questionLabel = 'Vraag van de ${_questionRoles[currentPhase] ?? 'agent'}';
        final showStartRefining = isStory && text(fields['storyPhase']).isEmpty;
        final showStartDeveloping = isStory &&
            text(fields['storyPhase']) == 'planning-approved' &&
            subtasks.isNotEmpty &&
            subtasks.every((s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['subtaskPhase']).isEmpty);
        final hasError = _hasError(fields, subtasks);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(text(issue['summary']), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                StatusBadge.fromPhase(text(fields['storyPhase'], fallback: '-')),
                if (boolValue(fields['paused'])) const StatusBadge('paused', BadgeTone.warn),
                if (hasError) const StatusBadge('blocked', BadgeTone.bad),
              ],
            ),
            if (hasError) ...[
              const SizedBox(height: 12),
              ErrorBanner(
                text(fields['error']).isNotEmpty
                    ? text(fields['error'])
                    : subtasks
                        .map((s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['error']))
                        .firstWhere((e) => e.isNotEmpty, orElse: () => 'Onbekende fout.'),
              ),
            ],
            if (showQuestion) ...[
              const SizedBox(height: 12),
              Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(questionLabel, style: const TextStyle(fontWeight: FontWeight.w800)),
                    const SizedBox(height: 6),
                    Text(myQuestion),
                  ],
                ),
              ),
            ],
            if (showStartRefining || showStartDeveloping) ...[
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: _busy ? null : (showStartRefining ? _startRefining : _startDeveloping),
                icon: const Icon(Icons.play_arrow),
                label: Text(showStartRefining ? 'Start refining' : 'Start developing'),
              ),
            ],
            const SizedBox(height: 20),
            const SectionTitle('Keten'),
            _ChainVisualization(subtasks: subtasks),
            const SizedBox(height: 16),
            _ActionsMenuButton(
              busy: _busy,
              onCommand: _command,
              onOpenWorkspace: _busy ? null : _openWorkspace,
              onPurge: _busy ? null : _purge,
              onOpenBriefing: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => BriefingScreen(state: widget.state, storyKey: widget.storyKey)),
              ),
              onOpenScreenshots: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => ScreenshotsScreen(state: widget.state, storyKey: widget.storyKey)),
              ),
              onOpenLink: _open,
              youTrackUrl: text(data['youTrackUrl']),
              prUrl: text(run['prUrl']),
              prNumber: text(run['prNumber']),
              previewUrl: text(data['previewUrl'], fallback: text(run['previewUrl'])),
            ),
            if (subtasks.isNotEmpty) ...[
              const SizedBox(height: 20),
              const SectionTitle('Subtaken'),
              _SubtasksPanel(state: widget.state, subtasks: subtasks),
            ],
            if (text(issue['description']).isNotEmpty) ...[
              const SizedBox(height: 20),
              const SectionTitle('Omschrijving'),
              Panel(child: Text(text(issue['description']))),
            ],
            const SizedBox(height: 20),
            const SectionTitle('Details'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Auto-approve'),
                    value: boolValue(fields['autoApprove']),
                    onChanged: _busy ? null : _toggleAutoApprove,
                  ),
                  const Divider(),
                  _KeyValueList({
                    'Target repo': text(fields['targetRepo'], fallback: '-'),
                    'AI-supplier': text(fields['aiSupplier'], fallback: '-'),
                    'AI-level': text(fields['aiLevel'], fallback: '-'),
                    'Started': formatTimestamp(run['startedAt']),
                    'Ended': formatTimestamp(run['endedAt']),
                    'Agent-runs': '${asList(data['agentRuns']).length}',
                    'Tokens in/uit': '${number(run['totalInputTokens'])} / ${number(run['totalOutputTokens'])}',
                    'Tokens cache':
                        '${number(run['totalCacheReadTokens'])} gelezen · ${number(run['totalCacheCreationTokens'])} aangemaakt',
                    'Kosten': run['totalCostUsdEst'] != null ? '\$${(run['totalCostUsdEst'] as num).toStringAsFixed(2)}' : '-',
                  }),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

/// Eén actieknop bovenin de pagina (§9-feedback) die een uitklapmenu opent met dezelfde 3
/// groepen als de oude Kotlin-actionsBar: commando's, links, en gevaarlijke acties — i.p.v.
/// losse iconen in de appbar of een platte knoppenrij.
class _ActionsMenuButton extends StatelessWidget {
  final bool busy;
  final void Function(String command, bool destructive) onCommand;
  final VoidCallback? onOpenWorkspace;
  final VoidCallback? onPurge;
  final VoidCallback onOpenBriefing;
  final VoidCallback onOpenScreenshots;
  final void Function(String url) onOpenLink;
  final String youTrackUrl;
  final String prUrl;
  final String prNumber;
  final String previewUrl;
  const _ActionsMenuButton({
    required this.busy,
    required this.onCommand,
    required this.onOpenWorkspace,
    required this.onPurge,
    required this.onOpenBriefing,
    required this.onOpenScreenshots,
    required this.onOpenLink,
    required this.youTrackUrl,
    required this.prUrl,
    required this.prNumber,
    required this.previewUrl,
  });

  @override
  Widget build(BuildContext context) => PopupMenuButton<String>(
    enabled: !busy,
    onSelected: (value) {
      if (value.startsWith('cmd:')) {
        final command = value.substring(4);
        final destructive = _commands.firstWhere((c) => c.$1 == command).$3;
        onCommand(command, destructive);
      } else {
        switch (value) {
          case 'workspace':
            onOpenWorkspace?.call();
          case 'briefing':
            onOpenBriefing();
          case 'screenshots':
            onOpenScreenshots();
          case 'youtrack':
            onOpenLink(youTrackUrl);
          case 'pr':
            onOpenLink(prUrl);
          case 'preview':
            onOpenLink(previewUrl);
          case 'purge':
            onPurge?.call();
        }
      }
    },
    itemBuilder: (context) => [
      const _GroupLabel('Commando\'s'),
      for (final (command, label, _) in _commands.where((c) => !c.$3))
        PopupMenuItem(value: 'cmd:$command', child: Text(label)),
      const PopupMenuDivider(),
      const _GroupLabel('Links'),
      PopupMenuItem(value: 'workspace', enabled: onOpenWorkspace != null, child: const Text('Open in IntelliJ')),
      const PopupMenuItem(value: 'briefing', child: Text('Briefing')),
      const PopupMenuItem(value: 'screenshots', child: Text('Screenshots')),
      if (youTrackUrl.isNotEmpty) const PopupMenuItem(value: 'youtrack', child: Text('YouTrack')),
      if (prUrl.isNotEmpty) PopupMenuItem(value: 'pr', child: Text('PR${prNumber.isNotEmpty ? ' #$prNumber' : ''}')),
      if (previewUrl.isNotEmpty) const PopupMenuItem(value: 'preview', child: Text('Test op preview')),
      const PopupMenuDivider(),
      const _GroupLabel('Gevaarlijk'),
      for (final (command, label, _) in _commands.where((c) => c.$3))
        PopupMenuItem(value: 'cmd:$command', child: Text(label, style: const TextStyle(color: SfColors.red))),
      PopupMenuItem(value: 'purge', enabled: onPurge != null, child: const Text('Purge story', style: TextStyle(color: SfColors.red))),
    ],
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(color: SfColors.accentSoft, borderRadius: BorderRadius.circular(12)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: const [
          Icon(Icons.tune, size: 18, color: SfColors.accent),
          SizedBox(width: 8),
          Text('Acties & links', style: TextStyle(color: SfColors.accent, fontWeight: FontWeight.w700)),
        ],
      ),
    ),
  );
}

/// Niet-selecteerbaar groep-label in het uitklapmenu — zelfde idee als de oude Kotlin `grp-label`.
class _GroupLabel extends PopupMenuEntry<String> {
  final String label;
  const _GroupLabel(this.label);

  @override
  double get height => 28;

  @override
  bool represents(String? value) => false;

  @override
  State<_GroupLabel> createState() => _GroupLabelState();
}

class _GroupLabelState extends State<_GroupLabel> {
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 12),
    child: Text(
      widget.label.toUpperCase(),
      style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: SfColors.faint, letterSpacing: 0.5),
    ),
  );
}

/// Subtaken-lijst — 1-op-1 met StoryDetailView.kt's subtasksPanel: key, samenvatting, type/status/
/// error-badges en fase, met doorklik naar het subtaak-detailscherm.
class _SubtasksPanel extends StatelessWidget {
  final AppState state;
  final List<Map<String, dynamic>> subtasks;
  const _SubtasksPanel({required this.state, required this.subtasks});

  @override
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final subtask in subtasks) _subtaskRow(context, subtask),
      ],
    ),
  );

  Widget _subtaskRow(BuildContext context, Map<String, dynamic> subtask) {
    final fields = Map<String, dynamic>.from(subtask['fields'] as Map? ?? {});
    final phase = text(fields['subtaskPhase']);
    final subtaskType = text(fields['subtaskType']);
    final hasError = text(fields['error']).isNotEmpty;
    return InkWell(
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => StoryDetailScreen(state: state, storyKey: text(subtask['key']))),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            SizedBox(width: 60, child: Text(text(subtask['key']), style: const TextStyle(fontWeight: FontWeight.w700))),
            Expanded(
              child: Text(text(subtask['summary']), maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
            const SizedBox(width: 8),
            if (subtaskType.isNotEmpty) Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Text(subtaskType, style: const TextStyle(color: Colors.black54, fontSize: 12)),
            ),
            if (hasError) const Padding(
              padding: EdgeInsets.only(right: 8),
              child: StatusBadge('fout', BadgeTone.bad),
            ),
            StatusBadge.fromPhase(phase.isEmpty ? null : phase),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right, size: 18),
          ],
        ),
      ),
    );
  }
}

/// Briefing als eigen scherm (§9-feedback): agent-run-samenvattingen en gebruikers-antwoorden,
/// chronologisch (nieuwste eerst) — zelfde bron als de oude Kotlin-briefingpagina.
class BriefingScreen extends StatelessWidget {
  final AppState state;
  final String storyKey;
  const BriefingScreen({super.key, required this.state, required this.storyKey});

  @override
  Widget build(BuildContext context) => DataScreen(
    state: state,
    title: 'Briefing — $storyKey',
    fetch: (api) => api.getJson('/api/v1/stories/$storyKey'),
    builder: (context, data) {
      final issue = Map<String, dynamic>.from(data['issue'] as Map? ?? {});
      final subtasks = asList(data['subtasks']);
      final allAgentRuns = asList(data['allAgentRuns']);
      return _BriefingPanel(issue: issue, subtasks: subtasks, allAgentRuns: allAgentRuns);
    },
  );
}

class _BriefingPanel extends StatelessWidget {
  final Map<String, dynamic> issue;
  final List<Map<String, dynamic>> subtasks;
  final List<Map<String, dynamic>> allAgentRuns;
  const _BriefingPanel({required this.issue, required this.subtasks, required this.allAgentRuns});

  @override
  Widget build(BuildContext context) {
    final items = <_BriefingItem>[
      for (final run in allAgentRuns)
        if (text(run['summaryText']).isNotEmpty)
          _BriefingItem(
            timestamp: text(run['endedAt'], fallback: text(run['startedAt'])),
            title: '${text(run['storyKey'])} · ${text(run['role'])}',
            body: text(run['summaryText']),
          ),
      for (final comment in _userComments(issue))
        _BriefingItem(
          timestamp: text(comment['created']),
          title: text(comment['authorDisplayName'], fallback: 'Onbekend'),
          body: text(comment['body']),
        ),
      for (final subtask in subtasks)
        for (final comment in _userComments(subtask))
          _BriefingItem(
            timestamp: text(comment['created']),
            title: '${text(subtask['key'])} · ${text(comment['authorDisplayName'], fallback: 'Onbekend')}',
            body: text(comment['body']),
          ),
    ]..sort((a, b) => b.timestamp.compareTo(a.timestamp));

    if (items.isEmpty) return const EmptyState('Nog geen agent-runs of gebruikers-antwoorden gevonden.');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final item in items)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(child: Text(item.title, style: const TextStyle(fontWeight: FontWeight.w700))),
                      Text(formatTimestamp(item.timestamp), style: const TextStyle(color: Colors.black54, fontSize: 12)),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(item.body),
                ],
              ),
            ),
          ),
      ],
    );
  }

  List<Map<String, dynamic>> _userComments(Map<String, dynamic> issueOrSubtask) =>
      asList(issueOrSubtask['comments']).where((c) => !boolValue(c['isAgentComment']) && text(c['created']).isNotEmpty).toList();
}

class _BriefingItem {
  final String timestamp;
  final String title;
  final String body;
  _BriefingItem({required this.timestamp, required this.title, required this.body});
}

class _ChainVisualization extends StatelessWidget {
  final List<Map<String, dynamic>> subtasks;
  const _ChainVisualization({required this.subtasks});

  @override
  Widget build(BuildContext context) {
    return Panel(
      child: Wrap(
        spacing: 8,
        runSpacing: 12,
        children: [
          for (final (type, label) in _chainStages) _ChainStep(type: type, label: label, subtasks: subtasks),
        ],
      ),
    );
  }
}

class _ChainStep extends StatelessWidget {
  final String type;
  final String label;
  final List<Map<String, dynamic>> subtasks;
  const _ChainStep({required this.type, required this.label, required this.subtasks});

  @override
  Widget build(BuildContext context) {
    final subtask = subtasks.firstWhere(
      (s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['subtaskType']) == type,
      orElse: () => const {},
    );
    final fields = Map<String, dynamic>.from(subtask['fields'] as Map? ?? {});
    final phase = text(fields['subtaskPhase']);
    return Column(
      children: [
        Text(label, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.black54)),
        const SizedBox(height: 4),
        StatusBadge.fromPhase(phase.isEmpty ? null : phase),
      ],
    );
  }
}

class _KeyValueList extends StatelessWidget {
  final Map<String, String> values;
  const _KeyValueList(this.values);

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final entry in values.entries)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(width: 120, child: Text(entry.key, style: const TextStyle(color: Colors.black54))),
              Expanded(child: Text(entry.value, style: const TextStyle(fontWeight: FontWeight.w600))),
            ],
          ),
        ),
    ],
  );
}
