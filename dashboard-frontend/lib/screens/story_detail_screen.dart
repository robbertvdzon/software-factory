import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../ai_catalog.dart';
import '../api_client.dart';
import '../app_state.dart';
import '../main.dart';
import '../pending_action.dart';
import '../phase_stepper.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'screenshots_screen.dart';

/// Commando's uit core/TrackerModels.kt FactoryCommand; destructief/onomkeerbaar (§8 fase D)
/// vraagt een bevestigingsdialoog vóór het versturen. Approve/reject staan hier bewust niet meer
/// in: die lopen nu via [PendingActionCard] met de juiste doelfase per situatie (zie
/// pending_action.dart) — het generieke commando werkte alleen voor de manual-approve-poort.
const _commands = [
  ('pause', 'Pause', false),
  ('resume', 'Resume', false),
  ('clear-error', 'Clear error', false),
  ('retry-current-step', 'Retry step', false),
  ('merge', 'Merge', false),
  ('re-implement', 'Re-implement', true),
  ('kill', 'Kill', true),
  ('delete', 'Delete', true),
];

class _PendingSubtask {
  final String key;
  final PendingAction action;
  final String question;
  const _PendingSubtask({required this.key, required this.action, required this.question});
}

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

  Future<void> _toggleSilent(bool enabled) async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/silent', {'enabled': enabled}),
      successMessage: enabled ? 'Silent ingeschakeld.' : 'Silent uitgeschakeld.',
    );
  }

  Future<void> _editDescription(String current) async {
    final result = await showDialog<String>(
      context: context,
      builder: (_) => _EditDescriptionDialog(initial: current),
    );
    if (result == null) return;
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/edit', {'description': result}),
      successMessage: 'Omschrijving opgeslagen.',
    );
  }

  Future<void> _editAiFields(String currentSupplier, String? currentModel) async {
    final result = await showDialog<Map<String, String?>>(
      context: context,
      builder: (_) => _EditAiFieldsDialog(initialSupplier: currentSupplier, initialModel: currentModel),
    );
    if (result == null) return;
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/edit', {
        'aiSupplier': result['aiSupplier'],
        if (result['aiModel'] != null) 'aiModel': result['aiModel'],
      }),
      successMessage: 'AI-velden opgeslagen.',
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
        final myPendingAction = pendingActionFor(isStory: isStory, phase: currentPhase, subtaskType: text(fields['subtaskType']));
        // Op het story-scherm surfacen we ook meteen de subtaken die op een mens wachten (vraag of
        // approve) — anders moest je eerst doorklikken naar de subtaak om dat te zien.
        final pendingSubtasks = <_PendingSubtask>[
          if (isStory)
            for (final s in subtasks)
              if (pendingActionFor(
                    isStory: false,
                    phase: text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['subtaskPhase']),
                    subtaskType: text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['subtaskType']),
                  )
                  case final action?)
                _PendingSubtask(key: text(s['key']), action: action, question: text(agentQuestions[text(s['key'])])),
        ];
        // storyPhase blijft na de refinement/planningfase bewust op 'in-progress' staan (development
        // is subtaak-gedreven) — voor een afgeronde story is `status` (== "Done") de juiste bron.
        final storyFinished = isStory && text(issue['status']).trim().toLowerCase() == 'done';
        final showStartRefining = isStory && text(fields['storyPhase']).isEmpty;
        final showStartDeveloping = isStory &&
            text(fields['storyPhase']) == 'planning-approved' &&
            subtasks.isNotEmpty &&
            subtasks.every((s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['subtaskPhase']).isEmpty);
        final hasError = _hasError(fields, subtasks);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SelectableText(text(issue['summary']), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (storyFinished)
                  const StatusBadge('done', BadgeTone.good)
                else
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
            if (myPendingAction != null) ...[
              const SizedBox(height: 12),
              PendingActionCard(
                state: widget.state,
                issueKey: widget.storyKey,
                isStory: isStory,
                action: myPendingAction,
                question: myPendingAction.kind == PendingKind.question ? myQuestion : null,
                onDone: () => _dataScreenKey.currentState?.reload(),
              ),
            ],
            if (pendingSubtasks.isNotEmpty) ...[
              const SizedBox(height: 12),
              const SectionTitle('Wacht op jou'),
              for (final pending in pendingSubtasks) ...[
                const SizedBox(height: 8),
                PendingActionCard(
                  state: widget.state,
                  issueKey: pending.key,
                  isStory: false,
                  action: pending.action,
                  question: pending.action.kind == PendingKind.question ? pending.question : null,
                  onDone: () => _dataScreenKey.currentState?.reload(),
                ),
              ],
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
            SectionTitle(isStory ? 'Fase' : 'Fase van deze subtaak'),
            if (isStory)
              StoryPhaseStepper(phase: currentPhase)
            else
              SubtaskPhaseStepper(subtaskType: text(fields['subtaskType']), phase: currentPhase),
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
              prUrl: text(run['prUrl']),
              prNumber: text(run['prNumber']),
              previewUrl: text(data['previewUrl'], fallback: text(run['previewUrl'])),
            ),
            if (subtasks.isNotEmpty) ...[
              const SizedBox(height: 20),
              const SectionTitle('Subtaken'),
              _SubtasksPanel(state: widget.state, subtasks: subtasks),
            ],
            const SizedBox(height: 20),
            Row(
              children: [
                const Expanded(child: SectionTitle('Omschrijving')),
                IconButton(
                  icon: const Icon(Icons.edit, size: 18),
                  tooltip: 'Omschrijving bewerken',
                  onPressed: _busy ? null : () => _editDescription(text(issue['description'])),
                ),
              ],
            ),
            Panel(child: SelectableText(text(issue['description'], fallback: 'Geen omschrijving.'))),
            const SizedBox(height: 20),
            Row(
              children: [
                const Expanded(child: SectionTitle('Details')),
                IconButton(
                  icon: const Icon(Icons.edit, size: 18),
                  tooltip: 'AI-supplier/model bewerken',
                  onPressed: _busy
                      ? null
                      : () => _editAiFields(
                          text(fields['aiSupplier']),
                          text(fields['aiModel']).isEmpty ? null : text(fields['aiModel']),
                        ),
                ),
              ],
            ),
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
                  if (isStory)
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Silent'),
                      value: boolValue(fields['silent']),
                      onChanged: _busy ? null : _toggleSilent,
                    ),
                  const Divider(),
                  _KeyValueList({
                    'State': text(issue['status'], fallback: '-'),
                    'Paused': boolValue(fields['paused']) ? 'Ja' : 'Nee',
                    if (isStory) 'Story phase': text(fields['storyPhase'], fallback: '-'),
                    if (!isStory) 'Subtask phase': text(fields['subtaskPhase'], fallback: '-'),
                    if (!isStory) 'Subtask type': text(fields['subtaskType'], fallback: '-'),
                    'Target repo': text(fields['targetRepo'], fallback: '-'),
                    'AI-supplier': text(fields['aiSupplier'], fallback: '-'),
                    'AI-model': text(fields['aiModel'], fallback: '-'),
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
                      Expanded(child: SelectableText(item.title, style: const TextStyle(fontWeight: FontWeight.w700))),
                      Text(formatTimestamp(item.timestamp), style: const TextStyle(color: Colors.black54, fontSize: 12)),
                    ],
                  ),
                  const SizedBox(height: 4),
                  SelectableText(item.body),
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

