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

  const BranchJobStatus({
    required this.workflowName,
    required this.status,
    required this.conclusion,
    required this.htmlUrl,
  });

  factory BranchJobStatus.fromJson(Map<String, dynamic> json) => BranchJobStatus(
    workflowName: _requiredString(json, 'workflowName'),
    status: _optionalNullableString(json, 'status'),
    conclusion: _optionalNullableString(json, 'conclusion'),
    htmlUrl: _optionalNullableString(json, 'htmlUrl'),
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
