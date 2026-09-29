/**
 * Capability-routing unit tests (no network, no provider keys needed).
 *   node --test tests/capability-routing.test.js
 */

import test from 'node:test'
import assert from 'node:assert/strict'

const providers = await import('../src/providers.js')
const router = await import('../src/router.js')

test('every provider declares a capability list', () => {
	for (const p of providers.PROVIDERS) {
		assert.ok(Array.isArray(p.capabilities) && p.capabilities.length > 0, p.id)
		assert.ok(p.capabilities.includes('chat'), p.id + ' must serve chat')
	}
})

test('CAPABILITIES covers everything providers declare', () => {
	const declared = new Set(providers.PROVIDERS.flatMap((p) => p.capabilities))
	for (const cap of declared) {
		assert.ok(providers.CAPABILITIES.includes(cap), 'missing canonical: ' + cap)
	}
})

test('resolveModel mirrors the Android ModelRouting table', () => {
	assert.equal(providers.resolveModel('groq', 'coding', 'fallback'), 'qwen/qwen3.8-27b')
	assert.equal(providers.resolveModel('groq', 'chat', 'fallback'), 'openai/gpt-oss-120b')
	assert.equal(providers.resolveModel('openrouter', 'reasoning', 'fallback'), 'deepseek/deepseek-r1')
	assert.equal(providers.resolveModel('gemini', 'reasoning', 'fallback'), 'gemini-2.5-pro')
	// unknown capability -> provider default
	assert.equal(providers.resolveModel('groq', 'vision', 'fallback'), 'fallback')
})

test('capability filter: pool excludes providers without the capability', () => {
	const pool = router.candidates(undefined, 'coding')
	// cerebras, groq, mistral, openai, openrouter declare coding; all are unkeyed here
	const poolIds = pool.map((p) => p.id)
	for (const id of poolIds) {
		const p = providers.providerById(id)
		assert.ok(providers.capabilitiesFor(p).includes('coding'), id)
	}
})

test('capability filter: vision pool excludes non-vision providers', () => {
	const pool = router.candidates(undefined, 'vision')
	for (const p of pool) {
		assert.ok(providers.capabilitiesFor(p).includes('vision'), p.id)
	}
	// cerebras declares no vision and must never appear
	assert.equal(pool.some((p) => p.id === 'cerebras'), false)
})

test('unkeyed providers never enter the pool regardless of capability', () => {
	for (const cap of ['chat', 'reasoning', 'coding', 'vision']) {
		assert.equal(router.candidates(undefined, cap).length, 0, cap)
	}
})
