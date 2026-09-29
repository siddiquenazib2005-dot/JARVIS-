/**
 * AURIX backend tool system.
 *
 * A small, dependency-free tool registry the model can call during chat.
 * Design rules:
 *  - every tool declares its args schema and runs behind a timeout
 *  - results are size-capped before they ever reach a prompt
 *  - fetch_text enforces an SSRF guard: public http(s) hosts only, every
 *    redirect hop re-validated, private/link-local ranges refused
 *  - the calculator is a real parser — no eval(), no Function()
 *
 * Endpoints (wired in index.js):
 *   GET  /v1/tools         list tools + schemas
 *   POST /v1/tool          { name, args } -> run one tool
 * Chat integration: the model may answer with {"tool":..,"args":..} once;
 * index.js executes it and re-asks the model with the result.
 *
 * Extra env (all optional):
 *   AURIX_TOOLS=off              disable tool prompting + endpoints' execution
 *   AURIX_ENABLED_TOOLS=a,b      allow-list (default: all built-ins)
 *   AURIX_TOOL_TIMEOUT_MS        per-tool timeout (default 12000)
 */

import dns from 'node:dns'
import net from 'node:net'

const TOOL_TIMEOUT_MS = Number(process.env.AURIX_TOOL_TIMEOUT_MS || 12_000)
const FETCH_TIMEOUT_MS = 10_000
const FETCH_MAX_BYTES = 200_000
const RESULT_TEXT_CAP = 4_000

const ENABLED_ENV = (process.env.AURIX_ENABLED_TOOLS || '')
	.split(',').map((s) => s.trim()).filter(Boolean)

function toolEnabled(name) {
	return ENABLED_ENV.length === 0 || ENABLED_ENV.includes(name)
}

