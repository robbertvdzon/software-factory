import 'package:flutter/material.dart';

import 'main.dart';
import 'widgets/common.dart';

/// Kleurstatus van één stap: grijs (niet gestart), geel (bezig), groen (goedgekeurd).
enum StepState { grey, yellow, green }

class _Step {
  final String label;
  final StepState state;
  const _Step(this.label, this.state);
}

Color _dot(StepState state) => switch (state) {
  StepState.grey => SfColors.faint,
  StepState.yellow => SfColors.amber,
  StepState.green => SfColors.green,
};

Color _fill(StepState state) => switch (state) {
  StepState.grey => SfColors.bg,
  StepState.yellow => SfColors.amberSoft,
  StepState.green => SfColors.greenSoft,
};

Color _border(StepState state) => switch (state) {
  StepState.grey => SfColors.line,
  StepState.yellow => SfColors.amber,
  StepState.green => SfColors.green,
};

class _StepRow extends StatelessWidget {
  final List<_Step> steps;
  const _StepRow(this.steps);

  @override
  Widget build(BuildContext context) => Panel(
    child: Wrap(
      spacing: 4,
      runSpacing: 12,
      children: [
        for (final step in steps)
          SizedBox(
            width: 76,
            child: Column(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(color: _fill(step.state), shape: BoxShape.circle, border: Border.all(color: _border(step.state))),
                  child: Center(child: Container(width: 10, height: 10, decoration: BoxDecoration(color: _dot(step.state), shape: BoxShape.circle))),
                ),
                const SizedBox(height: 6),
                Text(step.label, textAlign: TextAlign.center, style: const TextStyle(fontSize: 11, color: SfColors.muted)),
              ],
            ),
          ),
      ],
    ),
  );
}

const _refiningPhases = {'refining', 'refined-with-questions', 'questions-answered', 'refined', 'refined-rejected'};
const _planningPhases = {'planning', 'planned-with-questions', 'planning-questions-answered', 'planned', 'planning-rejected'};
const _pastRefining = {'refined-approved', 'planning-approved', 'in-progress', ..._planningPhases};

/// Story-fases (zie core/StoryPhase.kt): alleen refinen/plannen, op verzoek los van de
/// subtaak-keten getoond (die zie je pas als je een subtaak opent).
class StoryPhaseStepper extends StatelessWidget {
  final String phase;
  const StoryPhaseStepper({super.key, required this.phase});

  StepState _refiningState() {
    if (_refiningPhases.contains(phase)) return StepState.yellow;
    if (_pastRefining.contains(phase)) return StepState.green;
    return StepState.grey;
  }

  StepState _planningState() {
    if (_planningPhases.contains(phase)) return StepState.yellow;
    if (phase == 'planning-approved' || phase == 'in-progress') return StepState.green;
    return StepState.grey;
  }

  @override
  Widget build(BuildContext context) => _StepRow([
    _Step('Refinen', _refiningState()),
    _Step('Plannen', _planningState()),
  ]);
}

StepState _segmentState(String phase, {required Set<String> active, required String approved, Set<String> alsoGreenIf = const {}}) {
  if (phase == approved || alsoGreenIf.contains(phase)) return StepState.green;
  if (active.contains(phase)) return StepState.yellow;
  return StepState.grey;
}

/// Subtaak-fases (zie core/SubtaskPhase.kt), per `Subtask Type`. Een development-subtaak draagt
/// zowel de developer- als de reviewer-stap in dezelfde issue (zie SubtaskExecutionCoordinator)
/// en krijgt daarom 2 stappen; de rest heeft er 1.
class SubtaskPhaseStepper extends StatelessWidget {
  final String subtaskType;
  final String phase;
  const SubtaskPhaseStepper({super.key, required this.subtaskType, required this.phase});

  @override
  Widget build(BuildContext context) {
    const reviewPhases = {'reviewing', 'reviewed-with-questions', 'review-questions-answered', 'reviewed', 'review-rejected'};
    switch (subtaskType.toLowerCase()) {
      case 'development':
        final developState = _segmentState(
          phase,
          active: {'developing', 'developed-with-questions', 'development-questions-answered', 'developed', 'development-rejected'},
          approved: 'development-approved',
          alsoGreenIf: {...reviewPhases, 'review-approved'},
        );
        final reviewState = _segmentState(phase, active: reviewPhases, approved: 'review-approved');
        return _StepRow([_Step('Ontwikkelen', developState), _Step('Reviewen', reviewState)]);
      case 'test':
        return _StepRow([
          _Step(
            'Testen',
            _segmentState(phase, active: {'testing', 'tested-with-questions', 'test-questions-answered', 'tested', 'test-rejected'}, approved: 'test-approved'),
          ),
        ]);
      case 'summary':
        return _StepRow([
          _Step(
            'Samenvatten',
            _segmentState(
              phase,
              active: {'summarizing', 'summary-with-questions', 'summary-questions-answered', 'summarized', 'summary-rejected'},
              approved: 'summary-approved',
            ),
          ),
        ]);
      case 'documentation':
        return _StepRow([
          _Step(
            'Documenteren',
            _segmentState(
              phase,
              active: {'documenting', 'documentation-with-questions', 'documentation-questions-answered', 'documented'},
              approved: 'documentation-approved',
            ),
          ),
        ]);
      case 'manual':
        return _StepRow([
          _Step('Handmatige actie', _segmentState(phase, active: {'awaiting-human'}, approved: 'manual-action-done')),
        ]);
      case 'manual-approve':
        return _StepRow([
          _Step(
            'Goedkeuring',
            _segmentState(phase, active: {'manual-approve-needed', 'manually-not-approved'}, approved: 'manually-approved'),
          ),
        ]);
      case 'merge':
        return _StepRow([_Step('Mergen', _segmentState(phase, active: {'merging'}, approved: 'merge-approved'))]);
      case 'deploy':
        return _StepRow([
          _Step('Deployen', _segmentState(phase, active: {'deploying', 'deploy-failed'}, approved: 'deploy-approved')),
        ]);
      default:
        return _StepRow([_Step(subtaskType.isEmpty ? '-' : subtaskType, StepState.grey)]);
    }
  }
}
