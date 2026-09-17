/**
 * File-backed conversation memory.
 *
 * Deliberately dependency-free (plain JSON on disk) so the backend runs on any
 * free host without provisioning a database. The API surface matches what the
 * Android client needs: append a turn, read recent turns, wipe a session, and
 * store durable facts the assistant should always remember.
 */

import fs from 'node:fs'
import path from 'node:path'

const DATA_DIR = process.env.AURIX_DATA_DIR || path.join(process.cwd(), 'data')
const SESSIONS_FILE = path.join(DATA_DIR, 'sessions.json')
const FACTS_FILE = path.join(DATA_DIR, 'facts.json')
const MAX_TURNS_PER_SESSION = Number(process.env.AURIX_MAX_TURNS || 200)
const MAX_FACTS = Number(process.env.AURIX_MAX_FACTS || 100)
const MAX_FACT_CHARS = 500

function ensureDir() {
	if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true })
}

function readJson(file, fallback) {
	try {
		if (!fs.existsSync(file)) return fallback
		return JSON.parse(fs.readFileSync(file, 'utf8')) || fallback
	} catch {
		return fallback
	}
}

function writeJson(file, value) {
	ensureDir()
	const tmp = file + '.tmp'
	fs.writeFileSync(tmp, JSON.stringify(value, null, 2), 'utf8')
	fs.renameSync(tmp, file)
}

/** Appends one turn and returns the trimmed session history. */
export function appendTurn(sessionId, role, content) {
	if (!sessionId) return []
	const store = readJson(SESSIONS_FILE, {})
	const turns = store[sessionId] || []
	const safeRole = role === 'assistant' ? 'assistant' : 'user'
	const safeContent = String(content || '').slice(0, 12000)
	turns.push({ role: safeRole, content: safeContent, at: new Date().toISOString() })
	store[sessionId] = turns.slice(-MAX_TURNS_PER_SESSION)
	writeJson(SESSIONS_FILE, store)
	return store[sessionId]
}

/** Most recent turns for a session, oldest first. */
export function recentTurns(sessionId, limit = 20) {
	if (!sessionId) return []
	const store = readJson(SESSIONS_FILE, {})
	return (store[sessionId] || []).slice(-limit)
}

export function listSessions() {
	const store = readJson(SESSIONS_FILE, {})
	return Object.entries(store).map(([id, turns]) => ({
		id,
		turns: turns.length,
		updatedAt: turns[turns.length - 1]?.at || null,
		preview: turns.find((t) => t.role === 'user')?.content?.slice(0, 60) || '',
	}))
}

export function clearSession(sessionId) {
	const store = readJson(SESSIONS_FILE, {})
	delete store[sessionId]
	writeJson(SESSIONS_FILE, store)
	return true
}

/** Durable facts injected into every system prompt. */
export function facts() {
	return readJson(FACTS_FILE, [])
}

export function rememberFact(text) {
	const clean = String(text || '').trim()
	if (!clean) return facts()
	const all = facts()
	if (!all.some((f) => f.text === clean)) {
		all.push({ text: clean.slice(0, MAX_FACT_CHARS), at: new Date().toISOString() })
	}
	writeJson(FACTS_FILE, all.slice(-MAX_FACTS))
	return all
}

export function forgetFact(text) {
	const all = facts().filter((f) => f.text !== text)
	writeJson(FACTS_FILE, all)
	return all
}

/** Builds the memory block appended to the system prompt. */
export function memoryPreamble() {
	const all = facts()
	if (all.length === 0) return ''
	return '\n\nKnown facts about the user:\n' + all.map((f) => '- ' + f.text).join('\n')
}

export function memoryStats() {
	const sessions = readJson(SESSIONS_FILE, {})
	const allFacts = facts()
	return {
		sessions: Object.keys(sessions).length,
		turns: Object.values(sessions).reduce((sum, turns) => sum + turns.length, 0),
		facts: allFacts.length,
		dataDir: DATA_DIR,
	}
}
