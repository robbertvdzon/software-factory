import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Regressiebewaking op de HSTS-header in nginx.conf (SF-2008).
///
/// nginx erft `add_header` alleen van het omsluitende blok en maskeert de
/// server-variant volledig zodra de gekozen location zelf een `add_header`
/// heeft. Een nieuw location-blok met bijvoorbeeld een eigen `Cache-Control`
/// levert de HSTS-header dus stilletjes niet meer op. Deze test bewaakt precies
/// die val; het gedrag zelf is aangetoond tegen een draaiende nginx (zie
/// docs/stories/worklog/SF-2008-worklog.md).
const String hstsHeader =
    'add_header Strict-Transport-Security "max-age=31536000" always;';

void main() {
  final String config = File('nginx.conf').readAsStringSync();
  final List<String> lines = config.split('\n');

  test('server-niveau heeft de HSTS-header', () {
    final Iterable<String> serverLevel = lines
        .where((String line) => line.startsWith('  ') && !line.startsWith('   '))
        .map((String line) => line.trim());
    expect(serverLevel, contains(hstsHeader));
  });

  test('elk location-blok met een eigen add_header herhaalt de HSTS-header', () {
    final List<_LocationBlock> blocks = _parseLocationBlocks(lines);
    expect(blocks, isNotEmpty, reason: 'geen location-blokken gevonden');

    for (final _LocationBlock block in blocks) {
      final bool hasOwnHeader =
          block.body.any((String line) => line.startsWith('add_header '));
      if (!hasOwnHeader) {
        continue;
      }
      expect(
        block.body,
        contains(hstsHeader),
        reason:
            '${block.header} heeft een eigen add_header en maskeert daarmee de '
            'server-variant; herhaal de HSTS-header in dit blok',
      );
    }
  });

  test('de HSTS-header belooft niets over subdomeinen of preloading', () {
    final Iterable<String> hstsLines = lines
        .map((String line) => line.trim())
        .where((String line) =>
            !line.startsWith('#') &&
            line.contains('Strict-Transport-Security'));
    expect(hstsLines, isNotEmpty);
    for (final String line in hstsLines) {
      expect(line, equals(hstsHeader));
    }
  });
}

class _LocationBlock {
  _LocationBlock(this.header, this.body);

  final String header;
  final List<String> body;
}

/// Platte parser voor de niet-geneste location-blokken in deze config.
List<_LocationBlock> _parseLocationBlocks(List<String> lines) {
  final List<_LocationBlock> blocks = <_LocationBlock>[];
  String? header;
  List<String> body = <String>[];
  for (final String raw in lines) {
    final String line = raw.trim();
    if (header == null) {
      if (line.startsWith('location ') && line.endsWith('{')) {
        header = line.substring(0, line.length - 1).trim();
        body = <String>[];
      }
      continue;
    }
    if (line == '}') {
      blocks.add(_LocationBlock(header, body));
      header = null;
      continue;
    }
    body.add(line);
  }
  return blocks;
}
