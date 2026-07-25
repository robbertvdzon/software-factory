import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../features/projects/branch_timeline_models.dart';
import '../widgets/branch_timeline_tiles.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// Build- en deploystatus van precies één branch/PR (de branch van de huidige story), bereikbaar
/// via "Buildstraat" in het acties-menu op het story-scherm. Zelfde bouwstenen als de ingebedde
/// sectie op het Projects-scherm (zie [BranchTimelineRowCard] in branch_timeline_tiles.dart), maar
/// dan altijd op de volle pagina en gefilterd tot die ene branch — de backend levert nog steeds alle
/// branches (main + open PR's) via `/api/v1/projects/{name}/branch-timeline`, hier wordt er client-
/// side ééntje uitgepikt.
///
/// Ververst alleen handmatig (knop in de AppBar + pull-to-refresh, via `DataScreen`), geen
/// automatische SSE-herlaad — zelfde reden als bij de Projects-pagina (zie
/// `DataScreen.autoRefreshOnChange`).
class BuildstraatScreen extends StatefulWidget {
  final AppState state;
  final String projectName;
  final String branchName;
  final int? prNumber;

  const BuildstraatScreen({
    super.key,
    required this.state,
    required this.projectName,
    required this.branchName,
    this.prNumber,
  });

  @override
  State<BuildstraatScreen> createState() => _BuildstraatScreenState();
}

class _BuildstraatScreenState extends State<BuildstraatScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  // Gezet als side-effect van _fetch, vlak vóór het bijbehorende builder()-frame — geen eigen
  // state-notificatie nodig omdat DataScreen na _fetch() toch al opnieuw rendert.
  bool _usedMergedPrFallback = false;

  bool _isOwnRow(BranchTimelineRow row) {
    final prNumber = widget.prNumber;
    if (prNumber != null && prNumber > 0 && row.prNumber == prNumber) return true;
    return row.branchName == widget.branchName;
  }

  BranchTimelineRow? _ownRow(BranchTimelinePageData page) {
    for (final row in page.rows) {
      if (_isOwnRow(row)) return row;
    }
    return null;
  }

  BranchTimelineRow? _mainRow(BranchTimelinePageData page) {
    for (final row in page.rows) {
      if (row.isMain) return row;
    }
    return null;
  }

  /// Haalt eerst de gewone branch-timeline op (main + open PR's). Staat de story-branch/PR daar niet
  /// (meer) bij — bv. omdat de PR inmiddels gemerged is en de backend alleen main + open PR's kent —
  /// probeert dit alsnog het merge-commit van die PR op te halen (nieuwe endpoint, werkt ook op een
  /// gemergede PR) vóórdat er wordt teruggevallen op de main-tip, die na een merge al snel niks meer
  /// over déze story zegt (er kunnen intussen talloze andere commits/bump-merges overheen zijn).
  Future<Map<String, dynamic>> _fetch(ApiClient api) async {
    final data = await api.getJson('/api/v1/projects/${widget.projectName}/branch-timeline');
    _usedMergedPrFallback = false;
    final prNumber = widget.prNumber;
    if (prNumber == null || prNumber <= 0) return data;

    final page = BranchTimelinePageData.fromJson(data);
    if (page.rows.any(_isOwnRow)) return data;

    final mergedData = await api.getJson(
      '/api/v1/projects/${widget.projectName}/branch-timeline/pr/$prNumber',
    );
    final mergedPage = BranchTimelinePageData.fromJson(mergedData);
    if (mergedPage.rows.isNotEmpty) {
      _usedMergedPrFallback = true;
      return mergedData;
    }
    return data;
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Buildstraat',
      subtitle: widget.branchName,
      autoRefreshOnChange: false,
      fetch: _fetch,
      actions: (context) => [
        IconButton(
          icon: const Icon(Icons.refresh),
          tooltip: 'Ververs',
          onPressed: () => _dataScreenKey.currentState?.reload(),
        ),
      ],
      builder: (context, data) {
        final page = BranchTimelinePageData.fromJson(data);
        final ownRow = _ownRow(page);
        final row = ownRow ?? _mainRow(page);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final error in page.errors)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: ErrorBanner(error),
              ),
            if (_usedMergedPrFallback && ownRow != null)
              const Padding(
                padding: EdgeInsets.only(bottom: 8),
                child: Text(
                  'Deze branch is gemerged — dit toont de build- en deploystatus van het merge-commit.',
                  style: TextStyle(color: Colors.black54, fontSize: 12.5),
                ),
              )
            else if (ownRow == null && row != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  "Branch '${widget.branchName}' niet gevonden (nog geen commit gepusht, of de PR is gesloten zonder te mergen) — dit toont de status van main.",
                  style: const TextStyle(color: Colors.black54, fontSize: 12.5),
                ),
              ),
            if (row == null)
              const EmptyState('Nog geen build- of deploygegevens gevonden.')
            else
              BranchTimelineRowCard(row: row),
          ],
        );
      },
    );
  }
}
