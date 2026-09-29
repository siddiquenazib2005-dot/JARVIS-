/**
 * Unit tests for the AURIX backend tool system.
 *   node --test tests/tools.test.js
 * Pure logic only — no network, no provider keys needed.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

process.env.AURIX_TOOLS = 'on'
const { evaluateExpression, extractToolCall, isPrivateAddress, runTool } = await import('../src/tools.js')

// ---------------------------------------------------------------------------
// calculator parser
// ---------------------------------------------------------------------------

test('calculator: basic arithmetic and precedence', () => {
	assert.equal(evaluateExpression('2+3*4'), 14)
	assert.equal(evaluateExpression('(2+3)*4'), 20)
	assert.equal(evaluateExpression('10/4'), 2.5)
	assert.equal(evaluateExpression('2^3^2'), 512) // right-assoc
	assert.equal(evaluateExpression('-5+3'), -2)
	assert.equal(evaluateExpression('10 % 3'), 1)
})

test('calculator: functions and constants', () => {
	assert.equal(evaluateExpression('sqrt(16)'), 4)
	assert.equal(evaluateExpression('abs(-7)'), 7)
	assert.ok(Math.abs(evaluateExpression('pi') - Math.PI) < 1e-10)
	assert.ok(Math.abs(evaluateExpression('2*e') - 2 * Math.E) < 1e-10)
})

test('calculator: rejects injection attempts', () => {
	assert.throws(() => evaluateExpression('process.exit(1)'))
	assert.throws(() => evaluateExpression('constructor'))
	assert.throws(() => evaluateExpression('2; 3'))
	assert.throws(() => evaluateExpression('eval(1)'))
})

test('calculator: rejects malformed and unsafe inputs', () => {
	assert.throws(() => evaluateExpression(''), /empty/)
	assert.throws(() => evaluateExpression('1/0'), /division by zero/)
	assert.throws(() => evaluateExpression('(2+3'), /parenthesis/)
	assert.throws(() => evaluateExpression('2+'), /unexpected/)
	assert.throws(() => evaluateExpression('x'.repeat(300)), /too long/)
})

// ---------------------------------------------------------------------------
// SSRF guard
// ---------------------------------------------------------------------------

test('isPrivateAddress: ipv4 private ranges', () => {
	for (const ip of ['127.0.0.1', '10.1.2.3', '192.168.1.1', '172.16.0.1', '172.31.255.255', '169.254.169.254', '0.0.0.0', '100.64.0.1', '224.0.0.1']) {
		assert.equal(isPrivateAddress(ip), true, ip)
	}
	for (const ip of ['8.8.8.8', '1.1.1.1', '172.32.0.1', '100.128.0.1', '198.51.100.1']) {
		assert.equal(isPrivateAddress(ip), false, ip)
	}
})

test('isPrivateAddress: ipv6 private ranges', () => {
	for (const ip of ['::1', '::', 'fc00::1', 'fd12:3456::1', 'fe80::1', 'ff02::1']) {
		assert.equal(isPrivateAddress(ip), true, ip)
	}
	assert.equal(isPrivateAddress('2606:4700::1'), false)
	// IPv4-mapped must be judged by the embedded v4 address
	assert.equal(isPrivateAddress('::ffff:127.0.0.1'), true)
	assert.equal(isPrivateAddress('::ffff:8.8.8.8'), false)
})

// ---------------------------------------------------------------------------
// tool-call extraction (the chat protocol)
// ---------------------------------------------------------------------------

test('extractToolCall: accepts clean json and fenced json', () => {
	assert.deepEqual(
		extractToolCall('{"tool":"calculator","args":{"expression":"1+1"}}'),
		{ tool: 'calculator', args: { expression: '1+1' } }
	)
	assert.deepEqual(
		extractToolCall('```json\n{"tool":"weather","args":{"location":"Delhi"}}\n```'),
		{ tool: 'weather', args: { location: 'Delhi' } }
	)
})

test('extractToolCall: rejects prose, unknown tools, non-objects', () => {
	assert.equal(extractToolCall('Here is your answer: 42'), null)
	assert.equal(extractToolCall('{"tool":"rm_rf","args":{}}'), null)
	// args is optional and defaults to {}
	assert.deepEqual(extractToolCall('{"tool":"calculator"}'), { tool: 'calculator', args: {} })
	assert.equal(extractToolCall('[1,2,3]'), null)
	assert.equal(extractToolCall('{"tool":123,"args":{}}'), null)
	assert.equal(extractToolCall('not json at all'), null)
})

// ---------------------------------------------------------------------------
// runTool envelope (network-free tools only)
// ---------------------------------------------------------------------------

test('runTool: unknown tool and missing args return error envelope, never throw', async () => {
	const unknown = await runTool('no_such_tool', {})
	assert.equal(unknown.ok, false)
	assert.match(unknown.error, /unknown tool/)

	const missing = await runTool('weather', {})
	assert.equal(missing.ok, false)
	assert.match(missing.error, /missing required argument: location/)
})

test('runTool: calculator end-to-end', async () => {
	const out = await runTool('calculator', { expression: '(2+3)*4' })
	assert.equal(out.ok, true)
	assert.equal(out.text, '20')
	assert.equal(typeof out.ms, 'number')
})

test('runTool: datetime works without network', async () => {
	const out = await runTool('datetime', { timezone: 'Asia/Kolkata' })
	assert.equal(out.ok, true)
	assert.match(out.text, /Asia\/Kolkata/)

	const bad = await runTool('datetime', { timezone: 'Not/AZone' })
	assert.equal(bad.ok, false)
	assert.match(bad.error, /unknown timezone/)

	const utc = await runTool('datetime', {})
	assert.equal(utc.ok, true)
	assert.match(utc.text, /UTC/)
})
