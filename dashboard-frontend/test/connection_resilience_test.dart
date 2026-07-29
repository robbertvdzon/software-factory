import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:softwarefactory_dashboard/api_client.dart';
import 'package:softwarefactory_dashboard/app_state.dart';

/// Regressietests voor de knipperende "Factory niet verbonden"-banner: de SSE-client mocht per
/// verbroken verbinding maar één nieuwe poging plannen (deed er meerdere, die zich vermenigvuldigden),
/// en één mislukte statuscall mocht de banner nog niet aanzetten.
void main() {
  group('AppState.refreshStatus', () {
    test('zet de banner pas aan na twee mislukte statuscalls op rij', () async {
      SharedPreferences.setMockInitialValues({});
      final state = AppState(ApiClient());

      await http.runWithClient(() async {
        await state.refreshStatus();
        expect(state.connected, isTrue, reason: 'één hik is nog geen offline factory');
        await state.refreshStatus();
        expect(state.connected, isFalse);
      }, () => MockClient((_) async => http.Response('boom', 500)));
    });

    test('een geslaagde call herstelt de banner en reset de teller', () async {
      SharedPreferences.setMockInitialValues({});
      final state = AppState(ApiClient());
      var failing = true;

      await http.runWithClient(() async {
        await state.refreshStatus();
        await state.refreshStatus();
        expect(state.connected, isFalse);

        failing = false;
        await state.refreshStatus();
        expect(state.connected, isTrue);

        // Na het herstel begint het tellen opnieuw: één mislukking laat de banner uit.
        failing = true;
        await state.refreshStatus();
        expect(state.connected, isTrue);
      }, () => MockClient((request) async {
            if (failing) return http.Response('boom', 500);
            return http.Response(
              jsonEncode({'connected': true, 'since': null, 'factoryVersion': null}),
              200,
            );
          }));
    });

    test('een expliciet connected:false van de backend toont de banner meteen', () async {
      SharedPreferences.setMockInitialValues({});
      final state = AppState(ApiClient());

      await http.runWithClient(() async {
        await state.refreshStatus();
        expect(state.connected, isFalse);
      }, () => MockClient((_) async => http.Response(
            jsonEncode({'connected': false, 'since': null, 'factoryVersion': null}),
            200,
          )));
    });
  });

  group('SseClient', () {
    test('negeert een tweede connect zolang de eerste nog loopt', () async {
      var attempts = 0;
      final sse = SseClient(ApiClient(), minBackoff: const Duration(milliseconds: 20));

      await http.runWithClient(() async {
        final first = sse.connect();
        await sse.connect(); // moet meteen terugkeren, niet een tweede verbinding opzetten
        await first;
      }, () => MockClient((_) async {
            attempts++;
            return http.Response('nope', 500);
          }));

      sse.dispose();
      expect(attempts, 1);
    });

    test('plant per verbroken verbinding precies één nieuwe poging', () async {
      var attempts = 0;
      final sse = SseClient(
        ApiClient(),
        minBackoff: const Duration(milliseconds: 20),
        maxBackoff: const Duration(milliseconds: 20),
      );

      await http.runWithClient(() async {
        await sse.connect();
        await Future<void>.delayed(const Duration(milliseconds: 110));
        sse.dispose();
      }, () => MockClient((_) async {
            attempts++;
            return http.Response('nope', 500);
          }));

      // 1 initiële poging + ~5 retries van 20ms. Bij de oude, zich vermenigvuldigende reconnect
      // liep dit in hetzelfde tijdsbestek exponentieel op.
      expect(attempts, lessThanOrEqualTo(7));
      expect(attempts, greaterThan(1), reason: 'er moet wél opnieuw geprobeerd worden');
    });

    test('probeert niets meer na dispose', () async {
      var attempts = 0;
      final sse = SseClient(
        ApiClient(),
        minBackoff: const Duration(milliseconds: 20),
        maxBackoff: const Duration(milliseconds: 20),
      );

      await http.runWithClient(() async {
        await sse.connect();
        sse.dispose();
        final afterDispose = attempts;
        await Future<void>.delayed(const Duration(milliseconds: 80));
        expect(attempts, afterDispose);
      }, () => MockClient((_) async {
            attempts++;
            return http.Response('nope', 500);
          }));
    });
  });
}
