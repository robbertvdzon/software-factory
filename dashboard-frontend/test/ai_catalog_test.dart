import 'package:flutter_test/flutter_test.dart';
import 'package:softwarefactory_dashboard/ai_catalog.dart';

void main() {
  test('OpenAI catalog exposes the GPT-5.6 family', () {
    expect(aiModelsBySupplier['openai'], [
      'gpt-5.6-sol',
      'gpt-5.6-terra',
      'gpt-5.6-luna',
    ]);
  });
}
