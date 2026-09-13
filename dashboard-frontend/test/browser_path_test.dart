import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:softwarefactory_dashboard/browser_path.dart';

/// De adresbalk volgt het zichtbare scherm: het grondpad van de shell, tijdelijk overdekt
/// door een gepusht detailscherm, en altijd als vervanging (geen extra browsergeschiedenis).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<MethodCall> calls;

  setUp(() {
    BrowserPath.reset();
    calls = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.navigation,
      (call) async {
        calls.add(call);
        return null;
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.navigation,
      null,
    );
    BrowserPath.reset();
  });

  List<String> reported() => [
    for (final call in calls)
      if (call.method == 'routeInformationUpdated') (call.arguments as Map)['uri'] as String,
  ];

  test('grondpad, detail erbovenop en weer terug', () async {
    BrowserPath.showRoot('/stories');
    BrowserPath.push('/stories/SF-1');
    BrowserPath.pop('/stories/SF-1');
    await Future<void>.delayed(Duration.zero);

    expect(reported(), ['/stories', '/stories/SF-1', '/stories']);
    expect(BrowserPath.current, '/stories');
  });

  test('een nieuw grondpad vervangt het oude, ook onder een open detail', () async {
    BrowserPath.showRoot('/stories');
    BrowserPath.showRoot('/settings');
    await Future<void>.delayed(Duration.zero);

    expect(reported(), ['/stories', '/settings']);
  });

  test('het grondpad wordt nooit weggepopt', () async {
    BrowserPath.showRoot('/stories');
    BrowserPath.pop('/stories');

    expect(BrowserPath.current, '/stories');
  });

  test('de adresbalk wordt vervangen, niet aangevuld', () async {
    BrowserPath.showRoot('/agents');
    await Future<void>.delayed(Duration.zero);

    final call = calls.singleWhere((c) => c.method == 'routeInformationUpdated');
    expect((call.arguments as Map)['replace'], isTrue);
  });
}
