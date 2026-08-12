import 'package:flutter/material.dart';

import 'app_state.dart';
import 'screens/app_updates_screen.dart';
import 'screens/builds_screen.dart';
import 'screens/my_actions_screen.dart';
import 'screens/overview_screens.dart';
import 'screens/stories_screen.dart';
import 'screens/roadmap_screen.dart';
import 'text_scale_preference.dart';

class _NavEntry {
  final String label;
  final IconData icon;
  final WidgetBuilder builder;
  const _NavEntry(this.label, this.icon, this.builder);
}

/// App-shell: Stories is het startscherm. Bottom-navigatie op smalle
/// schermen (telefoon, de drie meest gebruikte secties + "Meer"), een volledige
/// NavigationRail op brede schermen (web/tablet).
class AppShell extends StatefulWidget {
  final AppState state;
  final TextScalePreference textScale;
  const AppShell({super.key, required this.state, required this.textScale});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  var selectedIndex = 0;

  // Stories is het eerste item en dus ook het scherm dat bij het openen van de app meteen
  // laadt (selectedIndex start op 0) — op gebruikersverzoek i.p.v. de oorspronkelijke
  // "My actions als startscherm"-keuze uit §9 van het ontwerp.
  List<_NavEntry> get _primaryEntries => [
    _NavEntry(
      'Stories',
      Icons.list_alt_outlined,
      (_) => StoriesScreen(state: widget.state),
    ),
    _NavEntry(
      'My actions',
      Icons.inbox_outlined,
      (_) => MyActionsScreen(state: widget.state),
    ),
    _NavEntry(
      'Agents',
      Icons.smart_toy_outlined,
      (_) => AgentsScreen(state: widget.state),
    ),
  ];

  List<_NavEntry> get _secondaryEntries => [
    _NavEntry(
      'Roadmap',
      Icons.account_tree_outlined,
      (_) => RoadmapScreen(state: widget.state),
    ),
    _NavEntry(
      'Projects',
      Icons.folder_outlined,
      (_) => ProjectsScreen(state: widget.state),
    ),
    _NavEntry(
      'Builds',
      Icons.construction_outlined,
      (_) => BuildsScreen(state: widget.state),
    ),
    _NavEntry(
      'App-updates',
      Icons.system_update_outlined,
      (_) => AppUpdatesScreen(state: widget.state),
    ),
    _NavEntry(
      'Audits',
      Icons.fact_check_outlined,
      (_) => AuditScreen(state: widget.state),
    ),
    _NavEntry(
      'Opruimen',
      Icons.cleaning_services_outlined,
      (_) => MaintenanceScreen(state: widget.state),
    ),
    _NavEntry(
      'Settings',
      Icons.settings_outlined,
      (_) => SettingsScreen(state: widget.state, textScale: widget.textScale),
    ),
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
                      scrollable: true,
                      selectedIndex: selectedIndex,
                      onDestinationSelected: (index) =>
                          setState(() => selectedIndex = index),
                      labelType: NavigationRailLabelType.all,
                      destinations: [
                        for (final entry in all)
                          NavigationRailDestination(
                            icon: _navIcon(entry),
                            label: Text(entry.label),
                          ),
                      ],
                    ),
                    const VerticalDivider(width: 1),
                    Expanded(
                      child: Builder(builder: all[selectedIndex].builder),
                    ),
                  ],
                ),
              );
            }
            final onMore = selectedIndex >= primary.length;
            return Scaffold(
              body: onMore
                  ? Builder(builder: all[selectedIndex].builder)
                  : Builder(builder: primary[selectedIndex].builder),
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
                  for (final entry in primary)
                    NavigationDestination(
                      icon: _navIcon(entry),
                      label: entry.label,
                    ),
                  const NavigationDestination(
                    icon: Icon(Icons.more_horiz),
                    label: 'Meer',
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  /// Tel-bolletje op de nav-items die op een mens wachten: "My actions" (stories/subtaken) en
  /// "Audits" (openstaande auditvragen — die staan niet tussen de story-acties, want een audit
  /// heeft geen story). Op label i.p.v. index gecheckt zodat dit onafhankelijk blijft van de
  /// volgorde van _primaryEntries.
  Widget _navIcon(_NavEntry entry) {
    final icon = Icon(entry.icon);
    final count = switch (entry.label) {
      'My actions' => widget.state.myActionsCount,
      'Audits' => widget.state.auditQuestionCount,
      _ => 0,
    };
    if (count <= 0) return icon;
    return Badge(label: Text('$count'), child: icon);
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
          ],
        ),
      ),
    );
  }
}
