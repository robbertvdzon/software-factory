import 'package:flutter/material.dart';

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

  BranchTimelineRow? _matchingRow(BranchTimelinePageData page) {
    final prNumber = widget.prNumber;
    if (prNumber != null && prNumber > 0) {
      for (final row in page.rows) {
        if (row.prNumber == prNumber) return row;
      }
    }
    for (final row in page.rows) {
      if (row.branchName == widget.branchName) return row;
    }
    return null;
  }

  BranchTimelineRow? _mainRow(BranchTimelinePageData page) {
    for (final row in page.rows) {
      if (row.isMain) return row;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Buildstraat',
      subtitle: widget.branchName,
      autoRefreshOnChange: false,
      fetch: (api) => api.getJson('/api/v1/projects/${widget.projectName}/branch-timeline'),
      actions: (context) => [
        IconButton(
          icon: const Icon(Icons.refresh),
          tooltip: 'Ververs',
          onPressed: () => _dataScreenKey.currentState?.reload(),
        ),
      ],
      builder: (context, data) {
        final page = BranchTimelinePageData.fromJson(data);
        // De feature-branch verdwijnt zodra de PR gemerged (of ge-closed) is — de backend levert
        // alleen main + nog open PR's. Dat betekende voorheen een lege pagina zodra je 'm na het
        // mergen opende, precies wanneer je juist wilt zien of main al aan het bouwen/deployen is.
        // Val daarom terug op de main-rij i.p.v. "geen data" te tonen.
        final ownRow = _matchingRow(page);
        final row = ownRow ?? _mainRow(page);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final error in page.errors)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: ErrorBanner(error),
              ),
            if (ownRow == null && row != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  "Branch '${widget.branchName}' niet gevonden (waarschijnlijk gemerged, of nog geen commit gepusht) — dit toont de status van main.",
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
