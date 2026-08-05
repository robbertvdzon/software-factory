import 'dart:async';

import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// De vijf opruimmechanismen die de factory logt, in de volgorde waarin ze op het scherm staan.
/// Bewust dezelfde letterlijke waarden als `CleanupKinds` in de backend: het zijn geen vertaalde
/// labels maar de soort-sleutel zelf, zodat een blok in het scherm één-op-één te herleiden is tot de
/// opruimer. De lijst is vast en komt niet uit de data, zodat een soort die nog nooit iets logde
/// tóch zichtbaar is met zijn knoppen.
const cleanupKinds = <String>[
  'github-releases',
  'agent-events',
  'agent-runs',
  'completion-payloads',
  'workspaces',
];

/// De `kind`-waarde van de "Alles draaien"-knop — geen opruimsoort maar de verzamelopdracht.
/// Spiegelt `CleanupKinds.ALL_KINDS` in de backend.
const allCleanupKinds = 'all';

/// Hoe vaak het scherm herlaadt zolang de backend meldt dat er nog een ronde loopt. Kort genoeg om
/// een afgeronde ronde vanzelf te zien verschijnen, lang genoeg om de bridge niet te belasten.
const _pollInterval = Duration(seconds: 3);

/// Onder deze breedte stapelen de titel en de knoppen van een actieblok, zodat het scherm op een
/// telefoon binnen de breedte blijft. Zelfde drempel als de vervallen knoppenbalk gebruikte.
const _stackBelowWidth = 560.0;

/// Het Opruimen-scherm: één blok per opruimactie met het resultaat van de laatste ronde
/// (verwijderd / blijft staan / duur), de knop **Nu draaien** en de knop **Runs bekijken** naar de
/// historie van alléén die actie ([CleanupRunsScreen]). De samenvatting komt uit het `summary`-veld
/// van `/api/v1/maintenance/cleanups`: de laatste ronde per soort, en voor `github-releases` per
/// project.
///
/// Stateful omdat het scherm zelf herlaadt na het starten van een ronde en doorpollt zolang de
/// backend soorten als draaiend meldt.
class MaintenanceScreen extends StatefulWidget {
  final AppState state;
  const MaintenanceScreen({super.key, required this.state});

  @override
  State<MaintenanceScreen> createState() => _MaintenanceScreenState();
}

class _MaintenanceScreenState extends State<MaintenanceScreen> {
  /// Nodig om te herladen zonder de [DataScreen] opnieuw op te bouwen: na het starten van een ronde
  /// en tijdens het pollen. Zelfde patroon als `audit_screen.dart`.
  final _dataScreenKey = GlobalKey<DataScreenState>();

  /// De soort waarvoor nu een start-verzoek onderweg is; zolang die gezet is staan alle knoppen uit,
  /// zodat twee snelle klikken nooit twee verzoeken opleveren.
  String? _busyKind;

  /// Loopt alleen zolang de backend soorten als "draait" meldt; zie [_syncPolling].
  Timer? _pollTimer;

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<Map<String, dynamic>> _fetch(ApiClient api) => api.getJson('/api/v1/maintenance/cleanups');

  Future<void> _reload() async => _dataScreenKey.currentState?.reload();

  /// Herlaadt licht door zolang er nog een ronde loopt en stopt zodra er niets meer draait; zo
  /// verschijnt het resultaat van een afgeronde ronde vanzelf zonder permanent te pollen.
  void _syncPolling(List<String> runningKinds) {
    if (runningKinds.isEmpty) {
      _pollTimer?.cancel();
      _pollTimer = null;
    } else {
      _pollTimer ??= Timer.periodic(_pollInterval, (_) => _reload());
    }
  }

  /// Een soort is te starten zolang er geen eigen verzoek loopt én de backend 'm niet als draaiend
  /// meldt (ook als die ronde uit een tweede tab of van de scheduler komt). "Alles draaien" blijft
  /// beschikbaar zolang er nog minstens één vrije soort is.
  bool _canStart(String kind, List<String> runningKinds) {
    if (_busyKind != null) return false;
    return kind == allCleanupKinds
        ? !cleanupKinds.every(runningKinds.contains)
        : !runningKinds.contains(kind);
  }

