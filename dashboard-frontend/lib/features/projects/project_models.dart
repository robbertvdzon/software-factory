class ProjectContractException implements Exception {
  final String message;
  const ProjectContractException(this.message);
  @override
  String toString() => 'ProjectContractException: $message';
}

class ProjectSummary {
  final String name;
  final String repoUrl;
  final bool hasDeployConfig;
  final int storiesTodo;
  final int storiesInProgress;
  final int storiesDone;
  final int activeAgentCount;
  final double totalCostUsd;
  final Map<String, dynamic>? prdVersion;
  final Map<String, dynamic>? buildStatus;
  final List<Map<String, dynamic>> liveComponents;

  const ProjectSummary({
    required this.name,
    required this.repoUrl,
    required this.hasDeployConfig,
    required this.storiesTodo,
    required this.storiesInProgress,
    required this.storiesDone,
    required this.activeAgentCount,
    required this.totalCostUsd,
    required this.prdVersion,
    required this.buildStatus,
    required this.liveComponents,
  });

  factory ProjectSummary.fromJson(Map<String, dynamic> json) => ProjectSummary(
    name: _requiredString(json, 'name'),
    repoUrl: _optionalString(json, 'repoUrl'),
    hasDeployConfig: _requiredBool(json, 'hasDeployConfig'),
    storiesTodo: _optionalInt(json, 'storiesTodo'),
    storiesInProgress: _optionalInt(json, 'storiesInProgress'),
    storiesDone: _optionalInt(json, 'storiesDone'),
    activeAgentCount: _optionalInt(json, 'activeAgentCount'),
    totalCostUsd: (json['totalCostUsd'] as num?)?.toDouble() ?? 0,
    prdVersion: _optionalMap(json, 'prdVersion'),
    buildStatus: _optionalMap(json, 'buildStatus'),
    liveComponents: _mapList(json['liveComponents']),
  );
}

class ProjectsPageData {
  final List<ProjectSummary> projects;
  final Map<String, Map<String, dynamic>> buildsByProject;
  final Map<String, List<Map<String, dynamic>>> downloadsByProject;

  const ProjectsPageData(
    this.projects,
    this.buildsByProject,
    this.downloadsByProject,
  );

  factory ProjectsPageData.fromJson(Map<String, dynamic> json) {
    final projects = _mapList(
      json['projects'],
    ).map(ProjectSummary.fromJson).toList();
    final builds = <String, Map<String, dynamic>>{};
    for (final item in _mapList(json['buildsRepos'])) {
      final key = _optionalString(item, 'projectKey');
      if (key.isNotEmpty) builds[key] = item;
    }
    final downloads = <String, List<Map<String, dynamic>>>{};
    for (final item in _mapList(json['downloads'])) {
      final key = _optionalString(item, 'projectKey');
      if (key.isNotEmpty) downloads.putIfAbsent(key, () => []).add(item);
    }
    return ProjectsPageData(projects, builds, downloads);
  }
}

String _requiredString(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! String || value.isEmpty) {
    throw ProjectContractException('$key must be a non-empty string');
  }
  return value;
}

bool _requiredBool(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! bool) throw ProjectContractException('$key must be a boolean');
  return value;
}

String _optionalString(Map<String, dynamic> json, String key) =>
    json[key] is String ? json[key] as String : '';
int _optionalInt(Map<String, dynamic> json, String key) =>
    json[key] is num ? (json[key] as num).toInt() : 0;
Map<String, dynamic>? _optionalMap(Map<String, dynamic> json, String key) =>
    json[key] is Map ? Map<String, dynamic>.from(json[key] as Map) : null;
List<Map<String, dynamic>> _mapList(dynamic value) =>
    (value as List? ?? const [])
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList();
