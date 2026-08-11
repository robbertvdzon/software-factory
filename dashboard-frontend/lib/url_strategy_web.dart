import 'package:flutter_web_plugins/url_strategy.dart';

/// Pad-gebaseerde URL's op web (uit de Flutter-SDK, geen nieuwe dependency), zodat
/// `/changelog/<projectnaam>` zonder `#` in de adresbalk staat en gekopieerd/gebookmarkt
/// kan worden. De nginx-config serveert index.html al op willekeurige paden.
void useBookmarkableUrls() => usePathUrlStrategy();