  Future<void> _runNow(String kind) async {
    setState(() => _busyKind = kind);
    try {
      final result = await widget.state.api.postJson('/api/v1/maintenance/run', {'kind': kind});
      if (!mounted) return;
      final started = boolValue(result['started']);
      final status = text(result['status']);
      showActionResult(
        context,
        // "draait al" is geen fout maar een normale uitkomst — de completion-payload-purge bezet de
        // bewaking elke ~2s kortstondig. Alleen een echte weigering wordt rood.
        success: started || status == 'already_running',
        message: _runNowMessage(status, started, kind, stringMap(result['kinds'])),
      );
      // Ook na een weigering herladen: `runningKinds` is dan net veranderd en zet de knoppen goed.
      await _reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busyKind = null);
    }
  }

  /// Meldtekst per status van `maintenance.runNow`. [started] (= "verzoek geaccepteerd") is de
  /// fallback voor een backend die nog geen `status` meestuurt — zelfde opzet als `audit_screen.dart`.
  String _runNowMessage(String status, bool started, String kind, Map<String, String> kinds) {
    if (kind == allCleanupKinds && kinds.isNotEmpty) return _allKindsMessage(kinds);
    switch (status) {
      case 'started':
        return 'Opruimronde gestart voor $kind.';
      case 'already_running':
        return 'De opruimronde voor $kind draait al.';
      case 'disabled':
        return 'De opruimer $kind staat uit; er is niets gestart.';
      case 'unknown_kind':
        return 'Onbekende opruimsoort: $kind.';
      default:
        return started ? 'Opruimronde gestart voor $kind.' : 'Kon de opruimronde niet starten.';
    }
  }

  /// "Alles draaien" start de vrije soorten en slaat de bezette/uitgezette over; het antwoord noemt
  /// allebei, zodat zichtbaar is wat er níet gebeurd is.
  String _allKindsMessage(Map<String, String> kinds) {
    final started = kinds.entries.where((entry) => entry.value == 'started').map((entry) => entry.key);
    final skipped = kinds.entries
        .where((entry) => entry.value != 'started')
        .map((entry) => '${entry.key} (${_skipReason(entry.value)})');
    final startedPart = started.isEmpty ? 'Niets gestart' : 'Gestart: ${started.join(', ')}';
    return skipped.isEmpty ? '$startedPart.' : '$startedPart. Overgeslagen: ${skipped.join(', ')}.';
  }

  String _skipReason(String status) {
    switch (status) {
      case 'already_running':
        return 'draait al';
      case 'disabled':
        return 'uit';
      case 'unknown_kind':
        return 'onbekend';
      default:
        return status;
    }
  }

  /// Eén knop bovenaan die alle opruimers achter elkaar draait; de melding noemt gestarte én
  /// overgeslagen soorten.
  Widget _runAllBar(List<String> runningKinds) => Panel(
        child: Align(
          alignment: Alignment.centerLeft,
          child: TextButton(
            key: const Key('run-now-all'),
            onPressed: _canStart(allCleanupKinds, runningKinds) ? () => _runNow(allCleanupKinds) : null,
            child: const Text('Alles draaien'),
          ),
        ),
      );

  /// Eén blok per opruimactie: naam, het resultaat van de laatste ronde (bij `github-releases` een
  /// regel per project) en de twee knoppen.
  Widget _kindPanel(String kind, List<Map<String, dynamic>> entries, List<String> runningKinds) {
    final title = Text(kind, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16));
    final actions = Wrap(
      spacing: 4,
      children: [
        TextButton(
          key: Key('run-now-$kind'),
          onPressed: _canStart(kind, runningKinds) ? () => _runNow(kind) : null,
          child: const Text('Nu draaien'),
        ),
        TextButton(
          key: Key('view-runs-$kind'),
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute(builder: (_) => CleanupRunsScreen(state: widget.state, kind: kind)),
          ),
          child: const Text('Runs bekijken'),
        ),
      ],
    );

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LayoutBuilder(
            builder: (context, constraints) {
              if (constraints.maxWidth < _stackBelowWidth) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [title, actions],
                );
              }
              return Row(children: [Expanded(child: title), actions]);
            },
          ),
          const SizedBox(height: 4),
          if (entries.isEmpty)
            const Text(
              'laatste ronde: geen wijzigingen gelogd',
              style: TextStyle(color: Colors.black54),
            )
          else
            for (final entry in entries)
              _CleanupResultLine(entry: entry, showProject: kind == 'github-releases'),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Opruimen',
      fetch: _fetch,
      builder: (context, data) {
        // errors zijn losse strings, niet maps — asList verwacht maps, dus de ruwe lijst hier
        // direct pakken i.p.v. via asList.
        final rawErrors = (data['errors'] as List? ?? []).map((e) => e.toString()).toList();
        final runningKinds = (data['runningKinds'] as List? ?? []).map((e) => e.toString()).toList();
        final summary = asList(data['summary']);
        _syncPolling(runningKinds);

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (rawErrors.isNotEmpty) ...[
              for (final error in rawErrors) ErrorBanner(error),
              const SizedBox(height: 12),
            ],
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: _runAllBar(runningKinds),
            ),
            for (final kind in cleanupKinds)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _kindPanel(
                  kind,
                  summary.where((entry) => text(entry['kind']) == kind).toList(),
                  runningKinds,
                ),
              ),
          ],
        );
      },
    );
  }
}

