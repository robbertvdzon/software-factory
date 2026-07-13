import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/features/projects/project_models.dart';

void main() {
  test('project parsing keeps boolean typed and ignores unknown fields', () {
    final project = ProjectSummary.fromJson({
      'name': 'softwarefactory',
      'hasDeployConfig': true,
      'unknownFutureField': 'ignored',
    });
    expect(project.name, 'softwarefactory');
    expect(project.hasDeployConfig, isTrue);
    expect(project.storiesTodo, 0);
  });

  test('project parsing rejects string boolean', () {
    expect(
      () => ProjectSummary.fromJson({
        'name': 'softwarefactory',
        'hasDeployConfig': 'true',
      }),
      throwsA(isA<ProjectContractException>()),
    );
  });
}