function withTimeout(promise, ms, label) {
	let timer
	const timeout = new Promise((_, reject) => {
		timer = setTimeout(() => reject(new Error(label + ' timed out after ' + ms + 'ms')), ms)
	})
	return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

// ---------------------------------------------------------------------------
// Calculator — recursive-descent parser. NO eval/Function: the model must not
// be able to turn a "calculator" call into code execution.
// ---------------------------------------------------------------------------

const FUNCS = {
	sqrt: Math.sqrt, abs: Math.abs, sin: Math.sin, cos: Math.cos,
	tan: Math.tan, log: Math.log10, ln: Math.log, exp: Math.exp,
}
const CONSTS = { pi: Math.PI, e: Math.E }
const MAX_EXPR_LEN = 200

export function evaluateExpression(raw) {
	const src = String(raw ?? '').trim()
	if (!src) throw new Error('empty expression')
	if (src.length > MAX_EXPR_LEN) throw new Error('expression too long')

	let pos = 0
	const peek = () => src[pos]
	const skip = () => { while (pos < src.length && src[pos] === ' ') pos += 1 }

	function parseNumber() {
		const m = /^\d+(\.\d+)?/.exec(src.slice(pos))
		if (!m) throw new Error('unexpected character at ' + pos + ': "' + peek() + '"')
		pos += m[0].length
		return Number(m[0])
	}

	function primary() {
		skip()
		if (peek() === '(') {
			pos += 1
			const v = expr()
			skip()
			if (peek() !== ')') throw new Error('missing closing parenthesis')
			pos += 1
			return v
		}
		if (/\d/.test(peek() || '')) return parseNumber()
		if (/[a-zA-Z]/.test(peek() || '')) {
			const m = /^[a-zA-Z]+/.exec(src.slice(pos))
			const ident = m[0].toLowerCase()
			pos += m[0].length
			if (ident in CONSTS) return CONSTS[ident]
			if (ident in FUNCS) {
				skip()
				if (peek() !== '(') throw new Error('function ' + ident + ' needs parentheses')
				pos += 1
				const v = expr()
				skip()
				if (peek() !== ')') throw new Error('missing closing parenthesis')
				pos += 1
				return FUNCS[ident](v)
			}
			throw new Error('unknown identifier: ' + ident)
		}
		throw new Error('unexpected character at ' + pos + ': "' + (peek() ?? 'end') + '"')
	}

	function unary() {
		skip()
		if (peek() === '-') { pos += 1; return -unary() }
		if (peek() === '+') { pos += 1; return unary() }
		return power()
	}

	function power() {
		const base = primary()
		skip()
		if (peek() === '^') { pos += 1; return Math.pow(base, unary()) } // right-assoc
		return base
	}

	function term() {
		let v = unary()
		for (;;) {
			skip()
			const c = peek()
			if (c === '*') { pos += 1; v *= unary() }
			else if (c === '/') { pos += 1; const d = unary(); if (d === 0) throw new Error('division by zero'); v /= d }
			else if (c === '%') { pos += 1; const d = unary(); if (d === 0) throw new Error('division by zero'); v %= d }
			else return v
		}
	}

	function expr() {
		let v = term()
		for (;;) {
			skip()
			const c = peek()
			if (c === '+') { pos += 1; v += term() }
			else if (c === '-') { pos += 1; v -= term() }
			else return v
		}
	}

	const value = expr()
	skip()
	if (pos !== src.length) throw new Error('unexpected trailing input at ' + pos)
	if (!Number.isFinite(value)) throw new Error('result is not a finite number')
	return Number(value.toFixed(10))
}

// ---------------------------------------------------------------------------
// SSRF guard for fetch_text
// ---------------------------------------------------------------------------

export function isPrivateAddress(ip) {
	const v = net.isIP(ip)
	if (v === 4) {
		const [a, b] = ip.split('.').map(Number)
		if (a === 0 || a === 10 || a === 127) return true
		if (a === 169 && b === 254) return true
		if (a === 172 && b >= 16 && b <= 31) return true
		if (a === 192 && b === 168) return true
		if (a === 192 && b === 0) return true
		if (a === 100 && b >= 64 && b <= 127) return true // CGNAT
		if (a === 198 && (b === 18 || b === 19)) return true
		if (a >= 224) return true // multicast + reserved
		return false
	}
	if (v === 6) {
		const lower = ip.toLowerCase()
		if (lower === '::' || lower === '::1') return true
		if (lower.startsWith('fc') || lower.startsWith('fd')) return true // fc00::/7
		if (lower.startsWith('fe8') || lower.startsWith('fe9') || lower.startsWith('fea') || lower.startsWith('feb')) return true // fe80::/10
		if (lower.startsWith('ff')) return true // multicast
		const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(lower)
		if (mapped) return isPrivateAddress(mapped[1])
		return false
	}
	return true // not parseable -> treat as unsafe
}

function assertPublicHost(hostname) {
	const host = hostname.replace(/^\[|\]$/g, '')
	if (net.isIP(host)) {
		if (isPrivateAddress(host)) throw new Error('blocked: private/reserved address ' + host)
		return
	}
	if (!host.includes('.')) throw new Error('blocked: hostname without dot')
	throwIpIfPrivate(host)
}

async function throwIpIfPrivate(hostname) {
	let records
	try {
		records = await dns.promises.lookup(hostname, { all: true })
	} catch {
		throw new Error('DNS lookup failed for ' + hostname)
	}
	if (records.length === 0) throw new Error('no DNS records for ' + hostname)
	for (const { address } of records) {
		if (isPrivateAddress(address)) {
			throw new Error('blocked: ' + hostname + ' resolves to private/reserved address ' + address)
		}
	}
}

/** Validates protocol + host (direct IP or DNS). Returns nothing; throws when unsafe. */
export async function assertPublicUrl(urlString) {
	let url
	try {
		url = new URL(urlString)
	} catch {
		throw new Error('invalid URL')
	}
	if (url.protocol !== 'http:' && url.protocol !== 'https:') {
		throw new Error('only http/https URLs are allowed')
	}
	if (url.username || url.password) throw new Error('blocked: userinfo in URL')
	await assertPublicHost(url.hostname)
	return url
}

const FETCHABLE_TYPES = /^text\/|application\/(json|xml|javascript|yaml|toml)/i

async function fetchCapped(urlString) {
	const url = await assertPublicUrl(urlString)
	const controller = new AbortController()
	const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
	try {
		const response = await fetch(url, {
			redirect: 'manual',
			headers: { 'User-Agent': 'AURIX-Assistant/1.0 (+tool: fetch_text)' },
			signal: controller.signal,
		})
		if (response.status >= 300 && response.status < 400) return { redirect: response.headers.get('location') || '' }
		const type = response.headers.get('content-type') || 'text/plain'
		if (!FETCHABLE_TYPES.test(type)) {
			throw new Error('unsupported content-type: ' + type.split(';')[0])
		}
		if (!response.ok) throw new Error('HTTP ' + response.status)
		const decoder = new TextDecoder('utf-8', { fatal: false })
		let text = ''
		let bytes = 0
		for await (const chunk of response.body) {
			bytes += chunk.length
			if (bytes > FETCH_MAX_BYTES) {
				controller.abort()
				throw new Error('response exceeds ' + FETCH_MAX_BYTES + ' bytes')
			}
			text += decoder.decode(chunk, { stream: true })
		}
		return { status: response.status, contentType: type, text }
	} finally {
		clearTimeout(timer)
	}
}

async function fetchTextTool(url, depth = 0) {
	if (depth > 3) throw new Error('too many redirects')
	const outcome = await fetchCapped(url)
	if (outcome.redirect) {
		const next = new URL(outcome.redirect, url).toString()
		return fetchTextTool(next, depth + 1)
	}
	return outcome.text.length > RESULT_TEXT_CAP
		? outcome.text.slice(0, RESULT_TEXT_CAP) + '\n…[truncated]'
		: outcome.text
}

// ---------------------------------------------------------------------------
// Free, key-less knowledge tools
// ---------------------------------------------------------------------------

const WMO_CODES = {
	0: 'Clear sky', 1: 'Mainly clear', 2: 'Partly cloudy', 3: 'Overcast',
	45: 'Fog', 48: 'Depositing rime fog', 51: 'Light drizzle', 53: 'Moderate drizzle', 55: 'Dense drizzle',
	61: 'Slight rain', 63: 'Moderate rain', 65: 'Heavy rain', 66: 'Freezing rain', 67: 'Heavy freezing rain',
	71: 'Slight snowfall', 73: 'Moderate snowfall', 75: 'Heavy snowfall', 77: 'Snow grains',
	80: 'Slight rain showers', 81: 'Moderate rain showers', 82: 'Violent rain showers',
	85: 'Slight snow showers', 86: 'Heavy snow showers', 95: 'Thunderstorm',
	96: 'Thunderstorm with slight hail', 99: 'Thunderstorm with heavy hail',
}

async function geocode(location) {
	const url = 'https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=' +
		encodeURIComponent(location)
	const res = await withTimeout(fetch(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) }), FETCH_TIMEOUT_MS, 'geocoding')
	if (!res.ok) throw new Error('geocoding HTTP ' + res.status)
	const body = await res.json()
	const hit = body?.results?.[0]
	if (!hit) throw new Error('location not found: ' + location)
	return hit
}

