import 'dart:math' as math;
import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../features/roadmap/roadmap_models.dart';
import '../main.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

class RoadmapScreen extends StatefulWidget {
  final AppState state;
  const RoadmapScreen({super.key, required this.state});

  @override
  State<RoadmapScreen> createState() => _RoadmapScreenState();
}

class _RoadmapScreenState extends State<RoadmapScreen> {
  final _dataKey = GlobalKey<DataScreenState>();

  @override
  Widget build(BuildContext context) => DataScreen(
    key: _dataKey,
    state: widget.state,
    title: 'Roadmap',
    subtitle:
        'Dependencies bepalen wat eerst moet; daarbinnen telt jouw klant-rank voor 75%.',
    maxContentWidth: 1240,
    actions: (context) => [
      IconButton(
        key: const Key('create-epic'),
        tooltip: 'Nieuwe epic',
        icon: const Icon(Icons.add),
        onPressed: () => _createEpic(context),
      ),
    ],
    fetch: (api) => api.getJson('/api/v1/roadmap'),
    builder: (context, data) {
      final epics = asList(data['epics']).map(RoadmapEpic.fromJson).toList()
        ..sort((a, b) => a.roadmapRank.compareTo(b.roadmapRank));
      if (epics.isEmpty) {
        return const EmptyState(
          'Nog geen epics. Maak de eerste epic aan met de + knop.',
        );
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _RankingLegend(
            customerWeight: number(data['customerWeightPercentage']),
            processWeight: number(data['processWeightPercentage']),
          ),
          const SizedBox(height: 16),
          _RoadmapGraph(
            epics: epics,
            onOpen: (epic) => _editEpic(context, epic, epics),
            onMove: (epic, rank) => _moveEpic(context, epic, epics, rank),
          ),
        ],
      );
    },
  );

  Future<void> _createEpic(BuildContext context) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _CreateEpicDialog(api: widget.state.api),
    );
    if (saved == true) await _dataKey.currentState?.reload();
  }

  Future<void> _editEpic(
    BuildContext context,
    RoadmapEpic epic,
    List<RoadmapEpic> epics,
  ) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) =>
          _EditEpicDialog(api: widget.state.api, epic: epic, allEpics: epics),
    );
    if (saved == true) await _dataKey.currentState?.reload();
  }

  Future<void> _moveEpic(
    BuildContext context,
    RoadmapEpic epic,
    List<RoadmapEpic> epics,
    int rank,
  ) async {
    if (rank < 1 || rank > epics.length) return;
    try {
      await _saveEpic(widget.state.api, epic, customerRank: rank);
      await _dataKey.currentState?.reload();
    } catch (error) {
      if (context.mounted) {
        showActionResult(context, success: false, message: error.toString());
      }
    }
  }
}

class _RankingLegend extends StatelessWidget {
  final int customerWeight;
  final int processWeight;
  const _RankingLegend({
    required this.customerWeight,
    required this.processWeight,
  });

  @override
  Widget build(BuildContext context) => Panel(
    child: Wrap(
      spacing: 18,
      runSpacing: 8,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text(
          'Rangberekening',
          style: Theme.of(
            context,
          ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800),
        ),
        Text('Klant $customerWeight%'),
        Text('Roadmap-proces $processWeight%'),
        const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.arrow_forward, size: 18),
            SizedBox(width: 5),
            Text('dependency'),
          ],
        ),
      ],
    ),
  );
}

class _RoadmapGraph extends StatelessWidget {
  final List<RoadmapEpic> epics;
  final ValueChanged<RoadmapEpic> onOpen;
  final void Function(RoadmapEpic epic, int rank) onMove;
  const _RoadmapGraph({
    required this.epics,
    required this.onOpen,
    required this.onMove,
  });