/// Dialoog voor het bewerken van de omschrijving (Panel "Omschrijving") — opslaan gebeurt door de
/// aanroeper via de nieuwe `POST .../edit`-bridge-operatie, hier alleen tekstinvoer.
class _EditDescriptionDialog extends StatefulWidget {
  final String initial;
  const _EditDescriptionDialog({required this.initial});

  @override
  State<_EditDescriptionDialog> createState() => _EditDescriptionDialogState();
}

class _EditDescriptionDialogState extends State<_EditDescriptionDialog> {
  late final _controller = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Omschrijving bewerken'),
    content: SizedBox(
      width: 420,
      child: TextField(
        controller: _controller,
        autofocus: true,
        minLines: 4,
        maxLines: 10,
        decoration: const InputDecoration(labelText: 'Omschrijving'),
      ),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Annuleren')),
      FilledButton(
        onPressed: () => Navigator.of(context).pop(_controller.text.trim()),
        child: const Text('Opslaan'),
      ),
    ],
  );
}

/// Dialoog voor het bewerken van AI-supplier/AI-model — zelfde suppliers/modellenlijst
/// ([aiSuppliers]/[aiModelsBySupplier]) als het "Nieuwe story"-dialoog (stories_screen.dart).
class _EditAiFieldsDialog extends StatefulWidget {
  final String initialSupplier;
  final String? initialModel;
  const _EditAiFieldsDialog({required this.initialSupplier, this.initialModel});

  @override
  State<_EditAiFieldsDialog> createState() => _EditAiFieldsDialogState();
}

class _EditAiFieldsDialogState extends State<_EditAiFieldsDialog> {
  late var _supplier = aiSuppliers.contains(widget.initialSupplier) ? widget.initialSupplier : 'claude';
  String? _model;

  @override
  void initState() {
    super.initState();
    _model = (aiModelsBySupplier[_supplier] ?? const <String>[]).contains(widget.initialModel) ? widget.initialModel : null;
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('AI-supplier/model bewerken'),
    content: SizedBox(
      width: 380,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          DropdownButtonFormField<String>(
            initialValue: _supplier,
            decoration: const InputDecoration(labelText: 'AI-supplier'),
            items: [for (final supplier in aiSuppliers) DropdownMenuItem(value: supplier, child: Text(supplier))],
            onChanged: (value) => setState(() {
              _supplier = value ?? 'claude';
              _model = null;
            }),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            initialValue: _model,
            decoration: const InputDecoration(labelText: 'AI-model'),
            items: [
              const DropdownMenuItem(value: null, child: Text('— automatisch (op AI-niveau) —')),
              for (final model in aiModelsBySupplier[_supplier] ?? const <String>[])
                DropdownMenuItem(value: model, child: Text(model)),
            ],
            onChanged: (value) => setState(() => _model = value),
          ),
        ],
      ),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Annuleren')),
      FilledButton(
        onPressed: () => Navigator.of(context).pop({'aiSupplier': _supplier, 'aiModel': _model}),
        child: const Text('Opslaan'),
      ),
    ],
  );
}
