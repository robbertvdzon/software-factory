import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/story_usage.dart';

void main() {
  group('StoryUsage', () {
    test('kosten volgen de Opus 4.5-tarieven per miljoen tokens', () {
      // 1M input ($5) + 1M cache-write (ook inputtarief, $5) + 10M cache read ($5) + 1M output ($25)
      const usage = StoryUsage(
        agentRuns: 8,
        inputTokens: 1000000,
        cacheCreationTokens: 1000000,
        cacheReadTokens: 10000000,
        outputTokens: 1000000,
      );
      expect(usage.costUsdOpus45, closeTo(40.0, 0.0001));
    });

    test('een realistische story komt op een bedrag van enkele tientjes', () {
      // Mediaanstory uit de productiedatabase (juli 2026).
      const usage = StoryUsage(
        agentRuns: 8,
        inputTokens: 13978,
        cacheReadTokens: 4302452,
        cacheCreationTokens: 258194,
        outputTokens: 45052,
      );
      // 0,272M × $5 ($1,36) + 4,302M × $0,50 ($2,15) + 0,045M × $25 ($1,13) ≈ $4,64
      expect(usage.costUsdOpus45, closeTo(4.64, 0.01));
    });

    test('parseert de backend-json en telt de tokens op', () {
      final usage = StoryUsage.fromJson({
        'storyKey': 'SF-1',
        'agentRuns': 3,
        'agentDurationMs': 754000,
        'inputTokens': 100,
        'cacheReadTokens': 200,
        'cacheCreationTokens': 300,
        'outputTokens': 400,
      });
      expect(usage.agentRuns, 3);
      expect(usage.agentDurationMs, 754000);
      expect(usage.totalTokens, 1000);
      expect(usage.isEmpty, isFalse);
    });

    test('ontbrekende usage is leeg en toont dus geen verbruiksregel', () {
      expect(StoryUsage.fromJson(null).isEmpty, isTrue);
      expect(StoryUsage.fromJson(const {}).isEmpty, isTrue);
    });
  });

  group('formatters', () {
    test('tokens compact vanaf duizend- en miljoentallen', () {
      expect(formatTokens(0), '-');
      expect(formatTokens(945), '945');
      expect(formatTokens(312400), '312k');
      expect(formatTokens(8900000), '8,9M');
    });

    test('bedragen met komma; onder een cent nooit als gratis tonen', () {
      expect(formatUsd(0), r'$0,00');
      expect(formatUsd(0.004), r'<$0,01');
      expect(formatUsd(12.3456), r'$12,35');
    });
  });
}