/// Het resultaat van één gelogde ronde binnen een actieblok: wanneer hij liep, de badges en de
/// label/waarde-paren. Alles in [Wrap]s, zodat een smal scherm de elementen stapelt in plaats van
/// horizontaal te scrollen.
class _CleanupResultLine extends StatelessWidget {
  final Map<String, dynamic> entry;

  /// Alleen `github-releases` draait per project; de andere soorten zijn factory-breed.
  final bool showProject;

  const _CleanupResultLine({required this.entry, required this.showProject});

  @override
  Widget build(BuildContext context) {
    final project = text(entry['project']);
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 4,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (showProject && project.isNotEmpty)
                Text(project, style: const TextStyle(fontWeight: FontWeight.w700)),
              Text(
                'laatste ronde: ${formatTimestamp(entry['startedAt'])}',
                style: const TextStyle(color: Colors.black54, fontSize: 13),
              ),
              if (boolValue(entry['dryRun'])) const StatusBadge('dry-run', BadgeTone.warn),
              if (isManual(entry)) const StatusBadge('handmatig', BadgeTone.active),
              if (boolValue(entry['failed'])) const StatusBadge('fout', BadgeTone.bad),
            ],
          ),
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Wrap(
              spacing: 16,
              runSpacing: 2,
              children: [
                _CleanupPair('verwijderd', '${number(entry['itemsDeleted'])}'),
                _CleanupPair('blijft staan', '${number(entry['itemsKept'])}'),
                _CleanupPair('duur', formatCleanupDuration(entry['startedAt'], entry['finishedAt'])),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Eén label/waarde-paar uit het resultaat, bijvoorbeeld "verwijderd: 3".
class _CleanupPair extends StatelessWidget {
  final String label;
  final String value;
  const _CleanupPair(this.label, this.value);

  @override
  Widget build(BuildContext context) =>
      Text('$label: $value', style: const TextStyle(color: Colors.black54, fontSize: 13));
}

/// Historie van alléén [kind], nieuwste eerst — achter de knop "Runs bekijken". Eigen pagina met
/// eigen [Scaffold]/[AppBar], net als het rondedetail; tikken op een ronde opent dat detail
/// ongewijzigd.
class CleanupRunsScreen extends StatefulWidget {
  final AppState state;
  final String kind;
  const CleanupRunsScreen({super.key, required this.state, required this.kind});

  @override
  State<CleanupRunsScreen> createState() => _CleanupRunsScreenState();
}

class _CleanupRunsScreenState extends State<CleanupRunsScreen> {
  late final Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.state.api.getJson('/api/v1/maintenance/cleanups?kind=${widget.kind}');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Rondes: ${widget.kind}')),
      body: FutureBuilder<Map<String, dynamic>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(snapshot.error.toString()));
          }
          final data = snapshot.data ?? {};
          final runs = asList(data['runs']);
          return Align(
            alignment: Alignment.topLeft,
            child: ConstrainedBox(
              // Zelfde max-breedte als DataScreen en het rondedetail.
              constraints: const BoxConstraints(maxWidth: 860),
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (runs.isEmpty)
                    const EmptyState('Nog geen opruimrondes.')
                  else
                    for (final run in runs)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: _CleanupRunTile(state: widget.state, run: run),
                      ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

/// Eén ronde in de historie: tijdstip, badges, de aantallen en de doorstap naar het rondedetail.
class _CleanupRunTile extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> run;
  const _CleanupRunTile({required this.state, required this.run});

  @override
  Widget build(BuildContext context) => Panel(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          title: Wrap(
            spacing: 8,
            runSpacing: 4,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(
                formatTimestamp(run['startedAt']),
                style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
              ),
              StatusBadge(text(run['kind'], fallback: 'onbekend'), BadgeTone.neutral),
              if (boolValue(run['dryRun'])) const StatusBadge('dry-run', BadgeTone.warn),
              if (isManual(run)) const StatusBadge('handmatig', BadgeTone.active),
              if (boolValue(run['failed'])) const StatusBadge('fout', BadgeTone.bad),
            ],
          ),
          subtitle: Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              cleanupCountsLine(run),
              style: const TextStyle(color: Colors.black54, fontSize: 13),
            ),
          ),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => _MaintenanceRunDetailScreen(state: state, runId: number(run['id'])),
            ),
          ),
        ),
      );
}

