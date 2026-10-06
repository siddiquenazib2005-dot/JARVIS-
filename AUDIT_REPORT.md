# AURIX Repository Audit

**Audit basis:** current `main` checkout at `6d8617e` (2026-10-07). Findings below are based on source inspection; build and test results are reported separately after verification. This is a source audit, not an on-device Android 16 certification or penetration test.

## 1. Executive Summary

AURIX is a feature-rich Android assistant with a Kotlin/Compose client, an explicit runtime composition root, provider routing and failover, a gated tool orchestrator, voice services, and an optional Node backend. The repository has meaningful unit-test coverage and already contains several sound reliability controls: encrypted provider-key storage, bounded agent loops, an accessibility permission gate, local fallbacks, and wake-word engine abstraction.

Two verified issues are selected for immediate repair: the backend tool-disable environment setting is inverted, and the mobile backend bearer token is stored in plaintext SharedPreferences. The app also carries broad sensitive permissions and has multiple overlapping representations of assistant activity. Those deserve deliberate follow-up; they are not being “fixed” by a broad rewrite in this pass.

## 2. Current Architecture

- **Android build:** single `:app` Android application module. Namespace `com.jarvis.ai`; application ID `com.aurix.ai.debug`; `minSdk 26`, `targetSdk/compileSdk 35`, Java/Kotlin target 17, Android Gradle Plugin 8.7.3, Kotlin 2.0.21. `debug` and `release` build types exist; no product flavors are configured. Release minification is off. Debug signing references root `debug.keystore` with Android's standard debug credentials.
- **Composition/lifecycle:** `AurixApp` installs crash handling and manages the floating companion; `MainActivity` hosts Compose and handles app destinations with saveable string state; `JarvisRuntime` is the process composition root and constructs provider, memory, and orchestration dependencies.
- **UI/state:** Jetpack Compose + Material 3. `JarvisViewModel` exposes `StateFlow<UiState>` and streams data to screens. The UI uses a manually switched destination in `MainActivity`, not Navigation Compose. `UiState` separately carries loading/listening/speaking/acting booleans; additional voice state lives in services and runtime gates.
- **Data/storage:** SQLite through `SQLiteOpenHelper` for sessions/messages; SharedPreferences for ordinary settings; Android Keystore AES-GCM wrapper (`SecureStore`) for provider secrets; `SecureKvStore` for local vector memory; optional Qdrant/Pinecone stores and embeddings. Backend keeps in-memory session turns/facts (see `server/src/memory.js`).
- **Networking/AI:** OkHttp streaming clients; SSE parser; on-device `ProviderRouter`/`ProviderManager` adapters, health/cooldown, key pools and model catalogue; optional `AurixBackendClient` routes through Node/Express provider router. `MasterOrchestrator` is the UI-facing pipeline for local commands, tools, memory, automation and chat.
- **Voice:** Android `SpeechRecognizer` wrapper for STT, Android `TextToSpeech` wrapper, microphone level engine, wake-word provider interface with OpenWakeWord implementation, foreground `WakeWordService`, voice interaction and recognition services. `VoiceSessionGate` coordinates microphone ownership.
- **Tools/automation:** Android tool registry + `PermissionGate`/`AuditLog`; accessibility service and focused gesture/text/scroll helpers; `AgentLoop` bounds steps, retries, time and cancellation. Backend has a separate registry for server-side web/math/data tools and a one-tool chat loop.
- **Permissions/components:** manifest declares microphone, overlay, foreground-service types, notifications, exact alarms, boot receive, package visibility, SMS, contacts, location, calls and image access. Accessibility, voice interaction and recognition services are exported with Android binding permissions; app activity is exported as launcher; wake word/overlay and receivers are non-exported.
- **Dependencies:** versions are centrally pinned in `gradle/libs.versions.toml`: AGP 8.7.3; Kotlin 2.0.21; Compose BOM 2024.12.01; Lifecycle 2.8.7; OkHttp 4.12.0; coroutines 1.9.0; serialization 1.7.3; Security Crypto 1.1.0-alpha06; ML Kit text recognition 16.0.0; WorkManager 2.9.0; OpenWakeWord Android 0.1.2; secrets plugin 2.0.1. Backend declares Node >=18, Express ^4.19.2 and CORS ^2.8.5.

## 3. What Already Works (source-supported)

