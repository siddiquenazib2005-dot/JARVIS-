# AURIX AI Companion

Original red/black futuristic Android AI companion, rebranded from the existing JARVIS codebase.

GitHub Actions workflow included: **Build AURIX Debug APK** creates a downloadable debug APK artifact on every push.

Your red/black futuristic personal AI companion for Android — a conversational companion that can control your device, read your screen, run tasks, and remember what you tell it, powered by multi-provider LLM routing running entirely on-device.

## ✨ Features

- **Chat with AURIX** — Compose-based chat UI with streaming replies.
- **Multi-provider LLM routing** — health-aware fallback across OpenAI, OpenRouter, Groq, Gemini and more; backend-only credentials, never exposed to the app.
- **Device automation** — via an Android AccessibilityService: open/close apps, tap, type, swipe, scroll, long-press, press Back/Home/Recents, lock the screen.
- **Screen awareness** — visual (OCR via ML Kit + screenshot capture) and semantic (accessibility node tree reading).
- **Task planning** — multi-step agent loop with bounded retries, working memory, and an action-JSON contract for the LLM.
- **Long-term memory** — vector-based store (local embedding + Qdrant/Pinecone backends) with recall context injection.
- **Security** — permission gate with confirmation/audit levels, secret redaction, privacy-aware logging.

## 🏗️ Architecture

```
AURIX
│
├── app/
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/                     # Compose-themed resources, a11y service config
│       │   └── java/com/jarvis/ai/
│       │       ├── MainActivity.kt      # Compose entry point → ChatScreen
│       │       ├── accessibility/       # AccessibilityService + UI automation engines
│       │       │   ├── JarvisAccessibilityService.kt
│       │       │   ├── A11yResult.kt    # Structured outcomes + error codes
│       │       │   ├── NodeResolver.kt  # Ranked UI-node resolution (text/desc/id/fuzzy)
│       │       │   ├── ScreenReader.kt  # Text extraction + structured snapshots
│       │       │   ├── GestureEngine.kt, TextInputEngine.kt, ScrollEngine.kt
│       │       │   ├── WaitEngine.kt, TextMatcher.kt, NodeValidator.kt
│       │       │   ├── RiskClassifier.kt, AccessibilityLogger.kt
│       │       ├── agent/               # AgentCore: LLM→action-JSON→execution loop
│       │       ├── bridge/              # SocketServer (external integrations)
│       │       ├── core/                # EventBus + event types
│       │       ├── data/                # Repository, models, SSE parsing, offline engine
│       │       ├── health/              # Health check subsystems
│       │       ├── intelligence/        # TaskRouter: request → reasoning plan
│       │       ├── memory/              # Vector memory (local + Qdrant + Pinecone)
│       │       ├── orchestrator/        # MasterOrchestrator + ToolExecutor + ToolRegistry
│       │       │   ├── MasterOrchestrator.kt   # Single pipeline: UI → intelligence → tools
│       │       │   ├── IntentClassifier.kt     # Intent routing (call/chat/automation/...)
│       │       │   ├── ToolExecutor.kt         # Gated tool execution + verification
│       │       │   ├── PackageResolver.kt      # App-name → package (defensive label loading)
│       │       │   └── AIProvider.kt / VisionAnalyzer.kt
│       │       ├── planning/            # AgentLoop + agent limits
│       │       ├── presence/            # Presence-sensing features
│       │       ├── proactive/           # Proactive assistant behaviors
│       │       ├── provider/            # ProviderRouter, routing, key pools, health
│       │       │   ├── ProviderRouter.kt / ProviderManager.kt / ProviderHealthManager.kt
│       │       │   └── adapters/        # Per-provider adapters (OpenAI, Groq, Gemini, ...)
│       │       ├── security/            # PermissionGate + AuditLog + secret redaction
│       │       ├── service/             # Foreground/background services
│       │       ├── system/              # SystemAwareness (device state)
│       │       ├── ui/                  # Compose screens, components, theme
│       │       ├── viewmodel/           # ChatViewModel
│       │       └── vision/              # Screenshot capture + OCR (ML Kit)
│       └── test/                        # JVM unit tests (incl. framework-agnostic a11y tests)
│
├── gradle/libs.versions.toml            # Version catalog
├── build.gradle.kts / settings.gradle.kts
└── tools/                               # Device-testing helper scripts
```

### Data flow

```
User input → IntentClassifier → MasterOrchestrator
              ├─ CALCULATION/TIME_DATE → OfflineJarvisEngine (local, no AI)
              ├─ MEMORY → VectorMemory (store/recall)
              ├─ SYSTEM_COMMAND → ToolExecutor (gated tools: open app/url/settings, battery, device)
              ├─ DEVICE_AUTOMATION → AgentCore → TaskRouter → LLM → action JSON
              │                    → JarvisAccessibilityService executes actions (tap/type/swipe/...)
              ├─ VISION_ANALYSIS → Screenshot → ML Kit OCR → reply
              └─ CHAT → memory context inject → ProviderRouter → streaming reply
All model traffic goes through ProviderRouter only — never direct client→backend calls.
```

## 🚀 Building

Requirements: JDK 17, Android SDK (compileSdk 35, minSdk 26), Android Gradle Plugin 8.7.3.

```bash
# Debug APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Unit tests
./gradlew :app:testDebugUnitTest
```

## 🔒 Security notes

- `local.properties`, `keystore/`, and `secrets.properties` are git-ignored — signing keys and provider credentials are never committed.
- LLM/backend credentials are stored backend-only and never exposed to the app (see `provider/`).
- A11y actions are gated by `PermissionGate` with confirm/high-risk audit logging, and logging redacts OTPs, PINs, passwords and tokens.
- `QUERY_ALL_PACKAGES` is declared for app-label resolution; package lookup uses defensive per-app label loading.

## 🧪 Testing

- JVM unit tests: `com.jarvis.ai.accessibility.*`, `com.jarvis.ai.orchestrator.*`, provider/memory/security/planning suites.
- `OpenWhatsappVerifyTest` covers the full utterance→classifier→resolver chain (incl. broken-label regression).
- Robolectric ARM64 limitation: `NodeResolverRobolectricTest` requires a non-ARM64 host to run.

## 🛠️ Tooling

`tools/device-test.sh` — ADB-based on-device verification harness.