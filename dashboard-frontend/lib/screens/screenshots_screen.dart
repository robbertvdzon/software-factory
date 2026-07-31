import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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
                      storyKey: storyKey,
                      screenshots: screenshots,
                      initialIndex: index,
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

/// Volledig scherm-viewer over de hele screenshotlijst van één story: bladeren met swipe
/// (`PageView`, niet circulair), met pijlknoppen en pijltjestoetsen op desktop. De lijst is een
/// momentopname van de al opgehaalde `screenshots`-respons; de viewer haalt zelf niets opnieuw op.
class _ScreenshotViewer extends StatefulWidget {
  final ApiClient api;
  final String storyKey;
  final List<Map<String, dynamic>> screenshots;
  final int initialIndex;
  const _ScreenshotViewer({
    required this.api,
    required this.storyKey,
    required this.screenshots,
    required this.initialIndex,
  });

  @override
  State<_ScreenshotViewer> createState() => _ScreenshotViewerState();
}

class _ScreenshotViewerState extends State<_ScreenshotViewer> {
  late final PageController _controller;
  late int _index;

  @override
  void initState() {
    super.initState();
    _index = widget.initialIndex;
    _controller = PageController(initialPage: _index);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool get _hasMultiple => widget.screenshots.length > 1;

  /// Bladert naar [target] als die bestaat; buiten de lijst gebeurt er niets (geen wrap).
  void _goTo(int target) {
    if (target < 0 || target >= widget.screenshots.length) return;
    _controller.animateToPage(
      target,
      duration: const Duration(milliseconds: 200),
      curve: Curves.easeOut,
    );
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) return KeyEventResult.ignored;
    if (event.logicalKey == LogicalKeyboardKey.arrowLeft) {
      _goTo(_index - 1);
      return KeyEventResult.handled;
    }
    if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
      _goTo(_index + 1);
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final current = widget.screenshots[_index];
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Expanded(
              child: Text(text(current['name']), maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
            if (_hasMultiple)
              Padding(
                padding: const EdgeInsets.only(left: 12),
                child: Text(
                  '${_index + 1} / ${widget.screenshots.length}',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                ),
              ),
          ],
        ),
      ),
      body: Focus(
        autofocus: true,
        onKeyEvent: _onKey,
        child: Stack(
          children: [
            PageView.builder(
              controller: _controller,
              itemCount: widget.screenshots.length,
              onPageChanged: (index) => setState(() => _index = index),
              itemBuilder: (context, index) => _ZoomableScreenshot(
                imageUrl: widget.api.url(
                  '/api/v1/stories/${widget.storyKey}/screenshots/${text(widget.screenshots[index]['id'])}/image',
                ),
                headers: widget.api.authHeaders(),
              ),
            ),
            if (_hasMultiple) ...[
              _NavArrow(
                alignment: Alignment.centerLeft,
                icon: Icons.chevron_left,
                tooltip: 'Vorige screenshot',
                onPressed: _index > 0 ? () => _goTo(_index - 1) : null,
              ),
              _NavArrow(
                alignment: Alignment.centerRight,
                icon: Icons.chevron_right,
                tooltip: 'Volgende screenshot',
                onPressed: _index < widget.screenshots.length - 1 ? () => _goTo(_index + 1) : null,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Eén viewer-pagina: inzoombaar, maar pannen pas zodra er daadwerkelijk ingezoomd is — anders
/// slikt de `InteractiveViewer` de horizontale sleep op en kan er niet meer geswipet worden.
class _ZoomableScreenshot extends StatefulWidget {
  final String imageUrl;
  final Map<String, String> headers;
  const _ZoomableScreenshot({required this.imageUrl, required this.headers});

  @override
  State<_ZoomableScreenshot> createState() => _ZoomableScreenshotState();
}

class _ZoomableScreenshotState extends State<_ZoomableScreenshot> {
  final TransformationController _transformation = TransformationController();
  bool _zoomed = false;

  @override
  void initState() {
    super.initState();
    _transformation.addListener(_onTransformationChanged);
  }

  void _onTransformationChanged() {
    final zoomed = _transformation.value.getMaxScaleOnAxis() > 1.01;
    if (zoomed != _zoomed) setState(() => _zoomed = zoomed);
  }

  @override
  void dispose() {
    _transformation.removeListener(_onTransformationChanged);
    _transformation.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => InteractiveViewer(
    transformationController: _transformation,
    panEnabled: _zoomed,
    minScale: 1,
    maxScale: 4,
    child: Center(
      child: Image.network(
        widget.imageUrl,
        headers: widget.headers,
        loadingBuilder: (_, child, progress) =>
            progress == null ? child : const Center(child: CircularProgressIndicator()),
        errorBuilder: (_, __, ___) => const Center(child: Icon(Icons.broken_image_outlined)),
      ),
    ),
  );
}

class _NavArrow extends StatelessWidget {
  final Alignment alignment;
  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  const _NavArrow({
    required this.alignment,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) => Align(
    alignment: alignment,
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: IconButton.filledTonal(
        icon: Icon(icon),
        tooltip: tooltip,
        onPressed: onPressed,
      ),
    ),
  );
}