- Provider routing is interface-driven and supports provider-specific adapters, configurable models, health state, cooldowns and key slots (`provider/ProviderRouter.kt`, `ProviderManager.kt`, `KeyPoolManager.kt`, `adapters/`).
- Chat can stream SSE and parse the OpenAI-compatible event format (`data/remote/JarvisApiClient.kt`, `SseLineParser.kt`). Backend streaming uses a shared OkHttp client (`AurixBackendClient.kt`).
- Provider keys use AES-GCM with an AndroidKeyStore key (`data/local/SecureStore.kt`); backup rules exclude that secure preferences file.
- The orchestrator applies a central permission decision and provides confirmation events; automation has tests for policy and accessibility behavior (`orchestrator/MasterOrchestrator.kt`, `security/PermissionGate.kt`).
- Mission/agent work has explicit limits, cancellation and verification hooks (`planning/AgentLoop.kt`, `orchestrator/ToolRegistry.kt`).
- Wake word has a replaceable `WakeWordProvider` abstraction, microphone ownership gate, foreground service and retry backoff (`service/wakeword/`, `WakeWordService.kt`).
- Unit tests exist across provider, memory, orchestrator, mission, security, voice and UI parsing. Backend tests cover tool logic and capability routing.

## 4. Critical Problems (P0)

### P0. Backend tools cannot be reliably disabled with the documented setting
- **File:** `server/src/index.js`, `TOOLS_ENABLED` near line 67; consumed by `/v1/tool`, tool prompt composition and chat tool execution.
- **Problem:** `TOOLS_ENABLED` negates a list containing affirmative values. Consequently `AURIX_TOOLS=off` leaves tools enabled, while `AURIX_TOOLS=true` disables them. This contradicts the source comments and deployment contract.
- **Why it matters:** Operators cannot use the documented emergency/configuration switch to disable server-side actions; a value intended to enable tools has the opposite effect.
- **Recommended fix:** Parse explicit enabled/disabled values with a safe documented default and unit-test all supported values.
- **Risk:** High (security/configuration correctness). **Status:** fixed in this pass; tests run below.

### P0. Backend bearer token is persisted in plaintext preferences
- **File:** `app/src/main/java/com/jarvis/ai/data/remote/AurixBackendClient.kt`, `BackendPrefs.init/save`; called by `JarvisViewModel.saveBackend`.
- **Problem:** `app_token` is read/written in ordinary `aurix_backend` SharedPreferences. Unlike provider keys in `SecureStore`, this token is readable from app data backups/rooted-device extraction and the preferences file is not excluded by backup rules.
- **Why it matters:** The token grants access to a user's backend account and potentially its provider keys, chat history and tools.
- **Recommended fix:** Store it through the existing Keystore-backed `SecureStore`; migrate existing plaintext token only after a successful encrypted write, then remove the legacy preference.
- **Risk:** High. **Status:** fixed in this pass; no fallback to plaintext on encryption failure.

## 5. High Priority Problems (P1)

### P1. Assistant activity is represented by independent booleans and services
- **Files:** `data/model/Message.kt` (`UiState`), `viewmodel/JarvisViewModel.kt`, `service/TtsEngine.kt`, `service/WakeWordService.kt`, `core/VoiceSessionGate.kt`, `overlay/AvatarState.kt`, ambient phase bus.
- **Problem:** Listening, loading/thinking, speaking, acting and hands-free/session ownership are separately stored or derived across several owners. The same conceptual state can diverge during cancellation, service restart or callback races. `isThinking` maps to `isLoading` in a screen rather than a canonical phase model.
- **Why it matters:** Conflicting UI indicators, microphone contention and difficult lifecycle recovery become more likely as voice modes grow.
- **Recommended fix:** Define a transition model and migrate one ownership boundary at a time, preserving existing presentation; avoid adding a second state source during migration.
- **Risk:** High; broad behavior change. **Status:** open; requires its own focused design and transition tests.

### P1. Broad permission footprint needs least-privilege validation
- **File:** `app/src/main/AndroidManifest.xml`.
- **Problem:** Manifest requests SMS, contacts, precise/coarse location, calls, broad package visibility, media access, overlay, microphone and exact alarms. Some are sensitive and cannot be justified solely by their declaration; actual use and onboarding purpose need a permission-to-feature matrix.
- **Why it matters:** Excess permissions raise privacy, review-policy and user-trust costs; unused runtime permissions are unnecessary exposure.
- **Recommended fix:** Trace each permission to an active user-facing action, request only at point of use, and remove unused declarations after proving no code path depends on them.
- **Risk:** High. **Status:** open; no permission removed without a complete call-path audit.

