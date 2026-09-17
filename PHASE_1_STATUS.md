# AURIX — Phase 1 status

**Version:** 1.3 (versionCode 4) · applicationId `com.aurix.ai`

## Phase 1 — 100% complete

| Item | Status |
| --- | --- |
| AURIX branding (name, red/black theme, icons) | ✅ |
| ChatGPT-style chat interface | ✅ |
| Sessions drawer (new / switch / delete chats) | ✅ |
| Model picker (Auto + 13 curated models) | ✅ |
| Multi-API routing with automatic failover | ✅ |
| API key manager (6 providers, save + live test) | ✅ |
| Offline device-command layer (works with zero keys) | ✅ |
| MYRA feature surface at basic level | ✅ |
| Voice input (mic, hands-free, wake word) | ✅ |
| Markdown replies, streaming, stop generation | ✅ |
| Suggestion prompts wired to real commands | ✅ |
| Build pipeline (GitHub Actions debug APK) | ✅ |
| Dead code / disabled tests removed | ✅ |

## Interface

Slim top bar (chats · model chip · new chat), full-width assistant turns with
avatar, right-aligned user bubbles, rounded pill composer with mic + send,
empty state with 8 tappable prompts.

## Multi-API routing

1. `RoutingPrefs` stores `Auto` or a pinned provider + model (persisted).
2. `ProviderManager.selectPrimary()` honours the pin when a key exists,
   otherwise scores providers: 45% success rate + 30% speed + 25% priority.
3. `ModelRouting.resolveModel()` returns the pinned model for its provider.
4. Dead / rate-limited / auth-failed providers are skipped and retried later,
   so any number of free keys can be mixed.

Supported keys: Groq, Gemini, OpenRouter, Cerebras, Mistral, OpenAI.

## Offline command layer

`QuickCommandRouter` + `DeviceActionPack` run before any network call, so
device work never fails with "no AI provider is reachable". English and
Hinglish phrasings are both matched.

Covered basics: flashlight, battery, device report, volume/mute, media
transport, alarms, timers, camera, gallery, files, share, maps navigation and
nearby search, calls, dialler, call log, contact lookup, SOS, WhatsApp, SMS,
email, web/YouTube search, open URL, open/list/uninstall apps, 17 settings
shortcuts, app info, PC-bridge info.

## Next: Phase 2 — real backend

- Persistent conversation memory service + embeddings
- Server-side tool execution and streaming relay
- Accessibility-driven screen automation UI
- Missions (multi-step autonomous tasks)
- Notification/OTP reader, PC Connect pairing UI
