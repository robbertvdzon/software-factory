import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api_client.dart';

/// Eén installeerbare app-versie, afgeleid uit de ruwe `/api/v1/downloads`-lijst (die alle
/// `.apk`-assets van alle geconfigureerde projecten door elkaar teruggeeft — zie
/// `GitHubReleaseClient.apkDownloads` op de backend).
///
/// Twee release-conventies komen hier samen (zie [groupAppReleases]):
/// - robberts-assistent-stijl: elke app heeft een eigen, steeds overschreven tag (`wind-latest`),
///   maar de asset heet altijd letterlijk `app-release.apk` — dus de tag is de identiteit.
/// - softwarefactory/personal-feed-stijl: elke build krijgt een eigen tag/asset met tijdstempel
///   (`dashboard-apk-<ts>-<sha>.apk`), plus een vaste "schone" naam ernaast
///   (`software-factory-dashboard.apk`) — die schone naam is de identiteit.
class AppRelease {
  final String projectKey;
  final String repository;
  final String appLabel;
  final String assetName;
  final int size;
  final String? createdAt;
  final String downloadUrl;
  final String? releaseTag;
  final String? releaseUrl;

  const AppRelease({
    required this.projectKey,
    required this.repository,
    required this.appLabel,
    required this.assetName,
    required this.size,
    required this.createdAt,
    required this.downloadUrl,
    required this.releaseTag,
    required this.releaseUrl,
  });
}

const _genericAssetName = 'app-release.apk';
final _timestampedNamePattern = RegExp(r'-\d{6,}-[0-9a-f]{6,}\.apk$', caseSensitive: false);

/// Groepeert de ruwe downloads-lijst tot maximaal één rij per app: de nieuwste release, met een
/// leesbare naam. Puur/testbaar zonder widgets — zelfde recept als `parseAgentLogEvent`.
List<AppRelease> groupAppReleases(List<dynamic> downloads) {
  final byRelease = <String, List<Map<String, dynamic>>>{};
  for (final raw in downloads) {
    final asset = Map<String, dynamic>.from(raw as Map);
    final key = '${text(asset['repository'])}::${text(asset['releaseTag'])}::${text(asset['releaseUrl'])}';
    byRelease.putIfAbsent(key, () => []).add(asset);
  }

  final byApp = <String, AppRelease>{};
  for (final assets in byRelease.values) {
    final chosen = _pickCanonicalAsset(assets);
    if (chosen == null) continue;
    final release = _toAppRelease(chosen);
    final appKey = '${release.repository}::${release.appLabel}';
    final existing = byApp[appKey];
    if (existing == null || _isNewer(release.createdAt, existing.createdAt)) {
      byApp[appKey] = release;
    }
  }

  final result = byApp.values.toList()..sort((a, b) => a.appLabel.compareTo(b.appLabel));
  return result;
}

/// Eén release publiceert soms twee `.apk`-assets naast elkaar (een tijdgestempelde en een vaste
/// "schone" naam voor hetzelfde bestand) — kies de schone voor weergave/installatie.
Map<String, dynamic>? _pickCanonicalAsset(List<Map<String, dynamic>> assets) {
  if (assets.isEmpty) return null;
  if (assets.length == 1) return assets.single;
  return assets.firstWhere(
    (a) => !_timestampedNamePattern.hasMatch(text(a['name'])),
    orElse: () => assets.first,
  );
}

AppRelease _toAppRelease(Map<String, dynamic> asset) => AppRelease(
      projectKey: text(asset['projectKey']),
      repository: text(asset['repository']),
      appLabel: _appLabelFor(asset),
      assetName: text(asset['name']),
      size: number(asset['size']),
      createdAt: asset['createdAt'] as String?,
      downloadUrl: text(asset['downloadUrl']),
      releaseTag: asset['releaseTag'] as String?,
      releaseUrl: asset['releaseUrl'] as String?,
    );

String _appLabelFor(Map<String, dynamic> asset) {
  final assetName = text(asset['name']);
  if (assetName.isNotEmpty && assetName.toLowerCase() != _genericAssetName) {
    return _humanize(assetName.replaceAll(RegExp(r'\.apk$', caseSensitive: false), ''));
  }
  final tag = text(asset['releaseTag']);
  if (tag.isNotEmpty) {
    return _humanize(tag.replaceFirst(RegExp(r'-latest$'), ''));
  }
  return _humanize(assetName.replaceAll(RegExp(r'\.apk$', caseSensitive: false), ''));
}

String _humanize(String raw) {
  final words = raw.split(RegExp(r'[-_]+')).where((w) => w.isNotEmpty);
  if (words.isEmpty) return raw;
  return words.map((w) => w[0].toUpperCase() + w.substring(1)).join(' ');
}

bool _isNewer(String? a, String? b) {
  if (a == null) return false;
  if (b == null) return true;
  final da = DateTime.tryParse(a);
  final db = DateTime.tryParse(b);
  if (da == null) return false;
  if (db == null) return true;
  return da.isAfter(db);
}

/// Gegooid als [AppUpdateInstaller.install] niet mag installeren — de UI moet dan naar
/// systeeminstellingen sturen (al gedaan via `requestInstallPermission`) en het opnieuw laten
/// proberen na terugkeer. Zelfde patroon als robberts_assistent's `UpdateChecker`.
class UpdatePermissionRequiredException implements Exception {}

/// Native install-brug voor Android (zie `UpdateInstaller.kt` + `MainActivity.kt`). Downloadt en
/// installeert een APK via de systeem-package-installer, en onthoudt lokaal (SharedPreferences)
/// welke release voor het laatst via dít scherm geïnstalleerd is — er is geen betrouwbare,
/// uniforme manier om voor willekeurige andere apps de écht geïnstalleerde versie op te vragen
/// (dat vereist per-package `<queries>`-declaraties en een centraal package-naam-register, zie
/// docs/adr voor de afweging), dus dit is een bewust simpelere, maar overal werkende aanpak.
class AppUpdateInstaller {
  static const _channel = MethodChannel('nl.vdzon.softwarefactory.softwarefactory_dashboard/updater');

  Future<void> install(AppRelease release) async {
    final canInstall = await _channel.invokeMethod<bool>('canInstallPackages') ?? false;
    if (!canInstall) {
      await _channel.invokeMethod('requestInstallPermission');
      throw UpdatePermissionRequiredException();
    }
    await _channel.invokeMethod('downloadAndInstall', {
      'url': release.downloadUrl,
      'fileName': release.assetName,
    });
    await _markInstalled(release);
  }

  Future<void> _markInstalled(AppRelease release) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefKey(release), release.createdAt ?? DateTime.now().toIso8601String());
  }

  /// Tijdstip van de laatst via dit scherm geïnstalleerde release van deze app, of `null` als hier
  /// nog nooit via geïnstalleerd is (zegt niets over of de app al op een andere manier up-to-date is).
  Future<String?> lastInstalledAt(AppRelease release) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_prefKey(release));
  }

  String _prefKey(AppRelease release) => 'app_update_installed::${release.repository}::${release.appLabel}';
}
