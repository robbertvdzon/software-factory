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
        final row = _matchingRow(page);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final error in page.errors)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: ErrorBanner(error),
              ),
            if (row == null)
              const EmptyState('Nog geen build- of deploygegevens voor deze branch.')
            else
              BranchTimelineRowCard(row: row),
          ],
        );
      },
    );
  }
}
