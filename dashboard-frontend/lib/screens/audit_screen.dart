import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../main.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'story_detail_screen.dart';

/// Toont, per project, een compact panel per geconfigureerde audit (uit `.factory/nightly/*`):
/// laatste-run-info (of "draait nu al X"), een link naar de eventueel voorgestelde vervolg-story,
/// en drie acties (Run now / Open reports / Open memory). Rapporten en memory-tips zelf staan niet
/// meer inline op deze pagina — die zijn per audit te veel om in één keer te tonen — maar in een
/// drill-down dialoog die op aanvraag ophaalt.
class AuditScreen extends StatefulWidget {
  final AppState state;
  const AuditScreen({super.key, required this.state});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  String? _runningAudit;

  Future<Map<String, dynamic>> _fetch(ApiClient api) async {
    final overview = await api.getJson('/api/v1/audits/overview');
    return {
      'auditProjects': overview['projects'],
      'errors': overview['errors'],
    };
  }

  Future<void> _reload() async => _dataScreenKey.currentState?.reload();

  Future<void> _runNow(String project, String auditType) async {
    final auditKey = '$project/$auditType';
    setState(() => _runningAudit = auditKey);
    try {
      final result = await widget.state.api.postJson('/api/v1/audits/run-now', {
        'project': project,
        'auditType': auditType,
      });
      if (!mounted) return;
      final started = boolValue(result['started']);
      showActionResult(
        context,
        success: started,
        message: started
            ? 'Audit gestart voor $project/$auditType.'
            : 'Kon audit niet starten — er loopt al een andere audit-run.',
      );
      if (started) await _reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _runningAudit = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Audits',
      fetch: _fetch,
      builder: (context, data) {
        final auditProjects = asList(data['auditProjects']);
        // errors zijn losse strings, niet maps — asList verwacht maps, dus de ruwe lijst hier
        // direct pakken i.p.v. via asList.
        final rawErrors = (data['errors'] as List? ?? []).map((e) => e.toString()).toList();

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (rawErrors.isNotEmpty) ...[
              for (final error in rawErrors) ErrorBanner(error),
              const SizedBox(height: 12),
            ],
            if (auditProjects.isEmpty)
              const EmptyState('Geen audits geconfigureerd.')
            else
              for (final projectGroup in auditProjects) ...[
                Padding(
                  padding: const EdgeInsets.only(top: 8, bottom: 8),
                  child: Text(
                    text(projectGroup['project']),
                    style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
                  ),
                ),
                Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  children: [
                    for (final audit in asList(projectGroup['audits']))
                      _AuditPanel(
                        state: widget.state,
                        project: text(projectGroup['project']),
                        audit: audit,
                        busy: _runningAudit != null,
                        onRunNow: _runNow,
                      ),
                  ],
                ),
                const SizedBox(height: 8),
              ],
          ],
        );
      },
    );
  }
}

class _AuditPanel extends StatelessWidget {
  final AppState state;
  final String project;
  final Map<String, dynamic> audit;
  final bool busy;
  final void Function(String project, String auditType) onRunNow;

  const _AuditPanel({
    required this.state,
    required this.project,
    required this.audit,
    required this.busy,
    required this.onRunNow,
  });

