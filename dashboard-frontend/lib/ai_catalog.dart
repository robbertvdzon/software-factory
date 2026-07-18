/// Zelfde suppliers/modellen als core/AiRouting.kt (`AI_SUPPLIER_OPTIONS`/`MODELS_BY_SUPPLIER`);
/// hier gedupliceerd omdat er geen bridge-operatie is die deze catalogus opvraagt. Gedeeld tussen
/// het "Nieuwe story"-dialoog (stories_screen.dart) en het edit-dialoog op het story-detailscherm
/// (story_detail_screen.dart), zodat beide dezelfde lijst tonen.
const aiSuppliers = ['none', 'mock', 'claude', 'openai', 'copilot', 'microsoft'];
const aiModelsBySupplier = {
  'claude': ['claude-sonnet-5', 'claude-opus-4-8', 'claude-opus-4-7', 'claude-sonnet-4-6', 'claude-haiku-4-5'],
  'copilot': ['claude-opus-4.5', 'claude-sonnet-4.5', 'claude-haiku-4.5', 'gpt-4.1'],
  'openai': ['gpt-4.1'],
  'mock': ['dummy-ai-client'],
};
