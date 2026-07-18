import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';
import 'package:softwarefactory_dashboard/screens/app_updates_screen.dart';

void main() {
  const channel = MethodChannel('nl.vdzon.softwarefactory.softwarefactory_dashboard/updater');

  Future<void> pumpScreen(
    WidgetTester tester, {
    required List<Map<String, dynamic>> downloads,
    List<MethodCall> recordedCalls = const [],
  }) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient();
    final state = AppState(api);

    final mockClient = MockClient((request) async {
      if (request.url.path.endsWith('/api/v1/downloads')) {
        return http.Response(jsonEncode({'downloads': downloads, 'errors': <String>[]}), 200);
      }
      return http.Response('Not found', 404);
    });

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(home: AppUpdatesScreen(state: state)));
      await tester.pumpAndSettle();
    }, () => mockClient);
  }

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('toont geen apps als er niets gevonden is', (tester) async {
    await pumpScreen(tester, downloads: const []);
    expect(find.textContaining("Geen APK's gevonden"), findsOneWidget);
  });

  testWidgets('groepeert apk\'s per app en toont "Installeren"-knop per app', (tester) async {
    await pumpScreen(tester, downloads: [
      {
        'repository': 'robbertvdzon/robberts-assistent',
        'projectKey': 'robberts-assistent',
        'name': 'app-release.apk',
        'size': 43500000,
        'createdAt': '2026-07-11T23:18:00Z',
        'downloadUrl': 'https://example.com/wind.apk',
        'releaseTag': 'wind-latest',
      },
      {
        'repository': 'robbertvdzon/software-factory',
        'projectKey': 'softwarefactory',
        'name': 'software-factory-dashboard.apk',
        'size': 51600000,
        'createdAt': '2026-07-15T10:30:00Z',
        'downloadUrl': 'https://example.com/dashboard.apk',
        'releaseTag': 'dashboard-apk-20260715-103000-abc1234',
      },
    ]);

    expect(find.text('Wind'), findsOneWidget);
    expect(find.text('Software Factory Dashboard'), findsOneWidget);
    expect(find.text('Installeren'), findsNWidgets(2));
    expect(find.textContaining('Nog niet via dit scherm geïnstalleerd'), findsNWidgets(2));
  });

  testWidgets('tikken op Installeren roept de native install-brug aan en markeert daarna als up-to-date', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'canInstallPackages') return true;
      return null;
    });

    await pumpScreen(tester, downloads: [
      {
        'repository': 'robbertvdzon/software-factory',
        'projectKey': 'softwarefactory',
        'name': 'software-factory-dashboard.apk',
        'size': 51600000,
        'createdAt': '2026-07-15T10:30:00Z',
        'downloadUrl': 'https://example.com/dashboard.apk',
        'releaseTag': 'dashboard-apk-20260715-103000-abc1234',
      },
    ]);

    await tester.tap(find.text('Installeren'));
    await tester.pumpAndSettle();

    expect(calls.map((c) => c.method), containsAllInOrder(['canInstallPackages', 'downloadAndInstall']));
    final downloadCall = calls.firstWhere((c) => c.method == 'downloadAndInstall');
    expect(downloadCall.arguments['url'], 'https://example.com/dashboard.apk');
    expect(downloadCall.arguments['fileName'], 'software-factory-dashboard.apk');
    debugDefaultTargetPlatformOverride = null;
  });

  testWidgets('geen installatie-toestemming stuurt naar systeeminstellingen en toont een foutmelding', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'canInstallPackages') return false;
      return null;
    });

    await pumpScreen(tester, downloads: [
      {
        'repository': 'robbertvdzon/software-factory',
        'projectKey': 'softwarefactory',
        'name': 'software-factory-dashboard.apk',
        'size': 51600000,
        'createdAt': '2026-07-15T10:30:00Z',
        'downloadUrl': 'https://example.com/dashboard.apk',
        'releaseTag': 'dashboard-apk-20260715-103000-abc1234',
      },
    ]);

    await tester.tap(find.text('Installeren'));
    await tester.pumpAndSettle();

    expect(calls.map((c) => c.method), ['canInstallPackages', 'requestInstallPermission']);
    expect(find.textContaining('installeren van deze app toestaan'), findsOneWidget);
    debugDefaultTargetPlatformOverride = null;
  });
}
