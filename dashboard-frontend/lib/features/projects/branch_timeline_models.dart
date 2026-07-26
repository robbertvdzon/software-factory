import 'project_models.dart';

/// Eén dot in de build-kolom van een [BranchTimelineRow]: status van één workflow voor de exacte
/// commit van die rij. [status] is `null` wanneer er geen enkele run van deze workflow bestaat voor
/// deze commit — het "niet getriggerd"-geval (bv. een paths-filter die niet matchte), dat de UI
/// zichtbaar anders moet tonen dan "nog niet gestart".
class BranchJobStatus {
  final String workflowName;
  final String? status;
  final String? conclusion;
  final String? htmlUrl;
  final String? startedAt;
  final String? finishedAt;

  const BranchJobStatus({
    required this.workflowName,
    required this.status,
    required this.conclusion,
    required this.htmlUrl,
    this.startedAt,
    this.finishedAt,
  });

  factory BranchJobStatus.fromJson(Map<String, dynamic> json) => BranchJobStatus(
    workflowName: _requiredString(json, 'workflowName'),
    status: _optionalNullableString(json, 'status'),
    conclusion: _optionalNullableString(json, 'conclusion'),
    htmlUrl: _optionalNullableString(json, 'htmlUrl'),
    startedAt: _optionalNullableString(json, 'startedAt'),
    finishedAt: _optionalNullableString(json, 'finishedAt'),
  );
}

/// Eén rij in de branch-timeline: de default branch, of één open PR.
class BranchTimelineRow {
  final String kind;
  final String branchName;
  final String commitShortSha;
  final String commitMessage;
  final String? commitDate;
  final int? prNumber;
  final String? prUrl;
  final List<BranchJobStatus> jobs;
  /// Alleen gevuld voor `kind == "main"`; PR's deployen niet. Blijft een ruwe map (zoals
  /// [ProjectSummary.liveComponents]) — de widget leest er direct `syncStatus`/`consoleUrl` uit.
  final List<Map<String, dynamic>> liveComponents;

  const BranchTimelineRow({
    required this.kind,
    required this.branchName,
    required this.commitShortSha,
    required this.commitMessage,
    required this.commitDate,
    required this.prNumber,
    required this.prUrl,
    required this.jobs,
    required this.liveComponents,
  });

  bool get isMain => kind == 'main';

  factory BranchTimelineRow.fromJson(Map<String, dynamic> json) => BranchTimelineRow(
    kind: _requiredString(json, 'kind'),
    branchName: _requiredString(json, 'branchName'),
    commitShortSha: _optionalString(json, 'commitShortSha'),
    commitMessage: _optionalString(json, 'commitMessage'),
    commitDate: _optionalNullableString(json, 'commitDate'),
    prNumber: _optionalNullableInt(json, 'prNumber'),
    prUrl: _optionalNullableString(json, 'prUrl'),
    jobs: _mapList(json['jobs']).map(BranchJobStatus.fromJson).toList(),
    liveComponents: _mapList(json['liveComponents']),
  );
}

class BranchTimelinePageData {
  final List<BranchTimelineRow> rows;
  final List<String> errors;

  const BranchTimelinePageData(this.rows, this.errors);

  factory BranchTimelinePageData.fromJson(Map<String, dynamic> json) => BranchTimelinePageData(
    _mapList(json['rows']).map(BranchTimelineRow.fromJson).toList(),
    (json['errors'] as List? ?? const []).map((e) => e.toString()).toList(),
  );
}

/// Eén commit in de commit-historie van de Builds-tab (project → branch → laatste N commits).
/// [deployed] is de ruwe `BuildSyncStatus`-string ("IN_SYNC"/"OUT_OF_SYNC"/"UNAVAILABLE"), zelfde
/// contract als [DeployStatusTile]'s `component['syncStatus']` — [SyncStatusBadge] leest 'm direct.
class BuildHistoryCommitRow {
  final String sha;
  final String shortSha;
  final String message;
  final String? date;
  final List<BranchJobStatus> jobs;
  final String deployed;

