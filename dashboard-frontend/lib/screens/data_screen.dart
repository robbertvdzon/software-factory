import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';

/// Generiek scherm: haalt één page-data-endpoint op, ververst automatisch op
/// [AppState.changedTick] (de "changed"-SSE-push, geen refresh-knop nodig) en toont
/// een offline-banner + foutmelding + pull-to-refresh als fallback.
class DataScreen extends StatefulWidget {
  final AppState state;
  final String title;
  final String? subtitle;
  final Future<Map<String, dynamic>> Function(ApiClient api) fetch;
  final Widget Function(BuildContext context, Map<String, dynamic> data) builder;
  final List<Widget> Function(BuildContext context)? actions;

  const DataScreen({
    super.key,
    required this.state,
    required this.title,
    this.subtitle,
    required this.fetch,
    required this.builder,
    this.actions,
  });

  @override
  State<DataScreen> createState() => DataScreenState();
}

class DataScreenState extends State<DataScreen> {
  Map<String, dynamic>? data;
  String? error;
  bool loading = true;
  int lastTick = -1;

  @override
  void initState() {
    super.initState();
    widget.state.addListener(_onStateChanged);
    _load();
  }

  void _onStateChanged() {
    if (widget.state.changedTick != lastTick) {
      _load();
    }
  }

  Future<void> reload() => _load();

  Future<void> _load() async {
    lastTick = widget.state.changedTick;
    if (mounted) {
      setState(() {
        loading = data == null;
        error = null;
      });
    }
    try {
      final result = await widget.fetch(widget.state.api);
      if (mounted) {
        setState(() {
          data = result;
          loading = false;
        });
      }
    } on FactoryOfflineException {
      if (mounted) setState(() => loading = false);
    } catch (e) {
      if (mounted) {
        setState(() {
          error = e.toString();
          loading = false;
        });
      }
    }
  }

  @override
  void dispose() {
    widget.state.removeListener(_onStateChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.title), actions: widget.actions?.call(context)),
      body: Column(
        children: [
          OfflineBanner(state: widget.state),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _load,
              child: Align(
                alignment: Alignment.topLeft,
                child: ConstrainedBox(
                  // Zelfde `.content{max-width:860px}` als het oude Kotlin-dashboard — op een
                  // brede laptop-monitor vulde de app anders de hele breedte, wat oogde als "te breed".
                  constraints: const BoxConstraints(maxWidth: 860),
                  child: ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      if (widget.subtitle != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: Text(widget.subtitle!, style: const TextStyle(color: Colors.black54)),
                        ),
                      if (error != null) ErrorBanner(error!),
                      if (loading && data == null)
                        const Padding(
                          padding: EdgeInsets.all(32),
                          child: Center(child: CircularProgressIndicator()),
                        )
                      else if (data != null)
                        widget.builder(context, data!),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
