/// Deep-link-afleiding voor de web-app (SF-2087): zet het gevraagde browserpad om naar
/// "changelog van project X" of "geen deep link". Bewust UI-vrij en zonder Flutter-imports,
/// zodat dit met gewone unit-tests te dekken is.
library;

/// Het enige pad-segment waarop de app een deep link herkent: `/changelog/<projectnaam>`.
const changelogPathSegment = 'changelog';

/// Bestemming die uit het gevraagde pad is afgeleid. [projectName] is al gedecodeerd
/// (dus met spaties/speciale tekens zoals de gebruiker ze kent) en kan leeg zijn wanneer
/// het projectdeel ontbreekt — het changelog-scherm toont dan zijn eigen foutmelding/lege staat.
class ChangelogDestination {
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

/// Leidt uit [path] de changelog-bestemming af, of `null` voor elk ander pad
/// (de app toont dan het bestaande gedrag: de app-shell).
///
/// Percent-encoding wordt gedecodeerd; een ongeldige escape-reeks laat het segment
/// ruw staan in plaats van te crashen.
ChangelogDestination? parseDeepLink(String path) {
  var pathOnly = path;
  final marker = pathOnly.indexOf(RegExp(r'[?#]'));
  if (marker >= 0) pathOnly = pathOnly.substring(0, marker);

  final segments = pathOnly.split('/').where((segment) => segment.isNotEmpty).toList();
  if (segments.isEmpty || _decode(segments.first) != changelogPathSegment) return null;

  // Een projectnaam met een '/' erin komt als meerdere segmenten binnen; die voegen we
  // weer samen zodat de naam ongeschonden bij het changelog-scherm aankomt.
  final projectName = segments.skip(1).map(_decode).join('/');
  return ChangelogDestination(projectName);
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