async function weatherTool(args) {
	const hit = await geocode(args.location)
	const qs = new URLSearchParams({
		latitude: String(hit.latitude),
		longitude: String(hit.longitude),
		current: 'temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m',
		timezone: 'auto',
	})
	const res = await withTimeout(
		fetch('https://api.open-meteo.com/v1/forecast?' + qs, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) }),
		FETCH_TIMEOUT_MS, 'weather'
	)
	if (!res.ok) throw new Error('weather HTTP ' + res.status)
	const body = await res.json()
	const c = body?.current
	if (!c) throw new Error('weather data unavailable')
	const code = WMO_CODES[c.weather_code] || 'Unknown'
	return {
		text:
			`Weather in ${hit.name}${hit.country ? ', ' + hit.country : ''}: ` +
			`${c.temperature_2m}${body.current_units?.temperature_2m || '°C'}, ${code}, ` +
			`feels like ${c.apparent_temperature}°C, humidity ${c.relative_humidity_2m}%, ` +
			`wind ${c.wind_speed_10m}${body.current_units?.wind_speed_10m || ' km/h'}.`,
	}
}

async function wikipediaTool(args) {
	const title = encodeURIComponent(String(args.title || '').trim().replace(/\s+/g, '_'))
	const url = 'https://en.wikipedia.org/api/rest_v1/page/summary/' + title
	const res = await withTimeout(fetch(url, {
		headers: { 'User-Agent': 'AURIX-Assistant/1.0 (+tool: wikipedia)' },
		signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
	}), FETCH_TIMEOUT_MS, 'wikipedia')
	if (res.status === 404) throw new Error('no Wikipedia article titled "' + args.title + '"')
	if (!res.ok) throw new Error('wikipedia HTTP ' + res.status)
	const body = await res.json()
	const text = body?.extract || ''
	if (!text) throw new Error('wikipedia returned no summary')
	return { text: text.length > RESULT_TEXT_CAP ? text.slice(0, RESULT_TEXT_CAP) + '…' : text }
}

async function datetimeTool(args) {
	const tz = String(args.timezone || '').trim()
	let zone = 'UTC'
	if (tz) {
		try {
			new Intl.DateTimeFormat('en-GB', { timeZone: tz }) // throws if unknown
			zone = tz
		} catch {
			throw new Error('unknown timezone: ' + tz + ' (use IANA names like Asia/Kolkata)')
		}
	}
	const now = new Date()
	const text = new Intl.DateTimeFormat('en-GB', {
		timeZone: zone, dateStyle: 'full', timeStyle: 'long',
	}).format(now)
	return { text: text + ' (' + zone + ')' }
}

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

