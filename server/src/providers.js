/**
 * Provider registry for the AURIX backend.
 *
 * Every entry describes one real, callable LLM endpoint. All OpenAI-compatible
 * providers share a single transport; Gemini has its own because the wire
 * format differs. Keys come from environment variables, so the backend can be
 * deployed with any subset configured and routing adapts automatically.
 */

export const PROVIDERS = [
	{
		id: 'groq',
		label: 'Groq',
		kind: 'openai',
		baseUrl: 'https://api.groq.com/openai/v1',
		envVar: 'GROQ_API_KEY',
		defaultModel: 'openai/gpt-oss-120b',
		priority: 10,
	},
	{
		id: 'cerebras',
		label: 'Cerebras',
		kind: 'openai',
		baseUrl: 'https://api.cerebras.ai/v1',
		envVar: 'CEREBRAS_API_KEY',
		defaultModel: 'gpt-oss-120b',
		priority: 20,
	},
	{
		id: 'openrouter',
		label: 'OpenRouter',
		kind: 'openai',
		baseUrl: 'https://openrouter.ai/api/v1',
		envVar: 'OPENROUTER_API_KEY',
		defaultModel: 'meta-llama/llama-3.3-70b-instruct',
		priority: 30,
	},
	{
		id: 'mistral',
		label: 'Mistral',
		kind: 'openai',
		baseUrl: 'https://api.mistral.ai/v1',
		envVar: 'MISTRAL_API_KEY',
		defaultModel: 'mistral-small-latest',
		priority: 40,
	},
	{
		id: 'gemini',
		label: 'Google Gemini',
		kind: 'gemini',
		baseUrl: 'https://generativelanguage.googleapis.com/v1beta',
		envVar: 'GEMINI_API_KEY',
		defaultModel: 'gemini-2.5-flash',
		priority: 50,
	},
	{
		id: 'openai',
		label: 'OpenAI',
		kind: 'openai',
		baseUrl: 'https://api.openai.com/v1',
		envVar: 'OPENAI_API_KEY',
		defaultModel: 'gpt-4o-mini',
		priority: 60,
	},
]

/**
 * Reads every key configured for a provider. Multiple keys are supported via
 * comma separation or numbered suffixes, which gives free-tier users a simple
 * way to pool quota.
 */
export function keysFor(provider, env = process.env) {
	const keys = []
	const push = (raw) => {
		if (!raw) return
		raw.split(',').map((k) => k.trim()).filter(Boolean).forEach((k) => keys.push(k))
	}
	push(env[provider.envVar])
	for (let i = 2; i <= 5; i += 1) push(env[provider.envVar + '_' + i])
	return [...new Set(keys)]
}

/** Providers that actually have at least one key configured. */
export function configuredProviders(env = process.env) {
	return PROVIDERS.filter((p) => keysFor(p, env).length > 0)
}

export function providerById(id) {
	return PROVIDERS.find((p) => p.id === id) || null
}
