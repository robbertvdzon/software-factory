import 'package:flutter/material.dart';

import 'app_state.dart';
import 'main.dart';
import 'widgets/common.dart';

/// Soort actie waarop een mens moet reageren — 1-op-1 met core/HumanActionPolicy.kt's HumanGate,
/// plus een aparte kind voor de manual-approve-poort (die via het commando-mechanisme loopt in
/// plaats van een fase-write, zie SubtaskExecutionCoordinator/ManualCommandService).
enum PendingKind { question, approval, manualGate, manualApprove }

/// Wat er moet gebeuren om een wachtend issue verder te helpen: welk soort actie, welke
/// doelfase(n) (of commando's bij [PendingKind.manualApprove]), en de tekst voor de kaart.
/// 1-op-1 met de oude Kotlin ActionCards.kt-tabel (storyActionCard/subtaskActionCard).
class PendingAction {
  final PendingKind kind;
  final String label;
  final String? note;
  final String approveTarget;
  final String? rejectTarget;
  const PendingAction({
    required this.kind,
    required this.label,
    this.note,
    required this.approveTarget,
    this.rejectTarget,
  });
}

/// Bepaalt of — en zo ja welke — mens-actie een story/subtaak-fase vereist. Zie
/// core/HumanActionPolicy.kt (gateFor) en web/views/shared/ActionCards.kt voor de brontabel.
PendingAction? pendingActionFor({required bool isStory, required String phase, String? subtaskType}) {
  if (isStory) {
    switch (phase) {
      case 'refined-with-questions':
        return const PendingAction(kind: PendingKind.question, label: 'Vraag van de refiner', approveTarget: 'questions-answered');
      case 'planned-with-questions':
        return const PendingAction(kind: PendingKind.question, label: 'Vraag van de planner', approveTarget: 'planning-questions-answered');
      case 'refined':
        return const PendingAction(
          kind: PendingKind.approval,
          label: 'Refinement beoordelen',
          note: 'De refiner is klaar. Keur goed om door te gaan, of stuur terug met feedback.',
          approveTarget: 'refined-approved',
          rejectTarget: 'refined-rejected',
        );
      case 'planned':
        return const PendingAction(
          kind: PendingKind.approval,
          label: 'Plan beoordelen',
          note: 'De planner heeft het plan afgerond. Keur goed om te starten, of stuur terug met feedback.',
          approveTarget: 'planning-approved',
          rejectTarget: 'planning-rejected',
        );
      default:
        return null;
    }
  }
  switch (phase) {
    case 'awaiting-human':
      return const PendingAction(
        kind: PendingKind.manualGate,
        label: 'Handmatige actie afronden',
        note: 'De factory wacht op een handmatige stap. Markeer als klaar zodra je het hebt gedaan.',
        approveTarget: 'manual-action-done',
      );
    case 'manual-approve-needed':
      return const PendingAction(
        kind: PendingKind.manualApprove,
        label: 'Handmatige goedkeuring',
        note: 'De factory wacht vóór de merge op je goedkeuring. Keur goed om door te gaan, of keur af met een reden om de hele story opnieuw uit te voeren.',
        approveTarget: 'approve',
        rejectTarget: 'reject',
      );
    case 'developed-with-questions':
      return const PendingAction(kind: PendingKind.question, label: 'Vraag van de developer', approveTarget: 'development-questions-answered');
    case 'reviewed-with-questions':
      return const PendingAction(kind: PendingKind.question, label: 'Vraag van de reviewer', approveTarget: 'review-questions-answered');
    case 'tested-with-questions':
      return const PendingAction(kind: PendingKind.question, label: 'Vraag van de tester', approveTarget: 'test-questions-answered');
    case 'summary-with-questions':
      return const PendingAction(kind: PendingKind.question, label: 'Vraag van de summarizer', approveTarget: 'summary-questions-answered');
    case 'developed':
      if (subtaskType?.toLowerCase() != 'development') return null;
      return const PendingAction(
        kind: PendingKind.approval,
        label: 'Ontwikkeling beoordelen',
        note: 'De developer heeft de wijziging geïmplementeerd en gepusht. Bekijk het resultaat en keur goed, of stuur terug met feedback.',
        approveTarget: 'development-approved',
        rejectTarget: 'development-rejected',
      );
    case 'reviewed':
      return const PendingAction(
        kind: PendingKind.approval,
        label: 'Review beoordelen',
        note: 'De reviewer is klaar. Keur de review goed, of stuur terug met feedback.',
        approveTarget: 'review-approved',
        rejectTarget: 'review-rejected',
      );
    case 'tested':
      return const PendingAction(
        kind: PendingKind.approval,
        label: 'Test beoordelen',
        note: 'De tester is klaar. Keur het testresultaat goed, of stuur terug met feedback.',
        approveTarget: 'test-approved',
        rejectTarget: 'test-rejected',
      );
    case 'summarized':
      return const PendingAction(
        kind: PendingKind.approval,
        label: 'Samenvatting beoordelen',
        note: 'De samenvatting is klaar. Keur goed, of stuur terug met feedback.',
        approveTarget: 'summary-approved',
        rejectTarget: 'summary-rejected',
      );
    default:
      return null;
  }
}

