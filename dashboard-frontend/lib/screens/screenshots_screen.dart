import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// Screenshots-galerij (§9): tester-screenshots als kaart-grid, elk kaartje opent de
/// afbeelding op volledige grootte. Elk plaatje is een los `screenshot.get`-verzoek
/// (base64 over de bridge, met size-cap — zie docs/ontwerp-bridge-dashboard.md §5/§11).
class ScreenshotsScreen extends StatelessWidget {
  final AppState state;
  final String storyKey;
  const ScreenshotsScreen({super.key, required this.state, required this.storyKey});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: '$storyKey screenshots',
      fetch: (api) => api.getJson('/api/v1/stories/$storyKey/screenshots'),
      builder: (context, data) {
        final screenshots = asList(data['screenshots']);
        if (screenshots.isEmpty) {
          return const EmptyState('Nog geen tester-screenshots gevonden.');
        }
        return GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
            maxCrossAxisExtent: 220,
            mainAxisSpacing: 12,
            crossAxisSpacing: 12,
            childAspectRatio: 0.9,
          ),
          itemCount: screenshots.length,
          itemBuilder: (context, index) {
            final shot = screenshots[index];
            final imageUrl = state.api.url(
              '/api/v1/stories/$storyKey/screenshots/${text(shot['id'])}/image',
            );
            return Card(
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => _ScreenshotViewer(
                      api: state.api,
                      title: text(shot['name']),
                      imageUrl: imageUrl,
                    ),
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Expanded(
                      child: Image.network(
                        imageUrl,
                        headers: state.api.authHeaders(),
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => const Center(child: Icon(Icons.broken_image_outlined)),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(8),
                      child: Text(
                        text(shot['name']),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}

class _ScreenshotViewer extends StatelessWidget {
  final ApiClient api;
  final String title;
  final String imageUrl;
  const _ScreenshotViewer({required this.api, required this.title, required this.imageUrl});

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(title)),
    body: InteractiveViewer(
      child: Center(child: Image.network(imageUrl, headers: api.authHeaders())),
    ),
  );
}
