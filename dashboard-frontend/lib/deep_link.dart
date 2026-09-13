/// Deep-link-afleiding voor de web-app: zet het gevraagde browserpad om naar een
/// [AppDestination] (changelog van project X, een app-shell-sectie, een story-detail) of
/// "geen deep link". Bewust UI-vrij en zonder Flutter-imports, zodat dit met gewone
/// unit-tests te dekken is. De omgekeerde richting (scherm → adresbalk) zit in browser_path.dart.
library;

/// Het pad-segment van de zelfstandige changelog-pagina: `/changelog/<projectnaam>`.
const changelogPathSegment = 'changelog';

/// Het pad-segment van het Stories-overzicht; `/stories/<storykey>` opent het detailscherm.
const storiesPathSegment = 'stories';

/// Een bestemming die uit het gevraagde browserpad is afgeleid.
sealed class AppDestination {
  const AppDestination();
}

/// Een sectie van de app-shell (`/stories`, `/settings`, ...), aangeduid met de slug uit het pad.
class ShellDestination extends AppDestination {
  final String section;
  const ShellDestination(this.section);

  @override
  bool operator ==(Object other) => other is ShellDestination && other.section == section;

  @override
  int get hashCode => section.hashCode;

  @override
  String toString() => 'ShellDestination($section)';
}

/// Het detailscherm van één story (`/stories/<storykey>`), bovenop het Stories-overzicht.
class StoryDestination extends AppDestination {
  final String storyKey;
  const StoryDestination(this.storyKey);

  @override
  bool operator ==(Object other) => other is StoryDestination && other.storyKey == storyKey;

  @override
  int get hashCode => storyKey.hashCode;

  @override
  String toString() => 'StoryDestination($storyKey)';
}

/// Bestemming die uit het gevraagde pad is afgeleid. [projectName] is al gedecodeerd
/// (dus met spaties/speciale tekens zoals de gebruiker ze kent) en kan leeg zijn wanneer
/// het projectdeel ontbreekt — het changelog-scherm toont dan zijn eigen foutmelding/lege staat.
class ChangelogDestination extends AppDestination {
  final String projectName;
  const ChangelogDestination(this.projectName);

  @override
  bool operator ==(Object other) => other is ChangelogDestination && other.projectName == projectName;

  @override
  int get hashCode => projectName.hashCode;

  @override
  String toString() => 'ChangelogDestination($projectName)';
}

/// Het bookmarkbare pad van de changelog van [projectName]; de projectnaam wordt
/// URL-geëncodeerd zodat namen met spaties of andere bijzondere tekens werken.
String changelogPathFor(String projectName) => '/$changelogPathSegment/${Uri.encodeComponent(projectName)}';

/// Het bookmarkbare pad van het detailscherm van [storyKey].
String storyPathFor(String storyKey) => '/$storiesPathSegment/${Uri.encodeComponent(storyKey)}';

/// Het bookmarkbare pad van een app-shell-sectie (bijv. `/settings`).
String sectionPathFor(String section) => '/$section';

/// Leidt uit [path] de app-bestemming af:
///  * `/changelog/<project>` → [ChangelogDestination] (zelfstandige pagina);
///  * `/stories/<key>` → [StoryDestination];
///  * `/<sectie>` → [ShellDestination] (de app-shell bepaalt zelf of de slug bestaat);
///  * `/` of leeg → `null` (standaard startscherm).
AppDestination? parseAppPath(String path) {
  final changelog = parseDeepLink(path);
  if (changelog != null) return changelog;
  final segments = _segments(path);
  if (segments.isEmpty) return null;
  final first = _decode(segments.first);
  if (first == storiesPathSegment && segments.length > 1) {
    return StoryDestination(segments.skip(1).map(_decode).join('/'));
  }
  return ShellDestination(first);
}

/// Leidt uit [path] de changelog-bestemming af, of `null` voor elk ander pad
/// (de app toont dan het bestaande gedrag: de app-shell).
///
/// Percent-encoding wordt gedecodeerd; een ongeldige escape-reeks laat het segment
/// ruw staan in plaats van te crashen.
ChangelogDestination? parseDeepLink(String path) {
  final segments = _segments(path);
  if (segments.isEmpty || _decode(segments.first) != changelogPathSegment) return null;

  // Een projectnaam met een '/' erin komt als meerdere segmenten binnen; die voegen we
  // weer samen zodat de naam ongeschonden bij het changelog-scherm aankomt.
  final projectName = segments.skip(1).map(_decode).join('/');
  return ChangelogDestination(projectName);
}

/// De padsegmenten zonder query/fragment en zonder lege segmenten.
List<String> _segments(String path) {
  var pathOnly = path;
  final marker = pathOnly.indexOf(RegExp(r'[?#]'));
  if (marker >= 0) pathOnly = pathOnly.substring(0, marker);
  return pathOnly.split('/').where((segment) => segment.isNotEmpty).toList();
}

String _decode(String segment) {
  try {
    return Uri.decodeComponent(segment);
  } on ArgumentError {
    return segment;
  } on FormatException {
    return segment;
  }
}
