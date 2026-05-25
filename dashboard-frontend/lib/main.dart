import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:url_launcher/url_launcher.dart';

void main() {
  runApp(const SoftwareFactoryDashboard());
}

const buildSha = String.fromEnvironment('BUILD_SHA', defaultValue: 'dev');
const buildTimestamp = String.fromEnvironment(
  'BUILD_TIMESTAMP',
  defaultValue: 'dev',
);

class SoftwareFactoryDashboard extends StatelessWidget {
  const SoftwareFactoryDashboard({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Software Factory',
      theme: AppTheme.light(),
      home: const DashboardScreen(),
    );
  }
}

class AppTheme {
  static ThemeData light() {
    const seed = Color(0xff6366f1);
    final scheme = ColorScheme.fromSeed(seedColor: seed);
    return ThemeData(
      colorScheme: scheme,
      scaffoldBackgroundColor: Colors.white,
      useMaterial3: true,
      appBarTheme: const AppBarTheme(elevation: 0, centerTitle: false),
      cardTheme: CardThemeData(
        color: const Color(0xfff6f7fb),
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        margin: EdgeInsets.zero,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xfff1f3f8),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
      chipTheme: ChipThemeData(
        side: BorderSide.none,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    );
  }
}

class ApiClient {
  static const baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: '',
  );
  String? token;

  Future<void> login(String username, String password) async {
    final response = await http.post(
      Uri.parse('$baseUrl/api/v1/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'username': username, 'password': password}),
    );
    if (response.statusCode >= 400) throw Exception(response.body);
    token =
        (jsonDecode(response.body) as Map<String, dynamic>)['token'] as String;
  }

  Future<Map<String, dynamic>> getJson(String path) async {
    final response = await http.get(
      Uri.parse('$baseUrl$path'),
      headers: _headers(),
    );
    if (response.statusCode >= 400) throw Exception(response.body);
    return Map<String, dynamic>.from(jsonDecode(response.body) as Map);
  }

  Future<void> post(String path) async {
    final response = await http.post(
      Uri.parse('$baseUrl$path'),
      headers: _headers(),
    );
    if (response.statusCode >= 400) throw Exception(response.body);
  }

  Map<String, String> _headers() => {
    'Content-Type': 'application/json',
    if (token != null) 'Authorization': 'Bearer $token',
  };
}

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final api = ApiClient();
  final username = TextEditingController(text: 'admin');
  final password = TextEditingController();
  var selectedIndex = 0;
  var loading = false;
  String? error;
  Map<String, dynamic>? state;
  List<Map<String, dynamic>> repositories = [];
  List<Map<String, dynamic>> stories = [];
  List<Map<String, dynamic>> downloads = [];
  final workflowsByRepo = <String, Map<String, dynamic>>{};
  Map<String, dynamic>? selectedRepo;
  Map<String, dynamic>? selectedStory;

  Future<void> _login() async {
    await _run(() async {
      await api.login(username.text, password.text);
      await _refreshAll();
    });
  }

  Future<void> _refreshAll() async {
    state = await api.getJson('/api/v1/state');
    repositories = _list(
      (await api.getJson('/api/v1/repositories'))['repositories'],
    );
    stories = _list((await api.getJson('/api/v1/stories'))['stories']);
    final downloadsResponse = await api.getJson('/api/v1/downloads');
    downloads = _list(downloadsResponse['downloads']);
    selectedRepo ??= repositories.isNotEmpty ? repositories.first : null;
    await _loadBuilds();
  }

  Future<void> _loadBuilds() async {
    workflowsByRepo.clear();
    for (final repo in repositories) {
      final owner = text(repo['owner']);
      final name = text(repo['repo']);
      if (owner.isEmpty || name.isEmpty) continue;
      workflowsByRepo['$owner/$name'] = await api.getJson(
        '/api/v1/repositories/$owner/$name/workflows',
      );
    }
  }

