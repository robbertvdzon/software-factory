import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// Toont de audit-rapporten (read-only agent-runs die geen code aanpassen, zie
/// `.factory/nightly/README.md`) en de memory-tips die de auditor voor zichzelf opslaat
/// (bewerkbaar/verwijderbaar door Robbert). Combineert twee backend-endpoints
/// (`/api/v1/audit-reports` + `/api/v1/audit-memory`) in één scherm.
class AuditScreen extends StatefulWidget {
  final AppState state;
  const AuditScreen({super.key, required this.state});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();

  Future<Map<String, dynamic>> _fetch(ApiClient api) async {
    final reports = await api.getJson('/api/v1/audit-reports');
    final memory = await api.getJson('/api/v1/audit-memory');
    return {
      'reports': reports['reports'],
      'errors': reports['errors'],
      'notes': memory['notes'],
    };
  }

  Future<void> _reload() async => _dataScreenKey.currentState?.reload();

  Future<void> _saveNote(String project, String auditType, String key, String content) async {
    try {
      await widget.state.api.postJson('/api/v1/audit-memory/update', {
        'project': project,
        'auditType': auditType,
        'key': key,
        'content': content,
      });
      if (!mounted) return;
      showActionResult(context, success: true, message: 'Memory-tip opgeslagen.');
      await _reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    }
  }

  Future<void> _deleteNote(String project, String auditType, String key) async {
    try {
      await widget.state.api.postJson('/api/v1/audit-memory/delete', {
        'project': project,
        'auditType': auditType,
        'key': key,
      });
      if (!mounted) return;
      showActionResult(context, success: true, message: 'Memory-tip verwijderd.');
      await _reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    }
  }

  Future<void> _openNoteDialog(
    String project,
    String auditType, {
    String? existingKey,
    String? existingContent,
  }) async {
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
      await _saveNote(project, auditType, keyController.text.trim(), contentController.text.trim());
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
        final reports = asList(data['reports']);
        // errors zijn losse strings, niet maps — asList verwacht maps, dus de ruwe lijst hier
        // direct pakken i.p.v. via asList.
        final rawErrors = (data['errors'] as List? ?? []).map((e) => e.toString()).toList();
        final notes = asList(data['notes']);
        final notesByGroup = <String, List<Map<String, dynamic>>>{};
        for (final note in notes) {
          final groupKey = '${text(note['project'])}/${text(note['auditType'])}';
          notesByGroup.putIfAbsent(groupKey, () => []).add(note);
        }

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (rawErrors.isNotEmpty) ...[
              for (final error in rawErrors) ErrorBanner(error),
              const SizedBox(height: 12),
            ],
            const SectionTitle('Rapporten'),
            if (reports.isEmpty)
              const EmptyState('Nog geen audit-rapporten.')
            else
              for (final report in reports) _ReportCard(report: report),
            const SizedBox(height: 24),
            const SectionTitle('Memory'),
            if (notesByGroup.isEmpty)
              const EmptyState('Nog geen memory-tips.')
            else
              for (final entry in notesByGroup.entries)
                _MemoryGroupCard(
                  groupLabel: entry.key,
                  notes: entry.value,
                  onAdd: (project, auditType) => _openNoteDialog(project, auditType),
                  onEdit: (project, auditType, key, content) =>
                      _openNoteDialog(project, auditType, existingKey: key, existingContent: content),
                  onDelete: _deleteNote,
                ),
          ],
        );
      },
    );
  }
}

class _ReportCard extends StatelessWidget {
  final Map<String, dynamic> report;
  const _ReportCard({required this.report});

  @override
  Widget build(BuildContext context) {
    final score = report['score'];
    final trend = text(report['scoreTrend']);
    final trendIcon = switch (trend) {
      'up' => Icons.trending_up,
      'down' => Icons.trending_down,
      'flat' => Icons.trending_flat,
      _ => null,
    };
    final proposedStoryKey = text(report['proposedStoryKey']);
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${text(report['project'])} · ${text(report['title'])}',
                    style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                  ),
                ),
                if (score != null) ...[
                  if (trendIcon != null) Icon(trendIcon, size: 18),
                  const SizedBox(width: 4),
                  Text(text(report['scoreLabel'], fallback: score.toString())),
                ],
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Laatst gedraaid: ${formatTimestamp(report['lastRunAt'])}',
              style: const TextStyle(color: Colors.black54),
            ),
            const SizedBox(height: 8),
            Text(text(report['content'], fallback: '(geen rapporttekst)')),
            if (proposedStoryKey.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text('Voorgestelde vervolg-story: $proposedStoryKey', style: const TextStyle(fontWeight: FontWeight.w600)),
            ],
          ],
        ),
      ),
    );
  }
}

class _MemoryGroupCard extends StatelessWidget {
  final String groupLabel;
  final List<Map<String, dynamic>> notes;
  final void Function(String project, String auditType) onAdd;
  final void Function(String project, String auditType, String key, String content) onEdit;
  final void Function(String project, String auditType, String key) onDelete;

  const _MemoryGroupCard({
    required this.groupLabel,
    required this.notes,
    required this.onAdd,
    required this.onEdit,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final project = text(notes.first['project']);
    final auditType = text(notes.first['auditType']);
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(groupLabel, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                ),
                IconButton(
                  icon: const Icon(Icons.add),
                  tooltip: 'Nieuwe tip',
                  onPressed: () => onAdd(project, auditType),
                ),
              ],
            ),
            for (final note in notes)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(text(note['key'])),
                subtitle: Text(text(note['content'])),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      icon: const Icon(Icons.edit_outlined),
                      onPressed: () => onEdit(project, auditType, text(note['key']), text(note['content'])),
                    ),
                    IconButton(
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () => onDelete(project, auditType, text(note['key'])),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}
