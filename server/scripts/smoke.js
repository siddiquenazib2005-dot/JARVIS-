/**
 * Smoke test for a running AURIX backend.
 *
 *   node scripts/smoke.js http://localhost:8080 [app-token]
 *
 * Checks the banner, health, models, and (when a provider key is configured)
 * a real chat round-trip. Exits non-zero on failure so CI can gate on it.
 */

const base = (process.argv[2] || 'http://localhost:8080').replace(/\/$/, '')
const token = process.argv[3] || process.env.AURIX_APP_TOKEN || ''

const headers = { 'Content-Type': 'application/json' }
if (token) headers.Authorization = 'Bearer ' + token

let failures = 0

function report(name, ok, detail) {
	console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (detail ? ' - ' + detail : ''))
	if (!ok) failures += 1
}

async function getJson(path) {
	const response = await fetch(base + path, { headers })
	const body = await response.json()
	return { status: response.status, body }
}

async function main() {
	try {
		const banner = await getJson('/')
		report('banner', banner.status === 200 && banner.body.service === 'aurix-backend')

		const health = await getJson('/v1/health')
		report('health', health.status === 200, 'providers: ' + health.body.routeOrder?.join(', '))

		const models = await getJson('/v1/models')
		report('models', models.status === 200 && Array.isArray(models.body.models))

		const configured = (health.body.providers || []).filter((p) => p.keys > 0)
		if (configured.length === 0) {
			console.log('SKIP  chat - no provider key configured on the server')
		} else {
			const response = await fetch(base + '/v1/chat', {
				method: 'POST',
				headers,
				body: JSON.stringify({ prompt: 'Reply with the single word: pong' }),
			})
			const body = await response.json()
			report(
				'chat',
				response.status === 200 && typeof body.text === 'string' && body.text.length > 0,
				body.provider ? body.provider + ' / ' + body.model : body.error?.message
			)
		}
	} catch (err) {
		report('request', false, err.message)
	}

	console.log(failures === 0 ? '\nAll checks passed.' : '\n' + failures + ' check(s) failed.')
	process.exit(failures === 0 ? 0 : 1)
}

main()