### P1. Release APK is not optimized/minified
- **File:** `app/build.gradle.kts`, `release` build type.
- **Problem:** `isMinifyEnabled = false`; shrinking is disabled despite a ProGuard file.
- **Why it matters:** Larger release package and less obfuscation; this does not replace secret protection and is not a debug-build blocker.
- **Recommended fix:** Enable R8 only after adding keep rules and verifying provider serialization/reflection, services and Compose release behavior.
- **Risk:** Medium/high. **Status:** open; separate release validation needed.

## 6. Medium Priority Problems (P2)

### P2. Screen navigation is manually state-switched
- **File:** `app/src/main/java/com/jarvis/ai/MainActivity.kt`.
- **Problem:** Home/chat/missions are selected by a string in Compose `rememberSaveable`; no navigation graph or typed destinations.
- **Why it matters:** Current three-screen flow is understandable, but deep links, back-stack semantics and destination-specific saved state become harder to scale.
- **Recommended fix:** Consider Navigation Compose when flows/deep links warrant it; retain current behavior and add process-restoration coverage during migration.
- **Risk:** Medium. **Status:** open.

### P2. Persistence is split across unrelated stores without a unified retention/control surface
- **Files:** `data/local/ChatDb.kt`, `memory/SecureKvStore` (referenced by runtime), `memory/vector/`, `server/src/memory.js`.
- **Problem:** Chat, local vector memories, preferences, and server session/fact memory have different persistence and deletion paths. Backend state is process-memory based and therefore not durable across restart; user-visible retention/export controls are not evident in the inspected settings flow.
- **Why it matters:** Data lifecycle and “forget” semantics are harder to reason about, and server restarts lose its supposedly durable facts/history.
- **Recommended fix:** Document scope/retention explicitly, add user controls, and select a durable backend store only when deployment requirements justify it.
- **Risk:** Medium. **Status:** open.

### P2. Network transport duplication complicates consistent policy
- **Files:** `data/remote/JarvisApiClient.kt`, `AurixBackendClient.kt`, provider adapters.
- **Problem:** Both chat clients use separate shared OkHttp client definitions/configuration and response parsing paths; provider adapters add another transport abstraction.
- **Why it matters:** Timeouts, cancellation, proxy/TLS policy, instrumentation and redaction can drift.
- **Recommended fix:** Consolidate common transport policy behind injected clients while retaining endpoint-specific protocol parsing.
- **Risk:** Medium. **Status:** open.

## 7. Low Priority Problems (P3/P4)

- **P3 — naming/branding debt:** Kotlin package and database identifiers remain `jarvis` while visible brand is AURIX (`app/build.gradle.kts`, `ChatDb.kt`, secure-store names). This is internally consistent and should only be migrated with an explicit data-preserving compatibility plan.
- **P3 — backend in-memory state:** `server/src/memory.js` keeps sessions/facts in process memory; suitable for a small single-process prototype, not durable multi-instance production.
- **P4 — release polish:** minification, package branding cleanup and expanded UI polish follow reliability/security work.

## 8. Security Findings

- **Confirmed and fixed:** backend app token in plaintext SharedPreferences (see P0).
- **Confirmed and fixed:** `AURIX_TOOLS` inversion (see P0).
- **Positive evidence:** provider keys use Android Keystore AES-GCM; secure preferences excluded from cloud/device transfer; provider secrets are resolved in `JarvisRuntime`, not UI code; backend bearer comparison uses `timingSafeEqual`; backend has rate limiting and tool fetch SSRF protections.
- **Review needed:** broad permissions and data backup of chat/history; the app currently opts into `allowBackup=true` and only excludes encrypted provider secrets.
- **No hardcoded live credentials were identified in the inspected provider configuration path.** This source audit is not a complete secret scanner or runtime network-security assessment.
- **Network:** app requests cleartext-capable URLs when the user configures `http://` backend/provider URLs; assess whether cleartext should be denied by default and permit only an explicit development override.
- **Exported components:** Android-bound exported services require platform binding permissions; launcher activity is intentionally exported. Recheck intent validation on voice activity handoffs as new extras are added.

## 9. Performance Findings

