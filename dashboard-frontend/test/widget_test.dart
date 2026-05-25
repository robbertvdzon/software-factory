import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/main.dart';

void main() {
  testWidgets('shows login screen', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const SoftwareFactoryDashboard());
    await tester.pumpAndSettle();

    expect(find.text('Software Factory'), findsOneWidget);
    expect(find.text('Inloggen'), findsOneWidget);
  });
}
