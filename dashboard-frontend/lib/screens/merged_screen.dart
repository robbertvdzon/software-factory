import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

class MergedScreen extends StatelessWidget {
  final AppState state;
  const MergedScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Merged',
      fetch: (api) => api.getJson('/api/v1/merged'),
      builder: (context, data) {
        final runs = asList(data['mergedRuns']);
        if (runs.isEmpty) {
          return const EmptyState('Nog geen gemergede stories.');
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final run in runs)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Panel(
                  child: ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(text(run['storyKey'])),
                    subtitle: Text(
                      'PR ${text(run['prNumber'], fallback: '-')} · ${formatTimestamp(run['endedAt'])}',
                    ),
                    trailing: text(run['prUrl']).isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.open_in_new),
                            onPressed: () => launchUrl(
                              Uri.parse(text(run['prUrl'])),
                              mode: LaunchMode.externalApplication,
                            ),
                          )
                        : null,
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// Samengevoegde Projects/Builds/Downloads-pagina (SF-samenvoeging): per project altijd zichtbaar
/// de status + huidige live-versie(s)+uptime, en een uitklapbare sectie met builds en downloads —
/// scheelt heen-en-weer-tabben tussen wat voorheen drie losse schermen waren.
