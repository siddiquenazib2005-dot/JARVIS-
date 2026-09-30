#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/.."
pkill -9 -f 'node src/index.js' >/dev/null 2>&1 || true
sleep 0.3
fail=0
node --check src/index.js || { echo FAIL_syntax; exit 1; }
echo PASS_syntax

wait_port() {
  local port=$1
  for _ in $(seq 1 20); do
    if curl -sf -m 1 "http://127.0.0.1:${port}/" >/dev/null 2>&1; then return 0; fi
    sleep 0.25
  done
  return 1
}

PORT=18081 node src/index.js >/tmp/s_closed.log 2>&1 & p1=$!
if ! wait_port 18081; then echo FAIL_closed_start; cat /tmp/s_closed.log; fail=1; else
  code=$(curl -sS -m 2 -o /tmp/body_closed.json -w '%{http_code}' http://127.0.0.1:18081/v1/health || echo 000)
  [ "$code" = "503" ] && echo PASS_closed_503 || { echo "FAIL_closed got $code"; fail=1; }
fi
kill -9 $p1 >/dev/null 2>&1 || true

PORT=18080 AURIX_ALLOW_ANON=true node src/index.js >/tmp/s_anon.log 2>&1 & p2=$!
if ! wait_port 18080; then echo FAIL_anon_start; cat /tmp/s_anon.log; fail=1; else
  code=$(curl -sS -m 2 -o /tmp/body_anon.json -w '%{http_code}' http://127.0.0.1:18080/v1/health || echo 000)
  banner=$(curl -sS -m 2 http://127.0.0.1:18080/)
  smoke_out=$(node scripts/smoke.js http://127.0.0.1:18080 2>&1); smoke_ec=$?
  [ "$code" = "200" ] && echo PASS_anon_200 || { echo "FAIL_anon got $code"; fail=1; }
  echo "BANNER=$banner"
  echo "$smoke_out"
  [ $smoke_ec -eq 0 ] && echo PASS_smoke || { echo FAIL_smoke; fail=1; }
fi
kill -9 $p2 >/dev/null 2>&1 || true

PORT=18082 AURIX_APP_TOKEN=tok node src/index.js >/tmp/s_tok.log 2>&1 & p3=$!
if ! wait_port 18082; then echo FAIL_tok_start; cat /tmp/s_tok.log; fail=1; else
  c1=$(curl -sS -m 2 -o /tmp/t1.json -w '%{http_code}' http://127.0.0.1:18082/v1/health || echo 000)
  c2=$(curl -sS -m 2 -o /tmp/t2.json -w '%{http_code}' -H 'Authorization: Bearer tok' http://127.0.0.1:18082/v1/health || echo 000)
  [ "$c1" = "401" ] && echo PASS_token_401 || { echo "FAIL_token_401 got $c1"; fail=1; }
  [ "$c2" = "200" ] && echo PASS_token_200 || { echo "FAIL_token_200 got $c2"; fail=1; }
fi
kill -9 $p3 >/dev/null 2>&1 || true

echo "RESULT fail=$fail"
exit $fail