  Future<void> _selectStory(String key) async {
    await _run(() async {
      selectedStory = await api.getJson('/api/v1/stories/$key');
      selectedIndex = 2;
    });
  }

  Future<void> _command(String key, String command) async {
    await _run(() async {
      await api.post('/api/v1/stories/$key/cmd/$command');
      selectedStory = await api.getJson('/api/v1/stories/$key');
    });
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      await action();
    } catch (e) {
      error = e.toString();
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (api.token == null) return _loginView();
    final screen = _screen();
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 760;
        if (!wide) {
          return Scaffold(
            appBar: AppBar(
              title: Text(_navItems[selectedIndex].label),
              actions: [_refreshButton()],
            ),
            drawer: Drawer(child: SafeArea(child: _sidebar())),
            body: screen,
          );
        }
        return Scaffold(
          body: Row(
            children: [
              SizedBox(width: 220, child: _sidebar()),
              const VerticalDivider(width: 1),
              Expanded(child: screen),
            ],
          ),
        );
      },
    );
  }

  Widget _loginView() => Scaffold(
    body: Center(
      child: SizedBox(
        width: 430,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Container(
                  height: 64,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: const Color(0xffdedafe),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: const Text(
                    'SF',
                    style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
                  ),
                ),
                const SizedBox(height: 22),
                const Text(
                  'Software Factory',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 4),
                const Text(
                  'Login op het remote dashboard',
                  style: TextStyle(color: Colors.black54),
                ),
                const SizedBox(height: 20),
                TextField(
                  controller: username,
                  decoration: const InputDecoration(
                    labelText: 'Gebruikersnaam',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: password,
                  decoration: const InputDecoration(labelText: 'Wachtwoord'),
                  obscureText: true,
                ),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: loading ? null : _login,
                  child: Text(loading ? 'Inloggen...' : 'Inloggen'),
                ),
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      error!,
                      style: const TextStyle(color: Colors.red),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    ),
  );

  Widget _sidebar() => Container(
    color: Colors.white,
    child: Padding(
      padding: const EdgeInsets.fromLTRB(12, 20, 12, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Row(
            children: [
              _Logo(),
              SizedBox(width: 10),
              Text(
                'Software Factory',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
            ],
          ),
          const SizedBox(height: 22),
          for (var i = 0; i < _navItems.length; i++)
            _NavButton(
              item: _navItems[i],
              selected: i == selectedIndex,
              onTap: () {
                setState(() => selectedIndex = i);
                if (Navigator.canPop(context)) Navigator.pop(context);
              },
            ),
          const Spacer(),
          Text(
            'Build: $buildSha\n$buildTimestamp',
            style: const TextStyle(fontSize: 11, color: Colors.black54),
          ),
        ],
      ),
    ),
  );

  Widget _screen() {
    switch (selectedIndex) {
      case 1:
        return _page(
          'Repositories',
          '3 repositories uit YouTrack projectbeschrijvingen',
          _repositoriesView(),
        );
      case 2:
        return _page(
          'Stories',
          'Factory work gekoppeld aan repositories',
          _storiesView(),
        );
      case 3:
        return _page(
          'Agents',
          'Lopende en recente factory agents',
          _agentsView(),
        );
      case 4:
        return _page(
          'Buildstraat',
          'GitHub Actions workflows per beheerde repository',
          _buildsView(),
        );
      case 5:
        return _page(
          'Downloads',
          "APK's en user-facing release assets uit GitHub Releases",
          _downloadsView(),
        );
      case 6:
        return _page(
          'Settings',
          'Dashboard configuratie en integratie status',
          _settingsView(),
        );
      default:
        return _page(
          'Dashboard',
          'Remote overview van beheerde repositories',
          _dashboardView(),
          showBuild: true,
        );
    }
  }

  Widget _page(
    String title,
    String subtitle,
    Widget child, {
    bool showBuild = false,
  }) => RefreshIndicator(
    onRefresh: () => _run(_refreshAll),
    child: ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 26,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(subtitle, style: const TextStyle(color: Colors.black54)),
                  if (showBuild)
                    Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Text(
                        'Dashboard build: $buildSha · $buildTimestamp',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.black54,
                        ),
                      ),
                    ),
                ],
              ),
            ),
            _refreshButton(),
          ],
        ),
        if (error != null)
          Padding(
            padding: const EdgeInsets.only(top: 16),
            child: _attention('Fout', error!),
          ),
        const SizedBox(height: 18),
        child,
      ],
    ),
  );

  Widget _dashboardView() {
    final activeStories = number(state?['activeStories']);
    final activeAgents = number(state?['activeAgents']);
    final blocked = repositories.fold<int>(
      0,
      (sum, repo) => sum + number(repo['blockedStories']),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        LayoutBuilder(
          builder: (context, c) => Wrap(
            spacing: 14,
            runSpacing: 14,
            children: [
              _metric('Repositories', '${repositories.length}', c.maxWidth),
              _metric('Active stories', '$activeStories', c.maxWidth),
              _metric('Active agents', '$activeAgents', c.maxWidth),
              _metric('APK downloads', '${downloads.length}', c.maxWidth),
            ],
          ),
        ),
        const SizedBox(height: 24),
        _sectionTitle('Repositories'),
        _repoTable(compact: true),
        const SizedBox(height: 24),
        _sectionTitle('Attention'),
        if (blocked == 0) _panel(const Text('Geen blockers gevonden.')),
        for (final repo in repositories.where(
          (r) => number(r['blockedStories']) > 0,
        ))
          _attention(
            '${text(repo['projectKey'])} blocked',
            '${text(repo['projectName'])}: ${number(repo['blockedStories'])} blocked story/stories.',
          ),
        if (downloads.isNotEmpty) ...[
          const SizedBox(height: 14),
          _panel(
            Text(
              'Latest APK\n${text(downloads.first['repository'])} · ${text(downloads.first['name'])} · ${formatBytes(number(downloads.first['size']))} · ${formatTimestamp(downloads.first['createdAt'])}',
            ),
          ),
        ],
      ],
    );
  }

  Widget _repositoriesView() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _filters([
        'All',
        'Active AI',
        'Has APK',
        'No CI',
        'Failed',
      ], selected: 'All'),
      _repoTable(compact: false),
      if (selectedRepo != null) ...[
        const SizedBox(height: 24),
        _repositoryDetail(selectedRepo!),
      ],
    ],
  );

  Widget _repoTable({required bool compact}) => _panel(
    Column(
      children: [
        _tableHeader(
          compact
              ? ['Repository', 'Project', 'Branch', 'CI', 'APK', 'Attention']
              : [
                  'Repository',
                  'Project',
                  'Branch',
                  'Workflows',
                  'Latest CI',
                  'APK',
                  '',
                ],
        ),
        for (final repo in repositories)
          InkWell(
            onTap: () => setState(() {
              selectedRepo = repo;
              selectedIndex = 1;
            }),
            child: _tableRow(
              compact
                  ? [
                      _repoName(repo),
                      text(repo['projectKey']),
                      text(repo['defaultBranch'], fallback: '-'),
                      _status(
                        text(
                          repo['latestConclusion'],
                          fallback: number(repo['workflowCount']) == 0
                              ? 'missing'
                              : '-',
                        ),
                      ),
                      _status(boolValue(repo['apkAvailable']) ? 'yes' : 'no'),
                      number(repo['blockedStories']) > 0
                          ? _status('blocked')
                          : const Text('-'),
                    ]
                  : [
                      _repoName(repo),
                      text(repo['projectKey']),
                      text(repo['defaultBranch'], fallback: '-'),
                      '${number(repo['workflowCount'])}',
                      _status(
                        text(
                          repo['latestConclusion'],
                          fallback: number(repo['workflowCount']) == 0
                              ? 'no CI'
                              : '-',
                        ),
                      ),
                      boolValue(repo['apkAvailable'])
                          ? _status('available')
                          : const Text('-'),
                      const Text(
                        'Open',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ],
            ),
          ),
      ],
    ),
  );

  Widget _repositoryDetail(Map<String, dynamic> repo) {
    final slug = '${text(repo['owner'])}/${text(repo['repo'])}';
    final workflowData = workflowsByRepo[slug] ?? {};
    final runs = _list(workflowData['runs']);
    final repoDownloads = downloads
        .where((d) => text(d['repository']) == slug)
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _sectionTitle(
          '${text(repo['projectKey'])} - ${text(repo['projectName'])}',
        ),
        Text(slug, style: const TextStyle(color: Colors.black54)),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _chip('Branch', text(repo['defaultBranch'], fallback: '-')),
            _chip('Workflows', '${number(repo['workflowCount'])}'),
            _chip('Latest CI', text(repo['latestConclusion'], fallback: '-')),
            _chip('APK', boolValue(repo['apkAvailable']) ? 'yes' : 'no'),
          ],
        ),
        const SizedBox(height: 16),
        _panel(
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Latest activity',
                style: TextStyle(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 8),
              for (final run in runs.take(4))
                _listLine(
                  text(run['displayTitle'], fallback: text(run['name'])),
                  '${text(run['headBranch'])} · ${text(run['conclusion'], fallback: text(run['status']))}',
                ),
              if (repoDownloads.isNotEmpty)
                _listLine(
                  'Latest APK',
                  '${text(repoDownloads.first['name'])} · ${formatBytes(number(repoDownloads.first['size']))} · ${formatTimestamp(repoDownloads.first['createdAt'])}',
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildsView() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _filters(
        repositories
            .map((r) => text(r['projectKey']))
            .where((v) => v.isNotEmpty)
            .toList(),
        selected: repositories.isEmpty
            ? ''
            : text(repositories.first['projectKey']),
      ),
      for (final repo in repositories) ...[
        _sectionTitle(text(repo['projectName'])),
        _buildTable(repo),
        const SizedBox(height: 16),
      ],
    ],
  );

  Widget _buildTable(Map<String, dynamic> repo) {
    final slug = '${text(repo['owner'])}/${text(repo['repo'])}';
    final data = workflowsByRepo[slug] ?? {};
    final workflows = _list(data['workflows']);
    final runs = _list(data['runs']);
    if (workflows.isEmpty) {
      return _panel(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: const [
            _Badge('No CI', Colors.orange),
            SizedBox(height: 10),
            Text(
              'No GitHub Actions workflows found. This repository can still be handled by the factory, but it has no visible buildstraat yet.',
              style: TextStyle(color: Colors.black54),
            ),
          ],
        ),
      );
    }
    return _panel(
      Column(
        children: [
          _tableHeader(['Workflow', 'Last result', 'Branch', 'Event', 'Run']),
          for (final workflow in workflows)
            _tableRow([
              text(workflow['name']),
              _status(
                _latestRunForWorkflow(workflow, runs)?['conclusion'] ??
                    _latestRunForWorkflow(workflow, runs)?['status'] ??
                    '-',
              ),
              text(
                _latestRunForWorkflow(workflow, runs)?['headBranch'],
                fallback: '-',
              ),
              text(
                _latestRunForWorkflow(workflow, runs)?['event'],
                fallback: '-',
              ),
              text(
                _latestRunForWorkflow(workflow, runs)?['displayTitle'],
                fallback: '-',
              ),
            ]),
        ],
      ),
    );
  }

  Widget _downloadsView() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      if (downloads.isEmpty) _panel(const Text('Geen APK downloads gevonden.')),
      for (final download in downloads) _downloadCard(download),
      for (final repo in repositories.where(
        (r) => !downloads.any(
          (d) =>
              text(d['repository']) == '${text(r['owner'])}/${text(r['repo'])}',
        ),
      ))
        _panel(
          ListTile(
            leading: const _Logo(),
            title: Text(text(repo['projectName'])),
            subtitle: Text(
              boolValue(repo['apkAvailable'])
                  ? 'APK metadata ontbreekt.'
                  : 'No APK release found.',
            ),
            trailing: const _Badge('no APK', Colors.orange),
          ),
        ),
      const SizedBox(height: 16),
      _panel(
        const Text(
          'Advanced artifacts\nGitHub Actions artifacts zijn bewust secundair. Veel artifacts zijn interne .dockerbuild bestanden en horen niet standaard tussen user downloads.',
        ),
      ),
    ],
  );

  Widget _storiesView() {
    final detail = selectedStory;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _filters([
          'All',
          ...repositories.map((r) => text(r['projectKey'])),
          'Blocked',
          'Active',
        ], selected: 'All'),
        _panel(
          Column(
            children: [
              _tableHeader([
                'Story',
                'Summary',
                'Repository',
                'Phase',
                'Status',
              ]),
              for (final story in stories)
                InkWell(
                  onTap: () => _selectStory(text(story['key'])),
                  child: _tableRow([
                    Text(
                      text(story['key']),
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                    text(story['summary']),
                    repoLabel(text(story['targetRepo'])),
                    text(story['aiPhase'], fallback: '-'),
                    text(story['error']).isNotEmpty
                        ? _status('blocked')
                        : _status(text(story['status'], fallback: '-')),
                  ]),
                ),
            ],
          ),
        ),
        if (detail != null) ...[
          const SizedBox(height: 24),
          _storyDetail(detail),
        ],
      ],
    );
  }

  Widget _storyDetail(Map<String, dynamic> detail) {
    final issue = Map<String, dynamic>.from((detail['issue'] as Map?) ?? {});
    final run = Map<String, dynamic>.from((detail['run'] as Map?) ?? {});
    final agents = _list(detail['agentRuns']);
    final key = text(issue['key']);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _sectionTitle('$key - ${text(issue['summary'])}'),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _chip('Status', text(issue['status'], fallback: '-')),
            _chip('Phase', text(issue['aiPhase'], fallback: '-')),
            _chip('Supplier', text(issue['aiSupplier'], fallback: '-')),
            _chip('Tokens', '${number(run['totalTokens'])}'),
          ],
        ),
        if (text(issue['error']).isNotEmpty) ...[
          const SizedBox(height: 12),
          _attention('Blocked', text(issue['error'])),
        ],
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final command in [
              'pause',
              'resume',
              'merge',
              'delete',
              're-implement',
            ])
              FilledButton.tonal(
                onPressed: loading ? null : () => _command(key, command),
                child: Text(command),
              ),
          ],
        ),
        const SizedBox(height: 16),
        _panel(
          _kv({
            'Target repo': text(
              issue['targetRepo'],
              fallback: text(run['targetRepo'], fallback: '-'),
            ),
            'PR': text(
              run['prUrl'],
              fallback: text(run['prNumber'], fallback: '-'),
            ),
            'Started': text(run['startedAt'], fallback: '-'),
            'Ended': text(run['endedAt'], fallback: '-'),
            'Cost': text(run['totalCostUsd'], fallback: '-'),
          }),
        ),
        const SizedBox(height: 16),
        _panel(
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Agent run timeline',
                style: TextStyle(fontWeight: FontWeight.w800),
              ),
              for (final agent in agents)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    '${text(agent['role'])} - ${text(agent['outcome'], fallback: 'running')}',
                  ),
                  subtitle: Text(
                    text(
                      agent['summary'],
                      fallback: text(agent['containerName']),
                    ),
                  ),
                  trailing: Text('${number(agent['totalTokens'])} tokens'),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _agentsView() {
    final active = _list(state?['activeAgentRuns']);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          spacing: 14,
          runSpacing: 14,
          children: [
            _metric('Active agents', '${active.length}', 800),
            _metric('Recent runs', '${active.length}', 800),
            _metric(
              'Blocked stories',
              '${repositories.fold<int>(0, (sum, r) => sum + number(r['blockedStories']))}',
              800,
            ),
          ],
        ),
        const SizedBox(height: 24),
        _sectionTitle('Active agents'),
        if (active.isEmpty) _panel(const Text('Geen actieve agents.')),
        for (final agent in active)
          _panel(
            Text(
              '${text(agent['storyKey'])} · ${text(agent['role'])}\n${text(agent['containerName'])}',
            ),
          ),
      ],
    );
  }

  Widget _settingsView() => _panel(
    _kv({
      'YouTrack': 'connected',
      'GitHub': 'connected',
      'Database': 'connected',
      'Managed projects': repositories
          .map((r) => text(r['projectKey']))
          .join(', '),
      'Dashboard namespace': 'software-factory',
      'Build SHA': buildSha,
      'Build timestamp': buildTimestamp,
    }),
  );

  Widget _downloadCard(Map<String, dynamic> download) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: _panel(
      ListTile(
        leading: const _Logo(text: 'APK'),
        title: Text(text(download['repository'])),
        subtitle: Text(
          '${text(download['name'])} · ${formatBytes(number(download['size']))} · ${formatTimestamp(download['createdAt'])} · ${text(download['releaseTag'], fallback: '-')}',
        ),
        trailing: FilledButton.tonal(
          onPressed: () => openUrl(text(download['downloadUrl'])),
          child: const Text('Download APK'),
        ),
      ),
    ),
  );

  Widget _metric(String label, String value, double maxWidth) => SizedBox(
    width: maxWidth < 720 ? (maxWidth - 14) / 2 : (maxWidth - 42) / 4,
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(color: Colors.black54)),
            const SizedBox(height: 6),
            Text(
              value,
              style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _panel(Widget child) => Card(
    child: Padding(padding: const EdgeInsets.all(18), child: child),
  );
  Widget _sectionTitle(String title) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Text(
      title,
      style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800),
    ),
  );
  Widget _attention(String title, String body) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xffffd9d5),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      '$title\n$body',
      style: const TextStyle(color: Color(0xffb42318)),
    ),
  );
  Widget _filters(List<String> values, {required String selected}) => Wrap(
    spacing: 8,
    runSpacing: 8,
    children: [
      for (final value in values)
        Chip(
          label: Text(value),
          backgroundColor: value == selected ? const Color(0xffdedafe) : null,
        ),
    ],
  );
  Widget _chip(String label, String value) =>
      Chip(label: Text('$label: $value'));
  Widget _tableHeader(List<String> labels) => _tableRow([
    for (final label in labels)
      Text(
        label.toUpperCase(),
        style: const TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w800,
          color: Colors.black54,
        ),
      ),
  ], header: true);
  Widget _tableRow(List<dynamic> cells, {bool header = false}) => Container(
    decoration: BoxDecoration(
      border: Border(bottom: BorderSide(color: Colors.grey.shade300)),
    ),
    padding: EdgeInsets.symmetric(vertical: header ? 8 : 12),
    child: Row(
      children: [
        for (final cell in cells)
          Expanded(child: cell is Widget ? cell : Text(cell.toString())),
      ],
    ),
  );

  Widget _repoName(Map<String, dynamic> repo) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        text(repo['projectName']),
        style: const TextStyle(fontWeight: FontWeight.w800),
      ),
      Text(
        '${text(repo['owner'])}/${text(repo['repo'])}',
        style: const TextStyle(color: Colors.black54, fontSize: 12),
      ),
    ],
  );

  Widget _status(String value) {
    final normalized = value.toLowerCase();
    if (normalized.contains('success') ||
        normalized == 'ok' ||
        normalized == 'yes' ||
        normalized == 'available' ||
        normalized == 'done') {
      return const _Badge('OK', Colors.green);
    }
    if (normalized.contains('blocked') ||
        normalized.contains('fail') ||
        normalized.contains('error')) {
      return const _Badge('blocked', Colors.red);
    }
    if (normalized.contains('missing') ||
        normalized.contains('no') ||
        normalized == '-') {
      return const _Badge('no CI', Colors.orange);
    }
    return _Badge(value, Colors.blue);
  }

  Widget _kv(Map<String, String> values) => Column(
    children: [
      for (final entry in values.entries)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            children: [
              SizedBox(
                width: 150,
                child: Text(
                  entry.key,
                  style: const TextStyle(color: Colors.black54),
                ),
              ),
              Expanded(
                child: Text(
                  entry.value,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
        ),
    ],
  );

  Widget _listLine(String title, String subtitle) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      children: [
        Expanded(
          child: Text(
            title,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
        ),
        Expanded(
          child: Text(subtitle, style: const TextStyle(color: Colors.black54)),
        ),
      ],
    ),
  );

  Widget _refreshButton() => IconButton(
    onPressed: loading ? null : () => _run(_refreshAll),
    icon: const Icon(Icons.refresh),
  );

  Map<String, dynamic>? _latestRunForWorkflow(
    Map<String, dynamic> workflow,
    List<Map<String, dynamic>> runs,
  ) {
    final name = text(workflow['name']);
    return runs.firstWhere(
      (run) => text(run['name']) == name,
      orElse: () => {},
    );
  }
}

