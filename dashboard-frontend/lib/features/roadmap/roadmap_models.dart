import '../../api_client.dart';

class RoadmapEpic {
  final int id;
  final String title;
  final String description;
  final String status;
  final int customerRank;
  final int processRank;
  final int roadmapRank;
  final Set<int> dependencyIds;
  final Set<int> blockedByIds;
  final Set<int> blocksIds;
  final String rankExplanation;

  const RoadmapEpic({
    required this.id,
    required this.title,
    required this.description,
    required this.status,
    required this.customerRank,
    required this.processRank,
    required this.roadmapRank,
    required this.dependencyIds,
    required this.blockedByIds,
    required this.blocksIds,
    required this.rankExplanation,
  });

  factory RoadmapEpic.fromJson(Map<String, dynamic> json) => RoadmapEpic(
    id: number(json['id']),
    title: text(json['title']),
    description: text(json['description']),
    status: text(json['status'], fallback: 'planned'),
    customerRank: number(json['customerRank']),
    processRank: number(json['processRank']),
    roadmapRank: number(json['roadmapRank']),
    dependencyIds: _ids(json['dependencyIds']),
    blockedByIds: _ids(json['blockedByIds']),
    blocksIds: _ids(json['blocksIds']),
    rankExplanation: text(json['rankExplanation']),
  );

  static Set<int> _ids(dynamic values) =>
      (values as List? ?? []).map(number).where((value) => value > 0).toSet();
}

class RoadmapNodePosition {
  final double x;
  final double y;
  final int level;
  const RoadmapNodePosition(this.x, this.y, this.level);
}

class RoadmapGraphLayout {
  static const cardWidth = 224.0;
  static const cardHeight = 164.0;
  static const horizontalGap = 66.0;
  static const verticalGap = 22.0;

  final Map<int, RoadmapNodePosition> positions;
  final double width;
  final double height;

  const RoadmapGraphLayout({
    required this.positions,
    required this.width,
    required this.height,
  });

  factory RoadmapGraphLayout.forEpics(List<RoadmapEpic> epics) {
    final levels = <int, int>{};
    final byId = {for (final epic in epics) epic.id: epic};

    int levelOf(RoadmapEpic epic, Set<int> visiting) {
      final known = levels[epic.id];
      if (known != null) return known;
      if (!visiting.add(epic.id)) return 0;
      var level = 0;
      for (final dependencyId in epic.dependencyIds) {
        final dependency = byId[dependencyId];
        if (dependency != null) {
          final candidate = levelOf(dependency, visiting) + 1;
          if (candidate > level) level = candidate;
        }
      }
      visiting.remove(epic.id);
      levels[epic.id] = level;
      return level;
    }

    for (final epic in epics) {
      levelOf(epic, <int>{});
    }
    final perLevel = <int, List<RoadmapEpic>>{};
    for (final epic in epics) {
      perLevel.putIfAbsent(levels[epic.id]!, () => []).add(epic);
    }
    for (final entries in perLevel.values) {
      entries.sort((a, b) => a.roadmapRank.compareTo(b.roadmapRank));
    }

    final positions = <int, RoadmapNodePosition>{};
    var maxRows = 1;
    for (final entry in perLevel.entries) {
      if (entry.value.length > maxRows) maxRows = entry.value.length;
      for (var row = 0; row < entry.value.length; row++) {
        positions[entry.value[row].id] = RoadmapNodePosition(
          entry.key * (cardWidth + horizontalGap),
          row * (cardHeight + verticalGap),
          entry.key,
        );
      }
    }
    final columns = perLevel.isEmpty
        ? 1
        : perLevel.keys.reduce((a, b) => a > b ? a : b) + 1;
    return RoadmapGraphLayout(
      positions: positions,
      width: columns * cardWidth + (columns - 1) * horizontalGap,
      height: maxRows * cardHeight + (maxRows - 1) * verticalGap,
    );
  }
}