/// "SF — 3 opgeruimd / 12 bewaard" — bij een dry-run gaat het om de *geplande* aantallen, want er is
/// dan niets echt verwijderd. Factory-brede rondes hebben geen project en tonen dat ook niet.
String cleanupCountsLine(Map<String, dynamic> run) {
  final project = text(run['project']);
  final verb = boolValue(run['dryRun']) ? 'zou worden opgeruimd' : 'opgeruimd';
  final counts = '${number(run['itemsDeleted'])} $verb / ${number(run['itemsKept'])} bewaard';
  return project.isEmpty ? counts : '$project — $counts';
}

/// Hoe lang een ronde duurde, leesbaar: `1 m 7 s`, `43 s`, `< 1 s` bij een ronde onder de seconde en
/// `-` als er geen (geldig) begin- of eindtijdstip is. De duur komt uit `startedAt`/`finishedAt` van
/// de gelogde rij; de backend rekent 'm bewust niet voor, zodat het scherm dezelfde velden gebruikt
/// als de rest van de weergave.
String formatCleanupDuration(dynamic startedAt, dynamic finishedAt) {
  final start = DateTime.tryParse(text(startedAt));
  final end = DateTime.tryParse(text(finishedAt));
  if (start == null || end == null) return '-';
  final seconds = end.difference(start).inSeconds;
  if (seconds < 1) return '< 1 s';
  if (seconds < 60) return '$seconds s';
  return '${seconds ~/ 60} m ${seconds % 60} s';
}

/// Detail van één opruimronde: de aantallen en — als de ronde misging — de foutmelding. Voor
/// `github-releases` ook de uitsplitsing naar releases/package-versions en de verwijderde namen;
/// de andere soorten hebben niets uit te splitsen. Eigen pagina (net als het auditrapport-detail)
/// omdat de opsommingen lang kunnen worden.
class _MaintenanceRunDetailScreen extends StatefulWidget {
  final AppState state;
  final int runId;
  const _MaintenanceRunDetailScreen({required this.state, required this.runId});

