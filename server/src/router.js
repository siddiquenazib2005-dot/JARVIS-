/**
 * Multi-provider router with health tracking, key pooling and failover.
 *
 * Scoring mirrors the Android client so behaviour is identical on both sides:
 *   score = 45% success rate + 30% speed + 25% configured priority
 *
 * A provider that returns 401/403 is disabled for the process, 429 puts it in
 * a short cooldown, and network/timeout failures degrade its score without
 * removing it. The next healthy provider is tried automatically.
 */

import { PROVIDERS, configuredProviders, keysFor, providerById } from './providers.js'

const COOLDOWN_MS = 60_000
const REQUEST_TIMEOUT_MS = Number(process.env.AURIX_REQUEST_TIMEOUT_MS || 90_000)
const RETRYABLE = new Set([408, 409, 425, 429, 500, 502, 503, 504])
const NEUTRAL_SUCCESS = 0.8

/** providerId -> health record */
const health = new Map()

function record(providerId) {
	if (!health.has(providerId)) {
		health.set(providerId, {
			ok: 0,
			fail: 0,
			avgMs: 1500,
			cooldownUntil: 0,
			authFailed: false,
			lastError: null,
		})
	}
	return health.get(providerId)
}

function noteSuccess(providerId, ms) {
	const h = record(providerId)
	h.ok += 1
	h.avgMs = Math.round(h.avgMs * 0.7 + ms * 0.3)
	h.cooldownUntil = 0
	h.lastError = null
}

function noteFailure(providerId, status, message) {
	const h = record(providerId)
	h.fail += 1
	h.lastError = message
	if (status === 401 || status === 403) h.authFailed = true
	if (status === 429 || status === 503) h.cooldownUntil = Date.now() + COOLDOWN_MS
}

function successRate(h) {
	const total = h.ok + h.fail
	return total === 0 ? NEUTRAL_SUCCESS : h.ok / total
}

function selectable(provider) {
	const h = record(provider.id)
	if (h.authFailed) return false
	if (h.cooldownUntil > Date.now()) return false
	return keysFor(provider).length > 0
}

/** Ordered candidate list, best first. */
export function candidates(preferredId) {
	const available = configuredProviders().filter(selectable)
	const scored = available
		.map((provider) => {
			const h = record(provider.id)
			const speed = Math.max(0, 1 - Math.min(h.avgMs, 8000) / 8000)
			const priority = 1 - Math.min(provider.priority, 100) / 100
			const score = successRate(h) * 0.45 + speed * 0.3 + priority * 0.25
			return { provider, score }
		})
		.sort((a, b) => b.score - a.score)
		.map((entry) => entry.provider)

	if (!preferredId) return scored
	const preferred = scored.find((p) => p.id === preferredId)
	if (!preferred) return scored
	return [preferred, ...scored.filter((p) => p.id !== preferredId)]
}

/** Public health snapshot used by GET /v1/health. */
export function healthSnapshot() {
	return PROVIDERS.map((provider) => {
		const h = record(provider.id)
		const keys = keysFor(provider).length
		let state = 'not_configured'
		if (keys > 0) {
			if (h.authFailed) state = 'auth_failed'
			else if (h.cooldownUntil > Date.now()) state = 'cooldown'
			else if (h.fail > 0 && successRate(h) < 0.5) state = 'degraded'
			else state = 'healthy'
		}
		return {
			id: provider.id,
			label: provider.label,
			state,
			keys,
			defaultModel: provider.defaultModel,
			successes: h.ok,
			failures: h.fail,
			avgLatencyMs: h.avgMs,
			lastError: h.lastError,
		}
	})
}

function toGeminiBody(messages, system) {
	const contents = messages
		.filter((m) => m.role !== 'system')
		.map((m) => ({
			role: m.role === 'assistant' ? 'model' : 'user',
			parts: [{ text: String(m.content || '') }],
		}))
	const body = { contents }
	const systemText = system || messages.find((m) => m.role === 'system')?.content
	if (systemText) body.systemInstruction = { parts: [{ text: String(systemText) }] }
	return body
}

async function fetchWithTimeout(url, options) {
	const controller = new AbortController()
	const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
	try {
		return await fetch(url, { ...options, signal: controller.signal })
	} catch (err) {
		if (err?.name === 'AbortError') {
			const e = new Error('Provider timed out after ' + REQUEST_TIMEOUT_MS + 'ms')
			e.status = 504
			throw e
		}
		throw err
	} finally {
		clearTimeout(timer)
	}
}

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)) }

