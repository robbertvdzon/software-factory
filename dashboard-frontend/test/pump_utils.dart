import 'package:flutter_test/flutter_test.dart';

/// Vervanger voor `tester.pumpAndSettle()` op schermen met een doorlopende `Timer.periodic`
/// (bv. de looptijd-ticker in `AgentsScreen` of de poll-timer in `AgentLogScreen`).
///
/// `pumpAndSettle()` blijft pompen zolang er een frame gepland staat; een periodieke timer die
/// telkens opnieuw `setState` aanroept plant altijd weer een nieuw frame, dus `pumpAndSettle()`
/// settelt nooit en loopt vast op een timeout. Deze helper pompt in plaats daarvan een vast
/// aantal stappen (standaard 10 x 100ms = 1s), genoeg om async fetches, `setState`-updates en
/// route-transitie-animaties (~300ms) af te ronden zonder afhankelijk te zijn van "settelen".
Future<void> pumpUntilSettled(
  WidgetTester tester, {
  int steps = 10,
  Duration step = const Duration(milliseconds: 100),
}) async {
  for (var i = 0; i < steps; i++) {
    await tester.pump(step);
  }
}
