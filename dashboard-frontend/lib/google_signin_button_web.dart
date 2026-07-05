import 'package:flutter/widgets.dart';
import 'package:google_sign_in_web/web_only.dart' as web_only;

/// De officiële Google Identity Services-knop. `GoogleSignIn.signIn()` is op web deprecated/
/// werkt niet meer betrouwbaar (hangt of gooit een null-check binnen de plugin) — GIS vereist
/// dat de eigen knop gerenderd wordt; een geslaagde login komt binnen via `onCurrentUserChanged`.
Widget renderGoogleButton() => web_only.renderButton();