/// Kaart voor precies één wachtende actie: tekstveld bij een vraag, approve/reject bij een
/// goedkeur-fase, of de manual-approve-poort (via het commando-mechanisme). Gebruikt op het
/// story-scherm (eigen fase + per-subtaak-banner), het subtaak-scherm en in My actions — één
/// implementatie i.p.v. drie losse, uit elkaar lopende knoppenrijen.
class PendingActionCard extends StatefulWidget {
  final AppState state;
  final String issueKey;
  final bool isStory;
  final PendingAction action;
  final String? question;
  final VoidCallback onDone;
  const PendingActionCard({
    super.key,
    required this.state,
    required this.issueKey,
    required this.isStory,
    required this.action,
    this.question,
    required this.onDone,
  });

  @override
  State<PendingActionCard> createState() => _PendingActionCardState();
}

class _PendingActionCardState extends State<PendingActionCard> {
  final _controller = TextEditingController();
  var _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit(String target, {required bool isReject}) async {
    final comment = _controller.text.trim();
    if (widget.action.kind == PendingKind.question && comment.isEmpty) return;
    if (widget.action.kind == PendingKind.manualApprove && isReject && comment.isEmpty) return;
    setState(() => _busy = true);
    try {
      if (widget.action.kind == PendingKind.manualApprove) {
        await widget.state.api.postJson(
          '/api/v1/stories/${widget.issueKey}/command/$target',
          {if (comment.isNotEmpty) 'reason': comment},
        );
      } else {
        await widget.state.api.postJson(
          widget.isStory ? '/api/v1/stories/${widget.issueKey}/story-phase' : '/api/v1/subtasks/${widget.issueKey}/phase',
          {'phase': target, if (comment.isNotEmpty) 'comment': comment},
        );
      }
      if (!mounted) return;
      showActionResult(context, success: true, message: 'Verwerkt.');
      widget.onDone();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final action = widget.action;
    final isQuestion = action.kind == PendingKind.question;
    final isManualGate = action.kind == PendingKind.manualGate;
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(action.label, style: const TextStyle(fontWeight: FontWeight.w800))),
              StatusBadge(widget.issueKey, BadgeTone.warn),
            ],
          ),
          if (action.note != null) ...[
            const SizedBox(height: 4),
            Text(action.note!, style: const TextStyle(color: SfColors.muted)),
          ],
          if (widget.question != null && widget.question!.isNotEmpty) ...[
            const SizedBox(height: 8),
            SelectableText(widget.question!),
          ],
          const SizedBox(height: 8),
          TextField(
            controller: _controller,
            minLines: 2,
            maxLines: 4,
            enabled: !_busy,
            onChanged: isQuestion ? (_) => setState(() {}) : null,
            decoration: InputDecoration(
              hintText: isQuestion
                  ? 'Jouw antwoord'
                  : (isManualGate ? 'Notitie (optioneel)' : 'Reden (optioneel, verplicht bij afkeuren)'),
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              if (isQuestion)
                FilledButton(
                  onPressed: _busy || _controller.text.trim().isEmpty ? null : () => _submit(action.approveTarget, isReject: false),
                  child: const Text('Antwoord versturen'),
                )
              else if (isManualGate)
                FilledButton(
                  onPressed: _busy ? null : () => _submit(action.approveTarget, isReject: false),
                  child: const Text('Mark done'),
                )
              else ...[
                FilledButton(
                  onPressed: _busy ? null : () => _submit(action.approveTarget, isReject: false),
                  child: const Text('Approve'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  style: OutlinedButton.styleFrom(foregroundColor: SfColors.red),
                  onPressed: _busy || action.rejectTarget == null ? null : () => _submit(action.rejectTarget!, isReject: true),
                  child: const Text('Reject'),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}
