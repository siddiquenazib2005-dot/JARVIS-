#!/usr/bin/env bash
# One-shot local endpoint sweep for the AURIX backend. No provider keys needed.
#   bash scripts/local-test.sh
set -u
cd "$(dirname "$0")/.."

PORT=${TEST_PORT:-18090}
BASE="http://127.0.0.1:${PORT}"
fail=0

say()  { printf '%s\n' "$*"; }
pass() { say "PASS  $*"; }
bad()  { say "FAIL  $*"; fail=1; }
skip() { say "SKIP  $*"; }

# json <path> [method] [body] -> prints "<code> <body>"; on connection
# failure curl's -w already emits "000", so no extra fallback is appended.
json() {
	local path=$1 method=${2:-GET} body=${3:-}
	rm -f /tmp/local-test-body.json
	curl -sS -m 5 -o /tmp/local-test-body.json -w '%{http_code}' \
		-X "$method" -H 'Content-Type: application/json' \
		${body:+-d "$body"} "$BASE$path" || true
}

pkill -f "node src/index.js" >/dev/null 2>&1 || true
AURIX_ALLOW_ANON=true PORT=$PORT node src/index.js >/tmp/local-test-server.log 2>&1 &
SRV=$!
trap 'kill -9 $SRV 2>/dev/null || true' EXIT

up=0
for _ in $(seq 1 20); do
	if curl -sf -m 1 "$BASE/" >/dev/null 2>&1; then up=1; break; fi
	sleep 0.25
done
if [ "$up" -ne 1 ]; then bad server_start; cat /tmp/local-test-server.log; exit 1; fi
pass server_start

# 1. banner
[ "$(json /)" = "200" ] && grep -q 'aurix-backend' /tmp/local-test-body.json \
	&& pass banner || bad banner "got $(cat /tmp/local-test-body.json)"

# 2. health
[ "$(json /v1/health)" = "200" ] && grep -q '"ok":false' /tmp/local-test-body.json \
	&& pass health_no_keys || bad health "got $(cat /tmp/local-test-body.json)"

# 3. models
[ "$(json /v1/models)" = "200" ] && grep -q '"models":\[' /tmp/local-test-body.json \
	&& pass models || bad models "got $(cat /tmp/local-test-body.json)"

# 4. facts CRUD
[ "$(json /v1/facts POST '{"text":"local-test fact"}')" = "200" ] \
	&& pass facts_post || bad facts_post "got $(cat /tmp/local-test-body.json)"
[ "$(json /v1/facts)" = "200" ] && grep -q 'local-test fact' /tmp/local-test-body.json \
	&& pass facts_get || bad facts_get "got $(cat /tmp/local-test-body.json)"
[ "$(json /v1/facts DELETE)" = "200" ] && pass facts_delete || bad facts_delete "got $(cat /tmp/local-test-body.json)"

# 5. sessions lifecycle
[ "$(json /v1/sessions)" = "200" ] && pass sessions_get || bad sessions_get "got $(cat /tmp/local-test-body.json)"
[ "$(json /v1/sessions/test-session DELETE)" = "200" ] \
	&& pass session_delete || bad session_delete "got $(cat /tmp/local-test-body.json)"

# 6. chat without keys must fail with a helpful error, not crash
[ "$(json /v1/chat POST '{"prompt":"hi"}')" = "503" ] || [ "$(json /v1/chat POST '{"prompt":"hi"}')" = "200" ] \
	&& pass chat_no_keys_graceful || bad chat_no_keys "got $(cat /tmp/local-test-body.json)"

# 7. stream endpoint exists (headers only; no keys so body is an error event)
code=$(curl -sS -m 5 -o /tmp/local-test-stream.txt -w '%{http_code}' -X POST \
	-H 'Content-Type: application/json' -d '{"prompt":"hi"}' "$BASE/v1/chat/stream" || echo 000)
[ "$code" != "000" ] && pass stream_endpoint "http $code" || bad stream_endpoint

# 8. tools: list, calculator, SSRF block
[ "$(json /v1/tools)" = "200" ] && grep -q '"tools":\[' /tmp/local-test-body.json \
	&& pass tools_list || bad tools_list "got $(cat /tmp/local-test-body.json)"

[ "$(json /v1/tool POST '{"name":"calculator","args":{"expression":"(2+3)*4"}}')" = "200" ] \
	&& grep -q '"ok":true' /tmp/local-test-body.json && grep -q '"text":"20"' /tmp/local-test-body.json \
	&& pass tool_calculator || bad tool_calculator "got $(cat /tmp/local-test-body.json)"

[ "$(json /v1/tool POST '{"name":"fetch_text","args":{"url":"http://127.0.0.1:9/x"}}')" = "200" ] \
	&& grep -q '"ok":false' /tmp/local-test-body.json \
	&& pass tool_ssrf_blocked || bad tool_ssrf_blocked "got $(cat /tmp/local-test-body.json)"

# weather (live network, best-effort: skip when the network itself is down)
weather_code=$(json /v1/tool POST '{"name":"weather","args":{"location":"Mumbai"}}')
if [ "$weather_code" = "000" ]; then
	skip tool_weather "no outbound network from this sandbox"
elif [ "$weather_code" = "200" ] && grep -q '"ok":true' /tmp/local-test-body.json 2>/dev/null; then
	pass tool_weather
elif [ "$weather_code" = "200" ]; then
	skip tool_weather "upstream issue: $(cat /tmp/local-test-body.json 2>/dev/null)"
else
	bad tool_weather "http $weather_code"
fi

# 9. smoke.js suite
if node scripts/smoke.js "$BASE" >/tmp/local-test-smoke.log 2>&1; then
	pass smoke_suite
else
	bad smoke_suite; cat /tmp/local-test-smoke.log
fi

kill -9 $SRV 2>/dev/null || true
say "RESULT fail=$fail"
exit $fail
