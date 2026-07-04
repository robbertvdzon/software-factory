import 'package:flutter/material.dart';

import 'app_state.dart';
import 'screens/my_actions_screen.dart';
import 'screens/overview_screens.dart';
import 'screens/stories_screen.dart';

class _NavEntry {
  final String label;
  final IconData icon;
  final WidgetBuilder builder;
  const _NavEntry(this.label, this.icon, this.builder);
}

/// App-shell: My actions is het startscherm (§9). Bottom-navigatie op smalle
/// schermen (telefoon, de vier meest gebruikte secties + "Meer"), een volledige
/// NavigationRail op brede schermen (web/tablet).
class AppShell extends StatefulWidget {
  final AppState state;
  final VoidCallback onLoggedOut;
  const AppShell({super.key, required this.state, required this.onLoggedOut});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  var selectedIndex = 0;

  List<_NavEntry> get _primaryEntries => [
    _NavEntry('My actions', Icons.inbox_outlined, (_) => MyActionsScreen(state: widget.state)),
    _NavEntry('Stories', Icons.list_alt_outlined, (_) => StoriesScreen(state: widget.state)),
    _NavEntry('Dashboard', Icons.dashboard_outlined, (_) => DashboardOverviewScreen(state: widget.state)),
    _NavEntry('Agents', Icons.smart_toy_outlined, (_) => AgentsScreen(state: widget.state)),
  ];

  List<_NavEntry> get _secondaryEntries => [
    _NavEntry('Merged', Icons.call_merge, (_) => MergedScreen(state: widget.state)),
    _NavEntry('Projects', Icons.folder_outlined, (_) => ProjectsScreen(state: widget.state)),
    _NavEntry('Nightly', Icons.nightlight_outlined, (_) => NightlyScreen(state: widget.state)),
    _NavEntry('Downloads', Icons.download_outlined, (_) => DownloadsScreen(state: widget.state)),
    _NavEntry('Settings', Icons.settings_outlined, (_) => SettingsScreen(state: widget.state)),
  ];

  @override
  Widget build(BuildContext context) {
    final primary = _primaryEntries;
    final all = [...primary, ..._secondaryEntries];
    return ListenableBuilder(
      listenable: widget.state,
      builder: (context, _) {
        return LayoutBuilder(
          builder: (context, constraints) {
            final wide = constraints.maxWidth >= 760;
            if (wide) {
              return Scaffold(
                body: Row(
                  children: [
                    NavigationRail(
                      selectedIndex: selectedIndex,
                      onDestinationSelected: (index) => setState(() => selectedIndex = index),
                      labelType: NavigationRailLabelType.all,
                      destinations: [
                        for (final entry in all) NavigationRailDestination(icon: Icon(entry.icon), label: Text(entry.label)),
                      ],
                      trailing: Expanded(
                        child: Align(
                          alignment: Alignment.bottomCenter,
                          child: Padding(
                            padding: const EdgeInsets.only(bottom: 16),
                            child: IconButton(
                              icon: const Icon(Icons.logout),
                              tooltip: 'Uitloggen',
                              onPressed: () async {
                                await widget.state.api.clearSession();
                                widget.onLoggedOut();
                              },
                            ),
                          ),
                        ),
                      ),
                    ),
                    const VerticalDivider(width: 1),
                    Expanded(child: Builder(builder: all[selectedIndex].builder)),
                  ],
                ),
              );
            }
            final onMore = selectedIndex >= primary.length;
            return Scaffold(
              body: onMore ? Builder(builder: all[selectedIndex].builder) : Builder(builder: primary[selectedIndex].builder),
              bottomNavigationBar: NavigationBar(
                selectedIndex: onMore ? primary.length : selectedIndex,
                onDestinationSelected: (index) {
                  if (index == primary.length) {
                    _openMoreSheet(context);
                  } else {
                    setState(() => selectedIndex = index);
                  }
                },
                destinations: [
                  for (final entry in primary) NavigationDestination(icon: Icon(entry.icon), label: entry.label),
                  const NavigationDestination(icon: Icon(Icons.more_horiz), label: 'Meer'),
                ],
              ),
            );
          },
        );
      },
    );
  }

  void _openMoreSheet(BuildContext context) {
    final primaryCount = _primaryEntries.length;
    showModalBottomSheet(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < _secondaryEntries.length; i++)
              ListTile(
                leading: Icon(_secondaryEntries[i].icon),
                title: Text(_secondaryEntries[i].label),
                onTap: () {
                  Navigator.of(sheetContext).pop();
                  setState(() => selectedIndex = primaryCount + i);
                },
              ),
            ListTile(
              leading: const Icon(Icons.logout),
              title: const Text('Uitloggen'),
              onTap: () async {
                Navigator.of(sheetContext).pop();
                await widget.state.api.clearSession();
                widget.onLoggedOut();
              },
            ),
          ],
        ),
      ),
    );
  }
}