- Shared OkHttp connection pools avoid per-turn thread/socket churn in both chat clients.
- Potential costs to profile: continuous microphone capture and wake-word foreground service, ambient overlay redraw, SQLite operations initiated by UI coroutine paths, and repeated provider probing. No profiler trace was available, so no measured bottleneck is claimed.
- `SecureStore.put` uses synchronous `commit()` for durability; callers should keep writes off main thread when storing larger/multiple values.
- Compose screens derive presentation from a broad `UiState`; profile recomposition before optimizing rather than assuming it is excessive.

## 10. Voice/AI Findings

- Provider agnosticism is substantially present for chat routing, but not universal: some provider-specific probes/format assumptions remain, and server and Android tool systems are separate registries.
- Streaming, health and fallback structures exist. Verify cancellation propagation and total call timeouts across each adapter before production claims.
- Voice has STT/TTS wrappers, a pluggable wake-word interface and one-mic gate. Android background microphone restrictions and service start conditions require device testing on API 34–36; source inspection alone cannot certify Android 16 behavior.
- The UI state is Boolean-heavy rather than one authoritative assistant phase. This is the main architectural follow-up for voice reliability.
- Context/history flow exists, but local SQLite history, vector memory and server-side session memory have different boundaries and controls.

## 11. UI/UX Findings

- Existing Compose UI has separate home, chat, missions, onboarding and voice-aware surfaces with AURIX red/black styling. Preserve this working identity.
- Loading, listening, speaking and confirmation feedback are present in the current screen paths; error and empty-state behavior varies by feature and warrants a systematic screen review.
- No full accessibility audit (TalkBack, font scale, contrast, touch targets) or responsive-device matrix was run.
- Navigation is simple but hand-maintained; defer framework migration until feature growth makes it valuable.

## 12. Technical Debt

- One very large `JarvisViewModel` owns UI state, voice session orchestration, command routing, backend settings and several feature collaborators.
- Runtime resolves a circular dependency between `AgentCore` and `MasterOrchestrator` through mutable post-construction wiring (`core/JarvisRuntime.kt`).
- Duplicate naming/legacy compatibility (`Jarvis*`, JARVIS sender migration) remains in internal identifiers.
- Android and server tool registries/security rules are distinct and can drift.
- Root docs describe a wide capability surface; keep claims synchronized with actual runtime availability and deployment prerequisites.
- A TODO/FIXME/XXX scan of `app` and `server` source returned no matches; this is not proof every path is complete.

## 13. Missing Features / Gaps

- Canonical `AurixState` transition owner across foreground UI, TTS, STT, wake-word and automation.
- Permission dashboard and permission-to-feature inventory; memory/key/tool controls with clear deletion semantics.
- Durable backend database and multi-instance-safe session/fact storage if deployed beyond single-process ephemeral use.
- End-to-end device tests for Android 14–16 microphone/foreground-service/overlay/notification/permission behavior.
- Release R8/minified build verification and explicit network cleartext policy.
- Test coverage for backend tools-off mode and backend-token migration (added for tools mode; Android migration covered through implementation/build verification as available).

## 14. Recommended Architecture

Keep the existing modular package structure and `JarvisRuntime` composition root. Make `MasterOrchestrator` the single request pipeline and continue to put provider-specific behavior behind provider adapters. For future work, establish a typed assistant phase state owner with explicit transitions and expose read-only derived state to Compose and services; do not copy the same phase into independent booleans. Keep tool definitions paired with typed argument/result contracts and one permission source of truth. Use injected storage/network interfaces at boundaries so local-first behavior, tests and optional backend routing remain explicit. Migrate incrementally with characterization tests and preserve existing flows.

## 15. Recommended Development Roadmap

See `ROADMAP.md` for prioritized, sequenced work with affected components, dependencies, complexity, risk and expected benefit.

## Verification Results for This Pass

- Backend command `node --test tests/*.test.js`: **19 passed, 0 failed**, including new checks for the tool-disable parser.
- `git diff --check`: passed.
- Existing static-analysis scripts: agents 2–7 passed. Agent 1 reported `com.jarvis.ai.BuildConfig` as unknown; that class is generated by the Android Gradle plugin (`buildFeatures.buildConfig = true`), so this is a known analyzer false positive rather than a source declaration defect.
- Android Gradle build, JVM unit tests and lint were attempted with `sh gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` but could not start because this environment has no Java/JDK (`JAVA_HOME` unset and no `java` on `PATH`). The direct `./gradlew` invocation also fails because the checked-out wrapper file is not executable. No APK was produced; Android-specific changes remain unverified by compilation in this environment.