  const BuildHistoryCommitRow({
    required this.sha,
    required this.shortSha,
    required this.message,
    required this.date,
    required this.jobs,
    required this.deployed,
  });

  factory BuildHistoryCommitRow.fromJson(Map<String, dynamic> json) => BuildHistoryCommitRow(
    sha: _requiredString(json, 'sha'),
    shortSha: _optionalString(json, 'shortSha'),
    message: _optionalString(json, 'message'),
    date: _optionalNullableString(json, 'date'),
    jobs: _mapList(json['jobs']).map(BranchJobStatus.fromJson).toList(),
    deployed: _optionalString(json, 'deployed'),
  );
}

class BuildHistoryPageData {
  final String branch;
  final List<BuildHistoryCommitRow> commits;
  /// True als deze pagina volledig gevuld was — de UI toont dan de "Meer"-knop.
  final bool hasMore;
  final List<String> errors;

  const BuildHistoryPageData({
    required this.branch,
    required this.commits,
    required this.hasMore,
    required this.errors,
  });

  factory BuildHistoryPageData.fromJson(Map<String, dynamic> json) => BuildHistoryPageData(
    branch: _optionalString(json, 'branch'),
    commits: _mapList(json['commits']).map(BuildHistoryCommitRow.fromJson).toList(),
    hasMore: json['hasMore'] == true,
    errors: (json['errors'] as List? ?? const []).map((e) => e.toString()).toList(),
  );
}

/// Eén project uit de elke-minuut-ververste "laatste commits"-snapshot (zie
/// `RecentCommitsPoller` op de backend). De Builds-tab gebruikt alleen [project]/[branch] om
/// zichzelf bij het openen alvast op de juiste keuze te zetten — de commit-details zelf blijven
/// een ruwe map (ongebruikt aan deze kant, zelfde recept als `BranchTimelineRow.liveComponents`).
class ProjectRecentCommits {
  final String project;
  final String branch;
  final List<Map<String, dynamic>> commits;

  const ProjectRecentCommits({required this.project, required this.branch, required this.commits});

  factory ProjectRecentCommits.fromJson(Map<String, dynamic> json) => ProjectRecentCommits(
    project: _optionalString(json, 'project'),
    branch: _optionalString(json, 'branch'),
    commits: _mapList(json['commits']),
  );
}

class RecentCommitsPageData {
  final List<ProjectRecentCommits> projects;
  final String? mostRecentProject;
  final String? mostRecentBranch;
  final List<String> errors;

  const RecentCommitsPageData({
    required this.projects,
    required this.mostRecentProject,
    required this.mostRecentBranch,
    required this.errors,
  });

  factory RecentCommitsPageData.fromJson(Map<String, dynamic> json) => RecentCommitsPageData(
    projects: _mapList(json['projects']).map(ProjectRecentCommits.fromJson).toList(),
    mostRecentProject: _optionalNullableString(json, 'mostRecentProject'),
    mostRecentBranch: _optionalNullableString(json, 'mostRecentBranch'),
    errors: (json['errors'] as List? ?? const []).map((e) => e.toString()).toList(),
  );
}

String _requiredString(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! String || value.isEmpty) {
    throw ProjectContractException('$key must be a non-empty string');
  }
  return value;
}

String _optionalString(Map<String, dynamic> json, String key) =>
    json[key] is String ? json[key] as String : '';
String? _optionalNullableString(Map<String, dynamic> json, String key) =>
    json[key] is String ? json[key] as String : null;
int? _optionalNullableInt(Map<String, dynamic> json, String key) =>
    json[key] is num ? (json[key] as num).toInt() : null;
List<Map<String, dynamic>> _mapList(dynamic value) =>
    (value as List? ?? const [])
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList();
