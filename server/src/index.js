/**
 * AURIX backend entry point.
 *
 * Endpoints
 *   GET  /                     service banner
 *   GET  /v1/health            provider health + configured keys
 *   GET  /v1/models            catalogue the app can pin
 *   POST /v1/probe             live reachability probe for one provider
 *   POST /v1/chat              non-streaming chat with failover
 *   POST /v1/chat/stream       Server-Sent Events streaming chat
 *   GET  /v1/sessions          stored sessions
 *   GET  /v1/sessions/:id      recent turns for a session
 *   DELETE /v1/sessions/:id    wipe a session
 *   GET  /v1/facts             durable memory facts
 *   POST /v1/facts             remember a fact
 *   DELETE /v1/facts           forget a fact
 *
 * Auth: if AURIX_APP_TOKEN is set, every /v1 request must send
 * `Authorization: Bearer <token>`. Provider keys never leave the server.
 */

import express from 'express'
import cors from 'cors'
import { PROVIDERS, configuredProviders, keysFor } from './providers.js'
import { complete, healthSnapshot, probe, candidates } from './router.js'
import {
	appendTurn,
	clearSession,
	facts,
	forgetFact,
	listSessions,
	memoryPreamble,
	memoryStats,
	recentTurns,
	rememberFact,
} from './memory.js'

const app = express()
const PORT = process.env.PORT || 8080
const APP_TOKEN = process.env.AURIX_APP_TOKEN || ''

const DEFAULT_SYSTEM =
	'You are AURIX, a concise and capable Android assistant. ' +
	'Address the user as "sir". Keep answers short and practical. ' +
	'Use Markdown when it improves clarity.'

app.use(cors())
app.use(express.json({ limit: process.env.AURIX_JSON_LIMIT || '2mb' }))

const buckets = new Map()
app.use('/v1', (req, res, next) => {
	const limit = Number(process.env.AURIX_RATE_LIMIT || 120)
	const key = req.ip || req.headers['x-forwarded-for'] || 'local'
	const now = Date.now()
	const windowMs = 60_000
	const b = buckets.get(key) || { start: now, count: 0 }
	if (now - b.start > windowMs) { b.start = now; b.count = 0 }
	b.count += 1
	buckets.set(key, b)
	if (b.count > limit) return res.status(429).json({ error: { message: 'Rate limit exceeded' } })
	next()
})

app.use('/v1', (req, res, next) => {
	if (!APP_TOKEN) return next()
	const header = req.headers.authorization || ''
	if (header === 'Bearer ' + APP_TOKEN) return next()
	return res.status(401).json({ error: { message: 'Invalid app token' } })
})

app.get('/', (req, res) => {
	res.json({
		service: 'aurix-backend',
		version: '1.0.0',
		configuredProviders: configuredProviders().map((p) => p.id),
		authRequired: Boolean(APP_TOKEN),
	})
})

app.get('/v1/health', (req, res) => {
	res.json({
		ok: configuredProviders().length > 0,
		providers: healthSnapshot(),
		routeOrder: candidates().map((p) => p.id),
		uptimeSeconds: Math.round(process.uptime()),
		memory: memoryStats(),
	})
})

app.get('/v1/diagnostics', (req, res) => {
	res.json({
		ok: true,
		version: '1.1.0',
		node: process.version,
		uptimeSeconds: Math.round(process.uptime()),
		configuredProviders: configuredProviders().map((p) => p.id),
		providers: healthSnapshot(),
		memory: memoryStats(),
	})
})

app.get('/v1/models', (req, res) => {
	res.json({
		models: PROVIDERS.map((p) => ({
			provider: p.id,
			label: p.label,
			defaultModel: p.defaultModel,
			configured: keysFor(p).length > 0,
		})),
	})
})

app.post('/v1/probe', async (req, res) => {
	const providerId = req.body?.provider
	if (!providerId) {
		return res.status(400).json({ error: { message: 'provider is required' } })
	}
	res.json(await probe(providerId))
})

function normalizeMessages(history) {
	return history
		.filter((m) => m && typeof m.content !== 'undefined')
		.slice(-40)
		.map((m) => ({
			role: m.role === 'assistant' || m.role === 'system' ? m.role : 'user',
			content: String(m.content || '').slice(0, 12000),
		}))
}