  @override
  Widget build(BuildContext context) {
    final layout = RoadmapGraphLayout.forEpics(epics);
    return SizedBox(
      height: math.max(layout.height + 36, 220),
      child: Container(
        key: const Key('roadmap-graph'),
        width: double.infinity,
        decoration: BoxDecoration(
          color: const Color(0xfff7f6f3),
          borderRadius: BorderRadius.circular(16),
        ),
        padding: const EdgeInsets.all(18),
        child: InteractiveViewer(
          constrained: false,
          minScale: .65,
          maxScale: 1.4,
          boundaryMargin: const EdgeInsets.all(80),
          child: SizedBox(
            width: math.max(layout.width, 224),
            height: math.max(layout.height, 142),
            child: Stack(
              children: [
                Positioned.fill(
                  child: CustomPaint(
                    painter: _DependencyPainter(epics, layout),
                  ),
                ),
                for (final epic in epics)
                  Positioned(
                    left: layout.positions[epic.id]!.x,
                    top: layout.positions[epic.id]!.y,
                    width: RoadmapGraphLayout.cardWidth,
                    height: RoadmapGraphLayout.cardHeight,
                    child: _EpicCard(
                      epic: epic,
                      total: epics.length,
                      onOpen: onOpen,
                      onMove: onMove,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _EpicCard extends StatelessWidget {
  final RoadmapEpic epic;
  final int total;
  final ValueChanged<RoadmapEpic> onOpen;
  final void Function(RoadmapEpic epic, int rank) onMove;
  const _EpicCard({
    required this.epic,
    required this.total,
    required this.onOpen,
    required this.onMove,
  });

  @override
  Widget build(BuildContext context) {
    final blocked = epic.blockedByIds.isNotEmpty;
    final tone = switch (epic.status) {
      'done' => SfColors.green,
      'in_progress' => SfColors.blue,
      _ when blocked => SfColors.amber,
      _ => SfColors.accent,
    };
    return Material(
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: tone, width: blocked ? 2 : 1),
      ),
      child: InkWell(
        key: Key('epic-${epic.id}'),
        borderRadius: BorderRadius.circular(14),
        onTap: () => onOpen(epic),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  CircleAvatar(
                    radius: 16,
                    backgroundColor: tone,
                    foregroundColor: Colors.white,
                    child: Text(
                      '${epic.roadmapRank}',
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  const Spacer(),
                  _RankButton(
                    icon: Icons.arrow_upward,
                    enabled: epic.customerRank > 1,
                    onTap: () => onMove(epic, epic.customerRank - 1),
                  ),
                  _RankButton(
                    icon: Icons.arrow_downward,
                    enabled: epic.customerRank < total,
                    onTap: () => onMove(epic, epic.customerRank + 1),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                epic.title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontWeight: FontWeight.w800,
                  fontSize: 15,
                ),
              ),
              const Spacer(),
              Text(
                'Klant ${epic.customerRank}  ·  Proces ${epic.processRank}',
                style: const TextStyle(fontSize: 12, color: SfColors.muted),
              ),
              if (blocked)
                const Text(
                  'Geblokkeerd',
                  style: TextStyle(
                    fontSize: 12,
                    color: SfColors.amber,
                    fontWeight: FontWeight.w700,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RankButton extends StatelessWidget {
  final IconData icon;
  final bool enabled;
  final VoidCallback onTap;
  const _RankButton({
    required this.icon,
    required this.enabled,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 32,
    height: 32,
    child: IconButton(
      padding: EdgeInsets.zero,
      iconSize: 17,
      onPressed: enabled ? onTap : null,
      icon: Icon(icon),
    ),
  );
}

class _DependencyPainter extends CustomPainter {
  final List<RoadmapEpic> epics;
  final RoadmapGraphLayout layout;
  _DependencyPainter(this.epics, this.layout);

  @override
  void paint(Canvas canvas, Size size) {
    final line = Paint()
      ..color = SfColors.faint
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;
    final arrow = Paint()
      ..color = SfColors.faint
      ..style = PaintingStyle.fill;
    for (final epic in epics) {
      final endNode = layout.positions[epic.id]!;
      for (final dependencyId in epic.dependencyIds) {
        final startNode = layout.positions[dependencyId];
        if (startNode == null) continue;
        final start = Offset(
          startNode.x + RoadmapGraphLayout.cardWidth,
          startNode.y + RoadmapGraphLayout.cardHeight / 2,
        );
        final end = Offset(
          endNode.x - 8,
          endNode.y + RoadmapGraphLayout.cardHeight / 2,
        );
        final midX = (start.dx + end.dx) / 2;
        final path = Path()
          ..moveTo(start.dx, start.dy)
          ..cubicTo(midX, start.dy, midX, end.dy, end.dx, end.dy);
        canvas.drawPath(path, line);
        canvas.drawPath(
          Path()
            ..moveTo(end.dx, end.dy)
            ..lineTo(end.dx - 9, end.dy - 5)
            ..lineTo(end.dx - 9, end.dy + 5)
            ..close(),
          arrow,
        );
      }
    }
  }

  @override
  bool shouldRepaint(covariant _DependencyPainter oldDelegate) =>
      oldDelegate.epics != epics;
}

class _CreateEpicDialog extends StatefulWidget {
  final ApiClient api;
  const _CreateEpicDialog({required this.api});

  @override
  State<_CreateEpicDialog> createState() => _CreateEpicDialogState();
}

class _CreateEpicDialogState extends State<_CreateEpicDialog> {
  final title = TextEditingController();
  final description = TextEditingController();
  bool saving = false;
  String? error;

  @override
  void dispose() {
    title.dispose();
    description.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Nieuwe epic'),
    content: SizedBox(
      width: 520,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('epic-title'),
            controller: title,
            maxLength: 80,
            autofocus: true,
            decoration: const InputDecoration(labelText: 'Korte titel'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: description,
            minLines: 3,
            maxLines: 6,
            decoration: const InputDecoration(
              labelText: 'Uitgebreide omschrijving',
            ),
          ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(error!, style: const TextStyle(color: SfColors.red)),
            ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: saving ? null : () => Navigator.pop(context, false),
        child: const Text('Annuleren'),
      ),
      FilledButton(
        onPressed: saving ? null : _save,
        child: const Text('Aanmaken'),
      ),
    ],
  );

  Future<void> _save() async {
    if (title.text.trim().isEmpty) {
      return setState(() => error = 'Titel is verplicht.');
    }
    setState(() {
      saving = true;
      error = null;
    });
    try {
      await widget.api.postJson('/api/v1/roadmap/epics', {
        'title': title.text.trim(),
        'description': description.text.trim(),
      });
      if (mounted) Navigator.pop(context, true);
    } catch (failure) {
      if (mounted) {
        setState(() {
          saving = false;
          error = failure.toString();
        });
      }
    }
  }
}

class _EditEpicDialog extends StatefulWidget {
  final ApiClient api;
  final RoadmapEpic epic;
  final List<RoadmapEpic> allEpics;
  const _EditEpicDialog({
    required this.api,
    required this.epic,
    required this.allEpics,
  });

  @override
  State<_EditEpicDialog> createState() => _EditEpicDialogState();
}

class _EditEpicDialogState extends State<_EditEpicDialog> {
  late final TextEditingController title;
  late final TextEditingController description;
  late final TextEditingController rank;
  late String status;
  late Set<int> dependencies;
  bool saving = false;
  String? error;

  @override
  void initState() {
    super.initState();
    title = TextEditingController(text: widget.epic.title);
    description = TextEditingController(text: widget.epic.description);
    rank = TextEditingController(text: '${widget.epic.customerRank}');
    status = widget.epic.status;
    dependencies = {...widget.epic.dependencyIds};
  }

  @override
  void dispose() {
    title.dispose();
    description.dispose();
    rank.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text('Epic #${widget.epic.roadmapRank}'),
    content: SizedBox(
      width: 560,
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: title,
              maxLength: 80,
              decoration: const InputDecoration(labelText: 'Korte titel'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: description,
              minLines: 3,
              maxLines: 7,
              decoration: const InputDecoration(
                labelText: 'Uitgebreide omschrijving',
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    key: const Key('customer-rank'),
                    controller: rank,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Jouw klant-rank',
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: InputDecorator(
                    decoration: const InputDecoration(
                      labelText: 'Process-rank',
                    ),
                    child: Text('${widget.epic.processRank}'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            DropdownButtonFormField<String>(
              initialValue: status,
              decoration: const InputDecoration(labelText: 'Status'),
              items: const [
                DropdownMenuItem(value: 'planned', child: Text('Gepland')),
                DropdownMenuItem(value: 'in_progress', child: Text('Bezig')),
                DropdownMenuItem(value: 'done', child: Text('Afgerond')),
              ],
              onChanged: (value) => setState(() => status = value!),
            ),
            const SizedBox(height: 18),
            const Text(
              'Afhankelijk van',
              style: TextStyle(fontWeight: FontWeight.w800),
            ),
            if (widget.allEpics.length == 1)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('Geen andere epics beschikbaar.'),
              ),
            for (final candidate in widget.allEpics.where(
              (candidate) => candidate.id != widget.epic.id,
            ))
              CheckboxListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text('#${candidate.roadmapRank} ${candidate.title}'),
                value: dependencies.contains(candidate.id),
                onChanged: (checked) => setState(
                  () => checked == true
                      ? dependencies.add(candidate.id)
                      : dependencies.remove(candidate.id),
                ),
              ),
            if (widget.epic.rankExplanation.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(
                  widget.epic.rankExplanation,
                  style: const TextStyle(color: SfColors.amber),
                ),
              ),
            if (error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  error!,
                  style: const TextStyle(color: SfColors.red),
                ),
              ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: saving ? null : () => Navigator.pop(context, false),
        child: const Text('Annuleren'),
      ),
      FilledButton(
        onPressed: saving ? null : _save,
        child: const Text('Opslaan'),
      ),
    ],
  );

  Future<void> _save() async {
    final parsedRank = int.tryParse(rank.text);
    if (title.text.trim().isEmpty || parsedRank == null || parsedRank < 1) {
      return setState(
        () => error = 'Vul een titel en een geldige klant-rank in.',
      );
    }
    setState(() {
      saving = true;
      error = null;
    });
    try {
      await widget.api.postJson('/api/v1/roadmap/epics/${widget.epic.id}', {
        'title': title.text.trim(),
        'description': description.text.trim(),
        'status': status,
        'customerRank': parsedRank,
        'dependencyIds': dependencies.toList(),
      });
      if (mounted) Navigator.pop(context, true);
    } catch (failure) {
      if (mounted) {
        setState(() {
          saving = false;
          error = failure.toString();
        });
      }
    }
  }
}

Future<void> _saveEpic(
  ApiClient api,
  RoadmapEpic epic, {
  required int customerRank,
}) => api.postJson('/api/v1/roadmap/epics/${epic.id}', {
  'title': epic.title,
  'description': epic.description,
  'status': epic.status,
  'customerRank': customerRank,
  'dependencyIds': epic.dependencyIds.toList(),
});
