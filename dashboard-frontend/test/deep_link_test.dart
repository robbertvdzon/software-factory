import 'package:flutter_test/flutter_test.dart';

import 'package:softwarefactory_dashboard/deep_link.dart';

void main() {
  group('parseDeepLink', () {
    test('changelog-pad levert de changelog-bestemming van dat project', () {
      expect(parseDeepLink('/changelog/software-factory'), const ChangelogDestination('software-factory'));
    });

    test('projectnaam met spaties en speciale tekens wordt gedecodeerd', () {
      expect(parseDeepLink('/changelog/Mijn%20Project%20%26%20Co'), const ChangelogDestination('Mijn Project & Co'));
      expect(parseDeepLink('/changelog/a%2Fb'), const ChangelogDestination('a/b'));
    });

    test('query en fragment horen niet bij de projectnaam', () {
      expect(parseDeepLink('/changelog/demo?x=1'), const ChangelogDestination('demo'));
      expect(parseDeepLink('/changelog/demo#top'), const ChangelogDestination('demo'));
    });

    test('een willekeurig ander pad levert geen changelog-bestemming', () {
      expect(parseDeepLink('/'), isNull);
      expect(parseDeepLink(''), isNull);
      expect(parseDeepLink('/projects'), isNull);
      expect(parseDeepLink('/changelogs/demo'), isNull);
      expect(parseDeepLink('/x/changelog/demo'), isNull);
    });

    test('leeg of ontbrekend projectdeel geeft een lege projectnaam i.p.v. een crash', () {
      expect(parseDeepLink('/changelog'), const ChangelogDestination(''));
      expect(parseDeepLink('/changelog/'), const ChangelogDestination(''));
    });

    test('een ongeldige escape-reeks crasht niet maar blijft ruw staan', () {
      expect(parseDeepLink('/changelog/%zz'), const ChangelogDestination('%zz'));
    });
  });

  group('changelogPathFor', () {
    test('encodeert de projectnaam', () {
      expect(changelogPathFor('software-factory'), '/changelog/software-factory');
      expect(changelogPathFor('Mijn Project & Co'), '/changelog/Mijn%20Project%20%26%20Co');
    });

    test('is heen en terug te gebruiken', () {
      for (final name in ['software-factory', 'Mijn Project & Co', 'a/b', 'ä ö?#']) {
        expect(parseDeepLink(changelogPathFor(name)), ChangelogDestination(name), reason: name);
      }
    });
  });
}
