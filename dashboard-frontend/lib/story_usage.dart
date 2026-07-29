/// Verbruik van één story: hoeveel agents er gedraaid hebben, hoe lang die samen bezig waren en
/// hoeveel tokens dat kostte. Komt van `usageByStory` uit `stories.list` (backend: `UiStoryUsage`),
/// geaggregeerd over álle story-runs van de story.
class StoryUsage {
  final int agentRuns;
  final int agentDurationMs;
  final int inputTokens;
  final int cacheReadTokens;
  final int cacheCreationTokens;
  final int outputTokens;

  const StoryUsage({
    this.agentRuns = 0,
    this.agentDurationMs = 0,
    this.inputTokens = 0,
    this.cacheReadTokens = 0,
    this.cacheCreationTokens = 0,
    this.outputTokens = 0,
  });

  static const empty = StoryUsage();

  factory StoryUsage.fromJson(Map<String, dynamic>? json) {
    if (json == null) return empty;
    int field(String key) {
      final value = json[key];
      if (value is int) return value;
      if (value is num) return value.toInt();
      return int.tryParse(value?.toString() ?? '') ?? 0;
    }

    return StoryUsage(
      agentRuns: field('agentRuns'),
      agentDurationMs: field('agentDurationMs'),
      inputTokens: field('inputTokens'),
      cacheReadTokens: field('cacheReadTokens'),
      cacheCreationTokens: field('cacheCreationTokens'),
      outputTokens: field('outputTokens'),
    );
  }

  int get totalTokens => inputTokens + cacheReadTokens + cacheCreationTokens + outputTokens;

  /// Geen enkele agent gedraaid — dan heeft een verbruiksregel niets te melden.
  bool get isEmpty => agentRuns == 0 && totalTokens == 0;

  /// Geschatte kosten **alsof alles op Claude Opus 4.5 gedraaid had**, in USD.
  ///
  /// Dit is bewust GEEN weergave van wat er echt betaald is: de factory draait op meerdere modellen
  /// (Opus 4.8, Sonnet 5, Haiku 4.5, …) en registreert de echte kosten per agent-run in
  /// `agent_runs.cost_usd_est`. Deze berekening beantwoordt een andere vraag — "wat zou dit
  /// ongeveer kosten als we hetzelfde tokenverbruik op Opus 4.5 zouden draaien" — omdat dat het
  /// enige beschikbare model is op de werkplek waar deze schatting voor bedoeld is.
  ///
  /// Tarieven per miljoen tokens (Opus 4.5): input $5,00 · cached input $0,50 · output $25,00.
  /// Cache-WRITE-tokens staan niet in dat tarievenlijstje en rekenen we hier tegen het gewone
  /// inputtarief; bij Anthropic kosten die in werkelijkheid 1,25× input, dus deze schatting valt
  /// voor dat deel iets te laag uit (in de praktijk enkele procenten op het totaal).
  double get costUsdOpus45 =>
      (inputTokens + cacheCreationTokens) / 1000000 * _opus45InputPerMillion +
      cacheReadTokens / 1000000 * _opus45CachedInputPerMillion +
      outputTokens / 1000000 * _opus45OutputPerMillion;

  static const _opus45InputPerMillion = 5.00;
  static const _opus45CachedInputPerMillion = 0.50;
  static const _opus45OutputPerMillion = 25.00;
}

/// Tokenaantal compact: `8,9M` / `312k` / `945`. Miljoenen en duizenden omdat een story al snel
/// miljoenen cache-read-tokens heeft — het rauwe getal is dan onleesbaar.
String formatTokens(int tokens) {
  if (tokens <= 0) return '-';
  if (tokens >= 1000000) return '${(tokens / 1000000).toStringAsFixed(1).replaceAll('.', ',')}M';
  if (tokens >= 1000) return '${(tokens / 1000).round()}k';
  return '$tokens';
}

/// Bedrag als `$12,34`. Onder een cent tonen we `<$0,01` in plaats van `$0,00`, zodat een story met
/// verbruik nooit als gratis oogt.
String formatUsd(double amount) {
  if (amount <= 0) return r'$0,00';
  if (amount < 0.01) return r'<$0,01';
  return '\$${amount.toStringAsFixed(2).replaceAll('.', ',')}';
}
