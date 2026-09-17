# AURIX Backend

Real, working backend for the AURIX Android assistant. Zero-database, plain
Node 18+ (only `express` and `cors`), so it runs on any free host.

## What it does

- **Multi-provider routing** across Groq, Cerebras, OpenRouter, Mistral,
  Gemini and OpenAI, using the same scoring as the app
  (45% success rate + 30% speed + 25% priority).
- **Automatic failover and key pooling.** `401/403` disables a provider,
  `429/503` puts it in a 60s cooldown, and the next candidate is tried. Extra
  keys (`GROQ_API_KEY_2`, or comma-separated values) rotate for more free quota.
- **Streaming** over Server-Sent Events, identical wire format to OpenAI
  (`data: {"choices":[{"delta":{"content":"..."}}]}` then `data: [DONE]`).
- **Server-side memory**: per-session turn history plus durable "facts" that are
  injected into every system prompt.
- **Keys stay on the server.** The app only needs the backend URL and an
  optional app token.

## Run locally

```bash
cd server
npm install
cp env.example.txt .env        # then fill in at least one API key
export $(grep -v '^#' .env | xargs)
npm start
```

Verify:

```bash
npm run health                  # or: node scripts/smoke.js http://localhost:8080
curl localhost:8080/v1/health
curl -X POST localhost:8080/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"hello"}'
```

## Deploy free

**Render** (easiest): push the repo, then New → Blueprint → pick this repo.
`render.yaml` is already configured; add your API keys as environment variables.

**Docker** (Fly.io, Railway, Koyeb, a VPS):

```bash
docker build -t aurix-backend ./server
docker run -p 8080:8080 -e GROQ_API_KEY=xxx aurix-backend
```

## Connect the app

In AURIX: drawer → **API keys & routing** → *Backend URL* → paste
`https://your-service.onrender.com` (plus the app token if you set one) → Save.

When a backend URL is set, chat runs through the backend and inherits its
routing, memory and quota pooling. Clear the field to go back to on-device
routing with the keys stored in the app. Device commands always run on-device
either way.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Service banner, configured providers |
| GET | `/v1/health` | Provider health, route order, uptime |
| GET | `/v1/models` | Catalogue with configured flag |
| POST | `/v1/probe` | Live probe: `{"provider":"groq"}` |
| POST | `/v1/chat` | `{prompt}` or `{messages}`, optional `sessionId`, `model`, `provider` |
| POST | `/v1/chat/stream` | Same body, SSE response |
| GET | `/v1/sessions` | Stored sessions |
| GET | `/v1/sessions/:id` | Recent turns |
| DELETE | `/v1/sessions/:id` | Wipe a session |
| GET/POST/DELETE | `/v1/facts` | Durable memory facts |

Set `AURIX_APP_TOKEN` to require `Authorization: Bearer <token>` on all `/v1`
routes.
