import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// Changelog van een project: `short_description_summary` per opgeleverde story, nieuwste eerst
/// (zelfde data als de publieke `/api/v1/public/changelog/{project}`-endpoint, hier via de
/// geauthenticeerde bridge). Layout naar het patroon van `BriefingScreen` in story_detail_screen.dart.
class ChangelogScreen extends StatelessWidget {
  final AppState state;
  final String projectName;
  const ChangelogScreen({
    super.key,
    required this.state,
    required this.projectName,
  });

  @override
  Widget build(BuildContext context) => DataScreen(
    state: state,
    title: 'Changelog — $projectName',
    // Encoderen: projectnamen met spaties/speciale tekens komen sinds SF-2087 ook uit de URL.
    fetch: (api) => api.getJson('/api/v1/changelog/${Uri.encodeComponent(projectName)}'),
    builder: (context, data) {
      final entries = asList(data['entries']);
      if (entries.isEmpty) {
        return const EmptyState('Nog geen changelog-items voor dit project.');
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final entry in entries)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      formatTimestamp(entry['timestamp']),
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 4),
                    SelectableText(text(entry['shortDescriptionSummary'])),
                  ],
                ),
              ),
            ),
        ],
      );
    },
  );
}
