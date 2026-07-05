import 'package:flutter/widgets.dart';

/// Niet-web platforms (Android) tonen geen GIS-knop; daar gebruiken we de imperatieve `signIn()`-flow.
Widget renderGoogleButton() => const SizedBox.shrink();