  @override
  Widget build(BuildContext context) {
    final auditType = text(audit['auditType']);
    final enabled = boolValue(audit['enabled']);
    final lastRunAt = audit['lastRunAt'];
    final lastRunDurationMs = audit['lastRunDurationMs'];
    final runStatus = text(audit['runStatus']);
    final isRunning = runStatus == 'running';
    final isPending = runStatus == 'pending';
    final proposedStoryKey = text(audit['proposedStoryKey']);

    final String statusLine;
    if (isRunning) {
      statusLine = 'Draait al ${_elapsedSince(audit['runStartedAt'])}';
    } else if (isPending) {
      statusLine = 'In wachtrij';
    } else if (lastRunAt == null) {
      statusLine = 'Nog nooit gedraaid';
    } else {
      final durationSuffix = lastRunDurationMs != null
          ? ' (${formatDuration((lastRunDurationMs as num) / 1000)})'
          : '';
      statusLine = 'Laatst gedraaid: ${formatTimestamp(lastRunAt)}$durationSuffix';
    }

    return SizedBox(
      width: 320,
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    text(audit['title'], fallback: auditType),
                    style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                  ),
                ),
                if (isRunning || isPending) _RunStatusChip(running: isRunning),
              ],
            ),
            if (!enabled)
              const Padding(
                padding: EdgeInsets.only(top: 2),
                child: Text('(uitgeschakeld)', style: TextStyle(color: Colors.black45, fontSize: 12)),
              ),
            const SizedBox(height: 6),
            Text(statusLine, style: const TextStyle(color: Colors.black54, fontSize: 13)),
            if (proposedStoryKey.isNotEmpty) ...[
              const SizedBox(height: 4),
              InkWell(
                onTap: () => Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => StoryDetailScreen(state: state, storyKey: proposedStoryKey)),
                ),
                child: Text(
                  'Story: $proposedStoryKey',
                  style: const TextStyle(color: SfColors.blue, fontSize: 13, decoration: TextDecoration.underline),
                ),
              ),
            ],
            const SizedBox(height: 10),
            Wrap(
              spacing: 4,
              children: [
                TextButton(
                  onPressed: (busy || isRunning || isPending) ? null : () => onRunNow(project, auditType),
                  child: const Text('Run now'),
                ),
                TextButton(
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => _AuditReportsDialog(state: state, project: project, auditType: auditType),
                  ),
                  child: const Text('Open reports'),
                ),
                TextButton(
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => _AuditMemoryDialog(state: state, project: project, auditType: auditType),
                  ),
                  child: const Text('Open memory'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// "net gestart"/"12 min"/"3 u 05 min" sinds [value] (ISO-timestamp) — voor de "Draait al ..."-regel.
String _elapsedSince(dynamic value) {
  final raw = text(value, fallback: '-');
  if (raw == '-') return '-';
  final parsed = DateTime.tryParse(raw);
  if (parsed == null) return '-';
  final diff = DateTime.now().difference(parsed.toLocal());
  if (diff.inSeconds < 60) return 'net gestart';
  if (diff.inMinutes < 60) return '${diff.inMinutes} min';
  if (diff.inHours < 24) return '${diff.inHours} u ${(diff.inMinutes % 60).toString().padLeft(2, '0')} min';
  return '${diff.inDays} d';
}

class _RunStatusChip extends StatelessWidget {
  final bool running;
  const _RunStatusChip({required this.running});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
    decoration: BoxDecoration(
      color: (running ? SfColors.amber : Colors.black45).withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (running)
          const SizedBox(
            width: 10,
            height: 10,
            child: CircularProgressIndicator(strokeWidth: 2, color: SfColors.amber),
          )
        else
          const Icon(Icons.hourglass_empty, size: 12, color: Colors.black45),
        const SizedBox(width: 6),
        Text(
          running ? 'Draait…' : 'In wachtrij',
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: running ? SfColors.amber : Colors.black54,
          ),
        ),
      ],
    ),
  );
}

/// Modaal met alleen datum/tijd per rapport (nieuwste bovenaan) — de inhoud zelf wordt pas
/// opgehaald zodra je op een rij klikt ([_AuditReportDetailScreen]).
class _AuditReportsDialog extends StatefulWidget {
  final AppState state;
  final String project;
  final String auditType;
  const _AuditReportsDialog({required this.state, required this.project, required this.auditType});

  @override
  State<_AuditReportsDialog> createState() => _AuditReportsDialogState();
}

