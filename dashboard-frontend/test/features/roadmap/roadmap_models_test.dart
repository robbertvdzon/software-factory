import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/features/roadmap/roadmap_models.dart';

void main() {
  RoadmapEpic epic(int id, int rank, {Set<int> dependencies = const {}}) =>
      RoadmapEpic(
        id: id,
        title: 'Epic $id',
        description: '',
        status: 'planned',
        customerRank: rank,
        processRank: rank,
        roadmapRank: rank,
        dependencyIds: dependencies,
        blockedByIds: dependencies,
        blocksIds: const {},
        rankExplanation: '',
      );

  test(
    'dependency levels plaatsen voorgangers links van afhankelijke epics',
    () {
      final layout = RoadmapGraphLayout.forEpics([
        epic(1, 1),
        epic(2, 2, dependencies: {1}),
        epic(3, 3, dependencies: {2}),
      ]);

      expect(layout.positions[1]!.level, 0);
      expect(layout.positions[2]!.level, 1);
      expect(layout.positions[3]!.level, 2);
      expect(layout.positions[1]!.x, lessThan(layout.positions[2]!.x));
      expect(layout.width, greaterThan(RoadmapGraphLayout.cardWidth * 3));
    },
  );

  test('onafhankelijke epics blijven op rangvolgorde onder elkaar', () {
    final layout = RoadmapGraphLayout.forEpics([epic(2, 2), epic(1, 1)]);

    expect(layout.positions[1]!.y, 0);
    expect(layout.positions[2]!.y, greaterThan(0));
  });
}
