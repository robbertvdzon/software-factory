import 'package:flutter_test/flutter_test.dart';

import 'package:softwarefactory_dashboard/main.dart';

void main() {
  testWidgets('shows login screen', (WidgetTester tester) async {
    await tester.pumpWidget(const SoftwareFactoryDashboard());

    expect(find.text('Software Factory'), findsOneWidget);
    expect(find.text('Inloggen'), findsOneWidget);
  });
}
