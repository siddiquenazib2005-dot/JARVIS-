# AURIX — Phase 2: real backend

A real, deployable server now lives in `server/`. It is plain Node 18+ with two
dependencies (`express`, `cors`) and no database, so it runs on any free host.

## What the backend does

| Capability | Detail |
| --- | --- |
| Multi-provider routing | Groq, Cerebras, OpenRouter, Mistral, Gemini, OpenAI |
| Scoring | 45% success rate + 30% speed + 25% priority (same as the app) |
| Failover | 401/403 disables a provider, 429/503 cools it down 60s, next one is tried |
| Key pooling | `GROQ_API_KEY_2..._5` or comma-separated values rotate for more free quota |
| Streaming | Server-Sent Events in OpenAI wire format |
| Memory | Per-session turn history + durable "facts" injected into every prompt |
| Security | Keys never leave the server; optional `AURIX_APP_TOKEN` gates all `/v1` routes |

## Endpoints

`GET /` · `GET /v1/health` · `GET /v1/models` · `POST /v1/probe` ·
`POST /v1/chat` · `POST /v1/chat/stream` · `GET|DELETE /v1/sessions[/:id]` ·
`GET|POST|DELETE /v1/facts`

## Deploy in 5 minutes (free)

1. Push this repo to GitHub.
2. Render.com → New → Blueprint → select the repo (`render.yaml` is included).
3. Add environment variables: at least one of `GROQ_API_KEY`,
   `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, ... plus an optional
   `AURIX_APP_TOKEN`.
4. Wait for the deploy, then open `https://<service>.onrender.com/v1/health`.

Docker works too: `docker build -t aurix-backend ./server`.

## Connect the app

AURIX → drawer → **API keys & routing** → *Backend URL* → paste the service URL
(and the app token if set) → **Save & test backend**. A confirmation line shows
which providers the server can reach.

### Routing precedence in the app

1. **Offline command layer** — device actions always run on-device, instantly.
2. **Backend** — used when a backend URL is configured.
3. **On-device provider routing** — fallback using keys saved in the app.

Clearing the URL returns the app to on-device routing. The model picker still
applies: a pinned model is forwarded to the backend as `provider` + `model`.

## Verify

```bash
cd server && npm install && npm start          # terminal 1
npm run health                                  # terminal 2
```

`scripts/smoke.js` checks the banner, health, models and a real chat
round-trip, and exits non-zero on failure.

## Next (Phase 3)

- Server-side tool execution and mission runner
- Vector recall over stored turns
- Screen automation UI, notification/OTP reader, PC Connect pairing