  @override
  State<_MaintenanceRunDetailScreen> createState() => _MaintenanceRunDetailScreenState();
}

class _MaintenanceRunDetailScreenState extends State<_MaintenanceRunDetailScreen> {
  late final Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.state.api.getJson('/api/v1/maintenance/cleanups/${widget.runId}');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Opruimronde')),
      body: FutureBuilder<Map<String, dynamic>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(snapshot.error.toString()));
          }
          final run = snapshot.data ?? {};
          final error = text(run['error']);
          final project = text(run['project']);
          final isGitHubCleanup = text(run['kind']) == 'github-releases';
          return Align(
            alignment: Alignment.topLeft,
            child: ConstrainedBox(
              // Zelfde max-breedte als DataScreen en het auditrapport-detail.
              constraints: const BoxConstraints(maxWidth: 860),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            formatTimestamp(run['startedAt']),
                            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.only(left: 8),
                          child: StatusBadge(text(run['kind'], fallback: 'onbekend'), BadgeTone.neutral),
                        ),
                        if (boolValue(run['dryRun']))
                          const Padding(
                            padding: EdgeInsets.only(left: 8),
                            child: StatusBadge('dry-run', BadgeTone.warn),
                          ),
                        if (isManual(run))
                          const Padding(
                            padding: EdgeInsets.only(left: 8),
                            child: StatusBadge('handmatig', BadgeTone.active),
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 16,
                      children: [
                        // Factory-brede rondes horen bij geen enkel project; dan ook geen lege regel.
                        if (project.isNotEmpty)
                          Text('Project: $project', style: const TextStyle(color: Colors.black54)),
                        Text('Klaar: ${formatTimestamp(run['finishedAt'])}',
                            style: const TextStyle(color: Colors.black54)),
                        Text(
                          'Opgeruimd: ${number(run['itemsDeleted'])}, '
                          '${number(run['itemsKept'])} bewaard',
                          style: const TextStyle(color: Colors.black54),
                        ),
                        if (isGitHubCleanup) ...[
                          Text(
                            'Releases: ${number(run['releasesDeleted'])} opgeruimd, '
                            '${number(run['releasesKept'])} bewaard',
                            style: const TextStyle(color: Colors.black54),
                          ),
                          Text(
                            'Package-versions: ${number(run['packagesDeleted'])} opgeruimd, '
                            '${number(run['packagesKept'])} bewaard',
                            style: const TextStyle(color: Colors.black54),
                          ),
                        ],
                      ],
                    ),
                    if (error.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      ErrorBanner(error),
                    ],
                    if (isGitHubCleanup) ...[
                      const Divider(height: 24),
                      _ItemList(title: 'Verwijderde releases', items: stringList(run['deletedReleaseTags'])),
                      const SizedBox(height: 16),
                      _ItemList(title: 'Verwijderde package-versions', items: stringList(run['deletedPackageVersions'])),
                    ],
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// `trigger` is `scheduled` of `manual` (zie `CleanupTriggers`); alleen een handmatig gestarte ronde
/// krijgt de "handmatig"-badge. Een backend zonder het veld valt terug op "gepland".
bool isManual(Map<String, dynamic> run) => text(run['trigger']) == 'manual';

/// De `kinds`-map uit het runNow-antwoord: per soort de status, als losse strings.
Map<String, String> stringMap(dynamic value) =>
    (value as Map?)?.map((key, item) => MapEntry(key.toString(), item.toString())) ?? const {};

/// De detailvelden zijn lijsten van losse strings (geen maps), dus [asList] past hier niet.
List<String> stringList(dynamic value) => (value as List? ?? []).map((item) => item.toString()).toList();

class _ItemList extends StatelessWidget {
  final String title;
  final List<String> items;
  const _ItemList({required this.title, required this.items});

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      SectionTitle(title),
      if (items.isEmpty)
        const Text('Niets verwijderd.', style: TextStyle(color: Colors.black54))
      else
        for (final item in items)
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Text('• $item'),
          ),
    ],
  );
}