/**
 * Calls one provider once. Returns { text, model, providerId, latencyMs }.
 * Throws an Error carrying `status` so the caller can decide about failover.
 */
async function callProvider(provider, key, { messages, system, model, temperature }) {
	const started = Date.now()
	const chosenModel = model || provider.defaultModel

	let response
	if (provider.kind === 'gemini') {
		const url =
			provider.baseUrl +
			'/models/' +
			encodeURIComponent(chosenModel) +
			':generateContent?key=' +
			encodeURIComponent(key)
		response = await fetchWithTimeout(url, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(toGeminiBody(messages, system)),
		})
	} else {
		const payload = {
			model: chosenModel,
			messages: system
				? [{ role: 'system', content: system }, ...messages.filter((m) => m.role !== 'system')]
				: messages,
			stream: false,
		}
		if (typeof temperature === 'number') payload.temperature = temperature
		response = await fetchWithTimeout(provider.baseUrl + '/chat/completions', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				Authorization: 'Bearer ' + key,
			},
			body: JSON.stringify(payload),
		})
	}

	const raw = await response.text()
	if (!response.ok) {
		const error = new Error(extractError(raw) || 'HTTP ' + response.status)
		error.status = response.status
		throw error
	}

	const parsed = safeJson(raw)
	const text =
		provider.kind === 'gemini'
			? parsed?.candidates?.[0]?.content?.parts?.map((p) => p.text).join('') || ''
			: parsed?.choices?.[0]?.message?.content || ''

	if (!text.trim()) {
		const error = new Error('Provider returned no content')
		error.status = 502
		throw error
	}

	return {
		text,
		model: chosenModel,
		providerId: provider.id,
		providerLabel: provider.label,
		latencyMs: Date.now() - started,
	}
}

function safeJson(raw) {
	try {
		return JSON.parse(raw)
	} catch {
		return null
	}
}

function extractError(raw) {
	const parsed = safeJson(raw)
	return parsed?.error?.message || parsed?.message || null
}

/**
 * Runs a chat completion across the whole provider pool with key rotation and
 * failover. Resolves with the first success, or throws an aggregate error.
 */
export async function complete({ messages, system, model, provider, temperature }) {
	const pool = candidates(provider)
	if (pool.length === 0) {
		const error = new Error(
			'No provider is configured. Set at least one API key, for example GROQ_API_KEY.'
		)
		error.status = 503
		throw error
	}

	const attempts = []
	for (const candidate of pool) {
		const requestedModel =
			provider && candidate.id === provider ? model : candidate.defaultModel
		for (const key of keysFor(candidate)) {
			for (let tryNo = 0; tryNo < 2; tryNo += 1) {
			try {
				const result = await callProvider(candidate, key, {
					messages,
					system,
					model: requestedModel,
					temperature,
				})
				noteSuccess(candidate.id, result.latencyMs)
				return { ...result, attempts }
			} catch (err) {
				noteFailure(candidate.id, err.status, err.message)
				attempts.push({
					provider: candidate.id,
					status: err.status || 0,
					error: err.message,
				})
				if (err.status === 401 || err.status === 403) break
				if (!RETRYABLE.has(err.status || 0) || tryNo === 1) break
				await sleep(350 + Math.floor(Math.random() * 250))
			}
			}
		}
	}

	const error = new Error(
		'Every provider failed: ' + attempts.map((a) => a.provider + ' (' + a.error + ')').join('; ')
	)
	error.status = 502
	error.attempts = attempts
	throw error
}

/** Live reachability probe for one provider, used by POST /v1/probe. */
export async function probe(providerId) {
	const provider = providerById(providerId)
	if (!provider) return { providerId, reachable: false, detail: 'unknown provider' }
	const keys = keysFor(provider)
	if (keys.length === 0) return { providerId, reachable: false, detail: 'no key configured' }
	try {
		const result = await callProvider(provider, keys[0], {
			messages: [{ role: 'user', content: 'ping' }],
			system: 'Reply with the single word: pong',
		})
		noteSuccess(provider.id, result.latencyMs)
		return { providerId, reachable: true, latencyMs: result.latencyMs, model: result.model }
	} catch (err) {
		noteFailure(provider.id, err.status, err.message)
		return { providerId, reachable: false, status: err.status || 0, detail: err.message }
	}
}
