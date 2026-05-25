import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

void main() {
  runApp(const SoftwareFactoryDashboard());
}

class SoftwareFactoryDashboard extends StatelessWidget {
  const SoftwareFactoryDashboard({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Software Factory',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff4f46e5)),
        scaffoldBackgroundColor: const Color(0xfff7f8fb),
        useMaterial3: true,
      ),
      home: const DashboardScreen(),
    );
  }
}

class ApiClient {
  static const baseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: '');
  String? token;

  Future<void> login(String username, String password) async {
    final response = await http.post(
      Uri.parse('$baseUrl/api/v1/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'username': username, 'password': password}),
    );
    if (response.statusCode >= 400) throw Exception(response.body);
    token = jsonDecode(response.body)['token'] as String;
  }

  Future<Map<String, dynamic>> getJson(String path) async {
    final response = await http.get(Uri.parse('$baseUrl$path'), headers: _headers());
    if (response.statusCode >= 400) throw Exception(response.body);
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  Future<void> post(String path) async {
    final response = await http.post(Uri.parse('$baseUrl$path'), headers: _headers());
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
  bool loading = false;
  String? error;
  Map<String, dynamic>? state;
  List<dynamic> stories = [];
  Map<String, dynamic>? selected;

  Future<void> _login() async {
    await _run(() async {
      await api.login(username.text, password.text);
      await _refresh();
    });
  }

  Future<void> _refresh() async {
    state = await api.getJson('/api/v1/state');
    stories = (await api.getJson('/api/v1/stories'))['stories'] as List<dynamic>;
    if (selected != null) {
      selected = await api.getJson('/api/v1/stories/${selected!['issue']['key']}');
    }
  }

  Future<void> _select(String key) async {
    await _run(() async {
      selected = await api.getJson('/api/v1/stories/$key');
    });
  }

  Future<void> _command(String key, String command) async {
    await _run(() async {
      await api.post('/api/v1/stories/$key/cmd/$command');
      selected = await api.getJson('/api/v1/stories/$key');
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
    return Scaffold(
      appBar: AppBar(
        title: const Text('Software Factory'),
        actions: [
          IconButton(onPressed: loading ? null : () => _run(_refresh), icon: const Icon(Icons.refresh)),
        ],
      ),
      body: Row(
        children: [
          SizedBox(width: 420, child: _storiesPane()),
          const VerticalDivider(width: 1),
          Expanded(child: _detailPane()),
        ],
      ),
    );
  }

  Widget _loginView() => Scaffold(
        body: Center(
          child: SizedBox(
            width: 360,
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text('Software Factory', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 20),
                    TextField(controller: username, decoration: const InputDecoration(labelText: 'Username')),
                    const SizedBox(height: 12),
                    TextField(controller: password, decoration: const InputDecoration(labelText: 'Password'), obscureText: true),
                    const SizedBox(height: 20),
                    FilledButton(onPressed: loading ? null : _login, child: Text(loading ? 'Signing in...' : 'Sign in')),
                    if (error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(error!, style: const TextStyle(color: Colors.red))),
                  ],
                ),
              ),
            ),
          ),
        ),
      );

  Widget _storiesPane() {
    final activeStories = state?['activeStories'] ?? 0;
    final activeAgents = state?['activeAgents'] ?? 0;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              _metric('Active stories', '$activeStories'),
              const SizedBox(width: 12),
              _metric('Active agents', '$activeAgents'),
            ],
          ),
        ),
        if (error != null) Padding(padding: const EdgeInsets.all(12), child: Text(error!, style: const TextStyle(color: Colors.red))),
        Expanded(
          child: ListView.separated(
            itemCount: stories.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final story = stories[index] as Map<String, dynamic>;
              return ListTile(
                selected: selected?['issue']?['key'] == story['key'],
                title: Text(story['key'] as String, style: const TextStyle(fontWeight: FontWeight.w700)),
                subtitle: Text(story['summary'] as String? ?? ''),
                trailing: Text(story['aiPhase'] as String? ?? story['status'] as String? ?? ''),
                onTap: () => _select(story['key'] as String),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _detailPane() {
    final detail = selected;
    if (detail == null) return const Center(child: Text('Select a story'));
    final issue = detail['issue'] as Map<String, dynamic>?;
    final run = detail['run'] as Map<String, dynamic>?;
    final agents = detail['agentRuns'] as List<dynamic>;
    final key = issue?['key'] as String? ?? '';
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('$key - ${issue?['summary'] ?? ''}', style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, children: [
          _chip('Status', issue?['status']),
          _chip('Phase', issue?['aiPhase']),
          _chip('Supplier', issue?['aiSupplier']),
          _chip('Tokens', run?['totalTokens']?.toString()),
        ]),
        if ((issue?['error'] as String?)?.isNotEmpty == true) _panel('Error', Text(issue!['error'] as String, style: const TextStyle(color: Colors.red))),
        const SizedBox(height: 16),
        Wrap(spacing: 8, children: [
          for (final command in ['pause', 'resume', 'merge', 'delete', 're-implement'])
            FilledButton.tonal(onPressed: loading ? null : () => _command(key, command), child: Text(command)),
        ]),
        const SizedBox(height: 16),
        _panel('Overview', _kv({
          'Target repo': issue?['targetRepo'],
          'PR': run?['prUrl'] ?? run?['prNumber']?.toString(),
          'Started': run?['startedAt'],
          'Ended': run?['endedAt'],
          'Cost': run?['totalCostUsd']?.toString(),
        })),
        _panel(
          'Agent runs',
          Column(
            children: [
              for (final item in agents.cast<Map<String, dynamic>>())
                ListTile(
                  title: Text('${item['role']} - ${item['outcome'] ?? 'running'}'),
                  subtitle: Text(item['summary'] as String? ?? item['containerName'] as String? ?? ''),
                  trailing: Text('${item['totalTokens']} tokens'),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _metric(String label, String value) => Expanded(
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label, style: const TextStyle(color: Colors.black54)),
              Text(value, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
            ]),
          ),
        ),
      );

  Widget _panel(String title, Widget child) => Card(
        margin: const EdgeInsets.only(top: 16),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w800)),
            const SizedBox(height: 10),
            child,
          ]),
        ),
      );

  Widget _kv(Map<String, dynamic> values) => Column(
        children: values.entries
            .map((e) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 5),
                  child: Row(children: [
                    SizedBox(width: 140, child: Text(e.key, style: const TextStyle(color: Colors.black54))),
                    Expanded(child: Text(e.value?.toString() ?? '-')),
                  ]),
                ))
            .toList(),
      );

  Widget _chip(String label, String? value) => Chip(label: Text('$label: ${value?.isNotEmpty == true ? value : '-'}'));
}