function buildMessages(body) {
	const history = Array.isArray(body?.messages) ? normalizeMessages(body.messages) : []
	if (history.length > 0) return history
	const prompt = String(body?.prompt || '').trim().slice(0, 12000)
	if (!prompt) return []
	const sessionId = body?.sessionId
	const prior = sessionId ? recentTurns(sessionId, 16) : []
	return [
		...prior.map((t) => ({ role: t.role, content: t.content })),
		{ role: 'user', content: prompt },
	]
}

function systemPrompt(body) {
	return (body?.system || DEFAULT_SYSTEM) + memoryPreamble()
}

app.post('/v1/chat', async (req, res) => {
	const messages = buildMessages(req.body)
	if (messages.length === 0) {
		return res.status(400).json({ error: { message: 'prompt or messages is required' } })
	}
	try {
		const result = await complete({
			messages,
			system: systemPrompt(req.body),
			model: req.body?.model,
			provider: req.body?.provider,
			temperature: req.body?.temperature,
		})
		const sessionId = req.body?.sessionId
		if (sessionId) {
			appendTurn(sessionId, 'user', messages[messages.length - 1].content)
			appendTurn(sessionId, 'assistant', result.text)
		}
		res.json({
			text: result.text,
			provider: result.providerId,
			providerLabel: result.providerLabel,
			model: result.model,
			latencyMs: result.latencyMs,
			failover: result.attempts,
		})
	} catch (err) {
		res.status(err.status || 500).json({
			error: { message: err.message, attempts: err.attempts || [] },
		})
	}
})

/**
 * SSE streaming. Providers are called non-streaming and the reply is chunked
 * out word-group by word-group, which keeps one identical failover path for
 * every provider while still giving the app a live typing effect.
 */
app.post('/v1/chat/stream', async (req, res) => {
	const messages = buildMessages(req.body)
	if (messages.length === 0) {
		return res.status(400).json({ error: { message: 'prompt or messages is required' } })
	}

	res.setHeader('Content-Type', 'text/event-stream')
	res.setHeader('Cache-Control', 'no-cache')
	res.setHeader('Connection', 'keep-alive')
	res.flushHeaders?.()

	const send = (payload) => res.write('data: ' + JSON.stringify(payload) + '\n\n')

	try {
		const result = await complete({
			messages,
			system: systemPrompt(req.body),
			model: req.body?.model,
			provider: req.body?.provider,
			temperature: req.body?.temperature,
		})

		send({
			meta: {
				provider: result.providerId,
				providerLabel: result.providerLabel,
				model: result.model,
				latencyMs: result.latencyMs,
			},
		})

		const words = result.text.split(/(\s+)/)
		let buffer = ''
		for (const word of words) {
			buffer += word
			if (buffer.length >= 18) {
				send({ choices: [{ delta: { content: buffer } }] })
				buffer = ''
				await new Promise((r) => setTimeout(r, 12))
			}
		}
		if (buffer) send({ choices: [{ delta: { content: buffer } }] })

		const sessionId = req.body?.sessionId
		if (sessionId) {
			appendTurn(sessionId, 'user', messages[messages.length - 1].content)
			appendTurn(sessionId, 'assistant', result.text)
		}

		res.write('data: [DONE]\n\n')
		res.end()
	} catch (err) {
		send({ error: { message: err.message } })
		res.write('data: [DONE]\n\n')
		res.end()
	}
})

app.get('/v1/sessions', (req, res) => res.json({ sessions: listSessions() }))

app.get('/v1/sessions/:id', (req, res) =>
	res.json({ id: req.params.id, turns: recentTurns(req.params.id, 100) })
)

app.delete('/v1/sessions/:id', (req, res) => {
	clearSession(req.params.id)
	res.json({ ok: true })
})

app.get('/v1/facts', (req, res) => res.json({ facts: facts() }))

app.post('/v1/facts', (req, res) => res.json({ facts: rememberFact(req.body?.text) }))

app.delete('/v1/facts', (req, res) => res.json({ facts: forgetFact(req.body?.text) }))

app.use((req, res) => res.status(404).json({ error: { message: 'Not found' } }))

app.listen(PORT, () => {
	const ready = configuredProviders().map((p) => p.id)
	console.log('AURIX backend listening on port ' + PORT)
	console.log(
		ready.length > 0
			? 'Configured providers: ' + ready.join(', ')
			: 'WARNING: no provider keys configured yet'
	)
})
