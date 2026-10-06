import test from 'node:test'
import assert from 'node:assert/strict'
import { toolsEnabledFromEnv } from '../src/config.js'

test('tool execution is enabled by default and for affirmative values', () => {
	for (const value of [undefined, '', 'on', 'true', '1', 'yes']) {
		assert.equal(toolsEnabledFromEnv(value), true, String(value))
	}
})

test('documented negative values disable tool execution', () => {
	for (const value of ['off', 'false', '0', 'no', ' OFF ']) {
		assert.equal(toolsEnabledFromEnv(value), false, value)
	}
})
