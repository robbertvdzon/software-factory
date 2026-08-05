import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:softwarefactory_dashboard/main.dart';
import 'package:softwarefactory_dashboard/phase_stepper.dart';

/// SF-1959 — de hotfix-subtaak heeft een eigen tak in [SubtaskPhaseStepper] en valt dus niet meer
/// in de grijze default-tak (die het ruwe subtaaktype als label toont).
void main() {
  Future<void> pumpStepper(WidgetTester tester, String subtaskType, String phase) async {
    await tester.pumpWidget(
      MaterialApp(home: Scaffold(body: SubtaskPhaseStepper(subtaskType: subtaskType, phase: phase))),
    );
    await tester.pumpAndSettle();
  }

  /// De vulkleur van het stap-bolletje: grijs = niet gestart, geel = bezig, groen = goedgekeurd.
  Color fillColor(WidgetTester tester) {
    final container = tester.widget<Container>(
      find.descendant(of: find.byType(SizedBox).first, matching: find.byType(Container)).first,
    );
    return ((container.decoration! as BoxDecoration).color)!;
  }

  testWidgets('een hotfix-subtaak toont één Hotfix-stap, niet de grijze default', (tester) async {
    await pumpStepper(tester, 'hotfix', 'developing');

    expect(find.text('Hotfix'), findsOneWidget);
    expect(find.text('hotfix'), findsNothing, reason: 'het ruwe type is het default-tak-label');
    expect(find.text('Reviewen'), findsNothing, reason: 'een hotfix heeft geen reviewstap');
    expect(fillColor(tester), SfColors.amberSoft, reason: 'developing = bezig');
  });

  testWidgets('hotfix-approved kleurt de stap groen', (tester) async {
    await pumpStepper(tester, 'hotfix', 'hotfix-approved');

    expect(fillColor(tester), SfColors.greenSoft);
  });

  testWidgets('een nog niet gestarte hotfix-subtaak blijft grijs', (tester) async {
    await pumpStepper(tester, 'hotfix', '');

    expect(fillColor(tester), SfColors.bg);
  });

  testWidgets('een onbekend subtaaktype valt nog steeds in de default-tak', (tester) async {
    await pumpStepper(tester, 'iets-nieuws', 'start');

    expect(find.text('iets-nieuws'), findsOneWidget);
  });
}