class _AuditReportsDialogState extends State<_AuditReportsDialog> {
  late final Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    final query =
        'project=${Uri.encodeQueryComponent(widget.project)}&auditType=${Uri.encodeQueryComponent(widget.auditType)}';
    _future = widget.state.api.getJson('/api/v1/audits/reports?$query');
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: SizedBox(
        width: 420,
        height: 520,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'Rapporten — ${widget.auditType}',
                      style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                    ),
                  ),
                  IconButton(icon: const Icon(Icons.close), onPressed: () => Navigator.pop(context)),
                ],
              ),
              const Divider(),
              Expanded(
                child: FutureBuilder<Map<String, dynamic>>(
                  future: _future,
                  builder: (context, snapshot) {
                    if (snapshot.connectionState != ConnectionState.done) {
                      return const Center(child: CircularProgressIndicator());
                    }
                    if (snapshot.hasError) {
                      return ErrorBanner(snapshot.error.toString());
                    }
                    final reports = asList(snapshot.data?['reports']);
                    if (reports.isEmpty) {
                      return const EmptyState('Nog geen rapporten.');
                    }
                    return ListView.builder(
                      itemCount: reports.length,
                      itemBuilder: (context, index) {
                        final report = reports[index];
                        return ListTile(
                          title: Text(formatTimestamp(report['generatedAt'])),
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => _AuditReportDetailScreen(state: widget.state, reportId: number(report['id'])),
                            ),
                          ),
                        );
                      },
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Volledige inhoud van één rapport — gerenderd als Markdown, niet als ruwe brontekst. Eigen
/// pagina (niet meer een klein Dialog-venster) omdat rapporten met samenvatting + gedetailleerde
/// bevindingen te lang zijn om prettig in een popup te lezen.
class _AuditReportDetailScreen extends StatefulWidget {
  final AppState state;
  final int reportId;
  const _AuditReportDetailScreen({required this.state, required this.reportId});

  @override
  State<_AuditReportDetailScreen> createState() => _AuditReportDetailScreenState();
}

class _AuditReportDetailScreenState extends State<_AuditReportDetailScreen> {
  late final Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.state.api.getJson('/api/v1/audits/reports/${widget.reportId}');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Auditrapport')),
      body: FutureBuilder<Map<String, dynamic>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(snapshot.error.toString()));
          }
          final report = snapshot.data ?? {};
          final score =
              text(report['scoreLabel']).isNotEmpty ? text(report['scoreLabel']) : report['score']?.toString();
          final durationMs = report['durationMs'];
          final proposedStoryKey = text(report['proposedStoryKey']);
          return Align(
            alignment: Alignment.topLeft,
            child: ConstrainedBox(
              // Zelfde max-breedte als DataScreen: rapporttekst met lange regels leest prettiger
              // smaller dan schermbreed, ook op een brede monitor.
              constraints: const BoxConstraints(maxWidth: 860),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      formatTimestamp(report['generatedAt']),
                      style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 16,
                      children: [
                        if (score != null) Text('Score: $score', style: const TextStyle(color: Colors.black54)),
                        if (durationMs != null)
                          Text('Duur: ${formatDuration((durationMs as num) / 1000)}',
                              style: const TextStyle(color: Colors.black54)),
                        if (proposedStoryKey.isNotEmpty)
                          InkWell(
                            onTap: () => Navigator.push(
                              context,
                              MaterialPageRoute(
                                builder: (_) => StoryDetailScreen(state: widget.state, storyKey: proposedStoryKey),
                              ),
                            ),
                            child: Text(
                              'Story: $proposedStoryKey',
                              style: const TextStyle(color: SfColors.blue, decoration: TextDecoration.underline),
                            ),
                          ),
                      ],
                    ),
                    const Divider(height: 24),
                    MarkdownBody(data: text(report['content'], fallback: '(geen rapporttekst)')),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// Memory-tips (`knowledge`-domein, rol `auditor`) voor precies deze ene (project, auditType) —
/// gefilterd client-side uit `/api/v1/audit-memory`, dat blijft alle audits in één keer ophalen.
class _AuditMemoryDialog extends StatefulWidget {
  final AppState state;
  final String project;
  final String auditType;
  const _AuditMemoryDialog({required this.state, required this.project, required this.auditType});

  @override
  State<_AuditMemoryDialog> createState() => _AuditMemoryDialogState();
}

class _AuditMemoryDialogState extends State<_AuditMemoryDialog> {
  List<Map<String, dynamic>> _notes = [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final memory = await widget.state.api.getJson('/api/v1/audit-memory');
      final all = asList(memory['notes']);
      if (!mounted) return;
      setState(() {
        _notes = all
            .where((note) => text(note['project']) == widget.project && text(note['auditType']) == widget.auditType)
            .toList();
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _save(String key, String content) async {
    try {
      await widget.state.api.postJson('/api/v1/audit-memory/update', {
        'project': widget.project,
        'auditType': widget.auditType,
        'key': key,
        'content': content,
      });
      await _load();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    }
  }

  Future<void> _delete(String key) async {
    try {
      await widget.state.api.postJson('/api/v1/audit-memory/delete', {
        'project': widget.project,
        'auditType': widget.auditType,
        'key': key,
      });
      await _load();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    }
  }

  Future<void> _openNoteDialog({String? existingKey, String? existingContent}) async {
    final keyController = TextEditingController(text: existingKey ?? '');
    final contentController = TextEditingController(text: existingContent ?? '');
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(existingKey == null ? 'Nieuwe memory-tip' : 'Memory-tip bewerken'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: keyController,
              enabled: existingKey == null,
              decoration: const InputDecoration(labelText: 'Sleutel (kort, stabiel)'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: contentController,
              maxLines: 6,
              decoration: const InputDecoration(labelText: 'Inhoud'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuleren')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Opslaan')),
        ],
      ),
    );
    if (saved == true && keyController.text.trim().isNotEmpty && contentController.text.trim().isNotEmpty) {
      await _save(keyController.text.trim(), contentController.text.trim());
    }
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: SizedBox(
        width: 480,
        height: 520,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'Memory — ${widget.auditType}',
                      style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                    ),
                  ),
                  IconButton(icon: const Icon(Icons.add), tooltip: 'Nieuwe tip', onPressed: () => _openNoteDialog()),
                  IconButton(icon: const Icon(Icons.close), onPressed: () => Navigator.pop(context)),
                ],
              ),
              const Divider(),
              Expanded(
                child: _loading
                    ? const Center(child: CircularProgressIndicator())
                    : _error != null
                        ? ErrorBanner(_error!)
                        : _notes.isEmpty
                            ? const EmptyState('Nog geen memory-tips.')
                            : ListView(
                                children: [
                                  for (final note in _notes)
                                    ListTile(
                                      title: Text(text(note['key'])),
                                      subtitle: Text(text(note['content'])),
                                      trailing: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          IconButton(
                                            icon: const Icon(Icons.edit_outlined),
                                            onPressed: () => _openNoteDialog(
                                              existingKey: text(note['key']),
                                              existingContent: text(note['content']),
                                            ),
                                          ),
                                          IconButton(
                                            icon: const Icon(Icons.delete_outline),
                                            onPressed: () => _delete(text(note['key'])),
                                          ),
                                        ],
                                      ),
                                    ),
                                ],
                              ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