export const TOOLS = [
	{
		name: 'calculator',
		description: 'Evaluate an arithmetic expression. Supports + - * / % ^, parentheses, sqrt/abs/sin/cos/tan/log/ln/exp, pi, e.',
		args: { expression: { type: 'string', required: true } },
		execute: (args) => ({ text: String(evaluateExpression(args.expression)) }),
	},
	{
		name: 'datetime',
		description: 'Current date and time. Optional IANA timezone (e.g. Asia/Kolkata). Defaults to UTC.',
		args: { timezone: { type: 'string', required: false } },
		execute: (args) => datetimeTool(args),
	},
	{
		name: 'weather',
		description: 'Current weather for a city or place name (uses Open-Meteo, no API key needed).',
		args: { location: { type: 'string', required: true } },
		execute: (args) => weatherTool(args),
	},
	{
		name: 'wikipedia',
		description: 'Fetch the summary of an English Wikipedia article by title.',
		args: { title: { type: 'string', required: true } },
		execute: (args) => wikipediaTool(args),
	},
	{
		name: 'fetch_text',
		description: 'Fetch a public web page or JSON API as plain text (SSRF-guarded; private addresses blocked).',
		args: { url: { type: 'string', required: true } },
		execute: (args) => fetchTextTool(args.url),
	},
]

export function listTools() {
	return TOOLS.filter((t) => toolEnabled(t.name)).map((t) => ({
		name: t.name,
		description: t.description,
		args: t.args,
	}))
}

export function getTool(name) {
	const clean = String(name || '').trim().toLowerCase()
	return TOOLS.find((t) => t.name === clean) || null
}

function validateArgs(tool, args) {
	const input = args && typeof args === 'object' && !Array.isArray(args) ? args : {}
	for (const [key, spec] of Object.entries(tool.args)) {
		const value = input[key]
		if (spec.required && (value === undefined || value === null || String(value).trim() === '')) {
			throw new Error('missing required argument: ' + key)
		}
		if (value !== undefined && spec.type === 'string' && typeof value !== 'string') {
			throw new Error('argument "' + key + '" must be a string')
		}
		if (value !== undefined && spec.type === 'number' && typeof value !== 'number') {
			throw new Error('argument "' + key + '" must be a number')
		}
	}
	return input
}

/**
 * Runs one tool by name. NEVER throws — always resolves to a normalised
 * envelope so HTTP handlers and the chat loop can pass it straight through.
 */
export async function runTool(name, args) {
	const started = Date.now()
	const tool = getTool(name)
	if (!tool) {
		return { ok: false, tool: String(name || ''), ms: 0, error: 'unknown tool: ' + name }
	}
	if (!toolEnabled(tool.name)) {
		return { ok: false, tool: tool.name, ms: 0, error: 'tool is disabled on this server' }
	}
	try {
		const cleanArgs = validateArgs(tool, args)
		const outcome = await withTimeout(tool.execute(cleanArgs), TOOL_TIMEOUT_MS, tool.name)
		const text = typeof outcome === 'string' ? outcome : String(outcome?.text ?? '')
		return { ok: true, tool: tool.name, ms: Date.now() - started, text: text.slice(0, RESULT_TEXT_CAP) }
	} catch (err) {
		return { ok: false, tool: tool.name, ms: Date.now() - started, error: err?.message || 'tool failed' }
	}
}

// ---------------------------------------------------------------------------
// Chat integration helpers
// ---------------------------------------------------------------------------

const TOOL_NAMES = TOOLS.map((t) => t.name).join(', ')

/** System-prompt block teaching the model the one-shot tool protocol. */
export function toolPromptBlock() {
	if (!toolEnabled(TOOLS[0].name) && !TOOLS.some((t) => toolEnabled(t.name))) return ''
	return (
		'\n\nTOOL PROTOCOL: If a tool would clearly help (math, date/time, weather, ' +
		'encyclopedia lookup, fetching a public URL), reply with ONLY one JSON object, nothing else:\n' +
		'{"tool":"<name>","args":{...}}\n' +
		'Available tools: ' + TOOL_NAMES + '. For anything else, answer normally in plain text. Never wrap the JSON in code fences or prose.'
	)
}

/**
 * Detects a tool call in a model reply. Returns { tool, args } or null.
 * Accepts an optional ```json fence; rejects unknown tools and non-objects.
 */
export function extractToolCall(text) {
	if (typeof text !== 'string') return null
	let body = text.trim()
	const fence = /^```(?:json)?\s*([\s\S]*?)\s*```$/.exec(body)
	if (fence) body = fence[1].trim()
	if (!body.startsWith('{') || !body.endsWith('}')) return null
	let parsed
	try {
		parsed = JSON.parse(body)
	} catch {
		return null
	}
	if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
	const name = typeof parsed.tool === 'string' ? parsed.tool.trim().toLowerCase() : ''
	const tool = getTool(name)
	if (!tool) return null
	return { tool: tool.name, args: parsed.args && typeof parsed.args === 'object' ? parsed.args : {} }
}
