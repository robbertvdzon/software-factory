import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/features/projects/branch_timeline_models.dart';
import 'package:softwarefactory_dashboard/features/projects/project_models.dart';

void main() {
  test('BranchJobStatus parsing keeps a null status distinct from a completed one', () {
    final notTriggered = BranchJobStatus.fromJson({'workflowName': 'Build notities'});
    expect(notTriggered.workflowName, 'Build notities');
    expect(notTriggered.status, isNull);
    expect(notTriggered.conclusion, isNull);
    expect(notTriggered.htmlUrl, isNull);

    final triggered = BranchJobStatus.fromJson({
      'workflowName': 'Build backend',
      'status': 'completed',
      'conclusion': 'success',
      'htmlUrl': 'https://x/1',
    });
    expect(triggered.status, 'completed');
    expect(triggered.conclusion, 'success');
    expect(triggered.htmlUrl, 'https://x/1');
  });

  test('BranchTimelineRow parsing distinguishes main from a pull request', () {
    final main = BranchTimelineRow.fromJson({
      'kind': 'main',
      'branchName': 'main',
      'commitShortSha': 'a1b2c3d',
      'commitMessage': 'Update UI',
      'commitDate': '2026-07-24T10:34:00Z',
      'jobs': [
        {'workflowName': 'Build backend', 'status': 'completed', 'conclusion': 'success'},
      ],
      'liveComponents': [
        {'label': 'backend', 'syncStatus': 'IN_SYNC'},
      ],
    });
    expect(main.isMain, isTrue);
    expect(main.jobs, hasLength(1));
    expect(main.liveComponents, hasLength(1));
    expect(main.prNumber, isNull);

    final pr = BranchTimelineRow.fromJson({
      'kind': 'pull_request',
      'branchName': 'fix/login-bug',
      'commitShortSha': 'e4f5a6b',
      'commitMessage': 'Fix login bug',
      'commitDate': '2026-07-24T09:00:00Z',
      'prNumber': 182,
      'prUrl': 'https://github.com/robbert/sf/pull/182',
      'jobs': <Map<String, dynamic>>[],
    });
    expect(pr.isMain, isFalse);
    expect(pr.prNumber, 182);
    expect(pr.prUrl, 'https://github.com/robbert/sf/pull/182');
    expect(pr.liveComponents, isEmpty);
  });

  test('BranchTimelineRow parsing rejects a missing branchName', () {
    expect(
      () => BranchTimelineRow.fromJson({'kind': 'main', 'commitShortSha': 'a1b2c3d'}),
      throwsA(isA<ProjectContractException>()),
    );
  });

  test('BranchTimelinePageData parsing collects rows and errors', () {
    final page = BranchTimelinePageData.fromJson({
      'rows': [
        {'kind': 'main', 'branchName': 'main', 'jobs': <Map<String, dynamic>>[]},
      ],
      'errors': ['Meer dan 15 open PR\'s; alleen de eerste 15 worden getoond.'],
    });
    expect(page.rows, hasLength(1));
    expect(page.errors, hasLength(1));
  });
}