class _NavItem {
  final String label;
  final IconData icon;
  const _NavItem(this.label, this.icon);
}

const _navItems = [
  _NavItem('Dashboard', Icons.dashboard_outlined),
  _NavItem('Repositories', Icons.source_outlined),
  _NavItem('Stories', Icons.list_alt_outlined),
  _NavItem('Agents', Icons.smart_toy_outlined),
  _NavItem('Builds', Icons.account_tree_outlined),
  _NavItem('Downloads', Icons.download_outlined),
  _NavItem('Settings', Icons.settings_outlined),
];

class _NavButton extends StatelessWidget {
  final _NavItem item;
  final bool selected;
  final VoidCallback onTap;
  const _NavButton({
    required this.item,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Material(
        color: selected ? const Color(0xffdedafe) : Colors.transparent,
        borderRadius: BorderRadius.circular(10),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: [
                Icon(item.icon, size: 18),
                const SizedBox(width: 10),
                Text(
                  item.label,
                  style: TextStyle(
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Logo extends StatelessWidget {
  final String text;
  const _Logo({this.text = 'SF'});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 34,
      height: 34,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: const Color(0xffdedafe),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        text,
        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w800),
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  final String label;
  final MaterialColor color;
  const _Badge(this.label, this.color);

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 92,
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: color.shade50,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        textAlign: TextAlign.center,
        style: TextStyle(
          color: color.shade800,
          fontSize: 12,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

List<Map<String, dynamic>> _list(dynamic value) => (value as List? ?? [])
    .map((item) => Map<String, dynamic>.from(item as Map))
    .toList();

String text(dynamic value, {String fallback = ''}) {
  final result = value?.toString() ?? '';
  return result.isEmpty || result == 'null' ? fallback : result;
}

int number(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

bool boolValue(dynamic value) =>
    value == true || value?.toString().toLowerCase() == 'true';

String repoLabel(String repoUrl) => repoUrl
    .replaceFirst('https://github.com/robbertvdzon/', '')
    .replaceFirst('https://github.com/', '')
    .replaceAll('/', '');

String formatBytes(int bytes) {
  if (bytes <= 0) return '-';
  return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
}

String formatTimestamp(dynamic value) {
  final raw = text(value, fallback: '-');
  if (raw == '-') return raw;
  final parsed = DateTime.tryParse(raw);
  if (parsed == null) return raw;
  final local = parsed.toLocal();
  final year = local.year.toString().padLeft(4, '0');
  final month = local.month.toString().padLeft(2, '0');
  final day = local.day.toString().padLeft(2, '0');
  final hour = local.hour.toString().padLeft(2, '0');
  final minute = local.minute.toString().padLeft(2, '0');
  return '$year-$month-$day $hour:$minute';
}

Future<void> openUrl(String url) async {
  if (url.isEmpty) return;
  await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
}
