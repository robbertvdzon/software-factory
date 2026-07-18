import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/app_updates.dart';

Map<String, dynamic> _download({
  required String repository,
  required String projectKey,
  required String name,
  String? releaseTag,
  String? createdAt,
  String? releaseUrl,
  int size = 1000,
  String downloadUrl = 'https://example.com/x.apk',
}) => {
      'repository': repository,
      'projectKey': projectKey,
      'name': name,
      'size': size,
      'createdAt': createdAt,
      'downloadUrl': downloadUrl,
      'releaseTag': releaseTag,
      'releaseUrl': releaseUrl,
    };

void main() {
  group('groupAppReleases - robberts-assistent-stijl (vaste asset-naam, tag is identiteit)', () {
    test('drie apps met dezelfde asset-naam krijgen elk hun eigen, leesbare naam uit de tag', () {
      final releases = groupAppReleases([
        _download(
          repository: 'robbertvdzon/robberts-assistent',
          projectKey: 'robberts-assistent',
          name: 'app-release.apk',
          releaseTag: 'wind-latest',
          createdAt: '2026-07-11T23:18:00Z',
        ),
        _download(
          repository: 'robbertvdzon/robberts-assistent',
          projectKey: 'robberts-assistent',
          name: 'app-release.apk',
          releaseTag: 'robberts-assistent-latest',
          createdAt: '2026-07-12T09:15:00Z',
        ),
        _download(
          repository: 'robbertvdzon/robberts-assistent',
          projectKey: 'robberts-assistent',
          name: 'app-release.apk',
          releaseTag: 'notities-latest',
          createdAt: '2026-07-11T23:20:00Z',
        ),
      ]);

      expect(releases.map((r) => r.appLabel).toList(), ['Notities', 'Robberts Assistent', 'Wind']);
      expect(releases.every((r) => r.assetName == 'app-release.apk'), isTrue);
    });
  });

  group('groupAppReleases - timestamped-tag-stijl (vaste "schone" asset-naam is identiteit)', () {
    test('kiest de schone asset-naam i.p.v. de tijdgestempelde variant binnen dezelfde release', () {
      final releases = groupAppReleases([
        _download(
          repository: 'robbertvdzon/software-factory',
          projectKey: 'softwarefactory',
          // Realistisch formaat (zie dashboard-frontend-image.yml): YYYYMMDD-HHMMSS + korte sha.
          name: 'software-factory-dashboard-20260715-103000-abc1234.apk',
          releaseTag: 'dashboard-apk-20260715-103000-abc1234',
          releaseUrl: 'https://github.com/x/releases/tag/dashboard-apk-20260715-103000-abc1234',
          createdAt: '2026-07-15T10:30:00Z',
        ),
        _download(
          repository: 'robbertvdzon/software-factory',
          projectKey: 'softwarefactory',
          name: 'software-factory-dashboard.apk',
          releaseTag: 'dashboard-apk-20260715-103000-abc1234',
          releaseUrl: 'https://github.com/x/releases/tag/dashboard-apk-20260715-103000-abc1234',
          createdAt: '2026-07-15T10:30:00Z',
        ),
      ]);

      expect(releases, hasLength(1));
      expect(releases.single.appLabel, 'Software Factory Dashboard');
      expect(releases.single.assetName, 'software-factory-dashboard.apk');
    });

    test('meerdere releases van dezelfde app over tijd houden alleen de nieuwste over', () {
      final releases = groupAppReleases([
        _download(
          repository: 'robbertvdzon/personal-news-feed-by-claude-code',
          projectKey: 'personal-feed',
          name: 'personal-news-feed.apk',
          releaseTag: 'apk-20260701-0000000',
          releaseUrl: 'https://github.com/x/releases/tag/apk-20260701-0000000',
          createdAt: '2026-07-01T09:00:00Z',
        ),
        _download(
          repository: 'robbertvdzon/personal-news-feed-by-claude-code',
          projectKey: 'personal-feed',
          name: 'personal-news-feed.apk',
          releaseTag: 'apk-20260715-1111111',
          releaseUrl: 'https://github.com/x/releases/tag/apk-20260715-1111111',
          createdAt: '2026-07-15T09:00:00Z',
          downloadUrl: 'https://example.com/newest.apk',
        ),
      ]);

      expect(releases, hasLength(1));
      expect(releases.single.downloadUrl, 'https://example.com/newest.apk');
      expect(releases.single.createdAt, '2026-07-15T09:00:00Z');
    });

    test('twee verschillende apps in hetzelfde repo (reader vs. hoofd-app) blijven los', () {
      final releases = groupAppReleases([
        _download(
          repository: 'robbertvdzon/personal-news-feed-by-claude-code',
          projectKey: 'personal-feed',
          name: 'personal-news-feed.apk',
          releaseTag: 'apk-20260715-1111111',
          releaseUrl: 'https://github.com/x/releases/tag/apk-20260715-1111111',
          createdAt: '2026-07-15T09:00:00Z',
        ),
        _download(
          repository: 'robbertvdzon/personal-news-feed-by-claude-code',
          projectKey: 'personal-feed',
          name: 'news-reader.apk',
          releaseTag: 'reader-apk-20260715-2222222',
          releaseUrl: 'https://github.com/x/releases/tag/reader-apk-20260715-2222222',
          createdAt: '2026-07-15T09:05:00Z',
        ),
      ]);

      expect(releases.map((r) => r.appLabel).toList()..sort(), ['News Reader', 'Personal News Feed']);
    });
  });

  test('lege downloadslijst geeft lege lijst', () {
    expect(groupAppReleases(const []), isEmpty);
  });
}
