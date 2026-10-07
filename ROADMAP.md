# AURIX Development Roadmap

Roadmap is based on source inspection of commit `6d8617e`. Priority: P0 broken/security-critical; P1 stability/architecture; P2 major capability; P3 useful enhancement; P4 polish. Sequence within a phase is deliberate; avoid starting a later phase while a prior security/stability blocker is open.

## Phase 1 — Security and Configuration Correctness

### P0: Repair backend tool-disable setting — DONE
- **Purpose:** Make `AURIX_TOOLS=off` actually disable tool prompting/execution and keep affirmative settings enabled.
- **Dependencies:** None.
- **Components:** `server/src/index.js`, backend config tests.
- **Complexity/Risk:** S / High (security behavior).
- **Benefit:** Operators regain a reliable server-side action kill switch.
- **Order:** First; verified with Node tests.

### P0: Encrypt the mobile backend bearer token — DONE
- **Purpose:** Keep app token under the existing Android Keystore-backed secret storage and migrate legacy plaintext safely.
- **Dependencies:** `SecureStore`.
- **Components:** `data/remote/AurixBackendClient.kt`, `data/local/SecureStore.kt`, legacy `aurix_backend` preference.
- **Complexity/Risk:** S / High (credential persistence).
- **Benefit:** Reduces token exposure in app preference files and backup copies.
- **Order:** Alongside tools flag; implementation is complete, but Android build/test verification is still pending because this environment has no JDK.

### P1: Permission-to-feature inventory and least-privilege pass
- **Purpose:** Prove each sensitive manifest permission is necessary, request at point of use, and surface controls to the owner.
- **Dependencies:** Trace all permission call sites and onboarding routes.
- **Components:** `AndroidManifest.xml`, onboarding, tools, voice, contacts/location/media features.
- **Complexity/Risk:** M / High (can disable existing behavior if done without traceability).
- **Benefit:** Lower privacy and policy exposure; clearer user trust.
- **Order:** Before adding more device capabilities.

### P0: Make clean debug APK builds reproducible — DONE (build verification pending)
- **Purpose:** Remove dependence on a missing repository-root debug keystore and restore direct wrapper execution.
- **Dependencies:** Android Gradle Plugin default debug signing.
- **Components:** `app/build.gradle.kts`, `gradlew` executable mode.
- **Complexity/Risk:** S / High (release blocker).
- **Benefit:** Clean checkouts and CI can use the standard Gradle wrapper task to produce a debug APK.
- **Order:** First stability fix; local build still needs JDK 17 + Android SDK to verify.

### P1: Gate sensitive multi-step plans before execution — DONE (JVM verification pending)
- **Purpose:** Request approval for any gated step before the plan runs and carry confirmed state into tool execution.
- **Dependencies:** Existing `PermissionGate`, `AgentLoop`, confirmation UI.
- **Components:** `MasterOrchestrator`, `ToolExecutor` plan policy, focused tests.
- **Complexity/Risk:** S / High (action safety).
- **Benefit:** No earlier mission step runs while a later sensitive step is awaiting approval; the user gets a real confirmation request.
- **Order:** Before adding more write-capable tools; validate with Gradle tests when a JDK is available.

## Phase 2 — Voice State and Lifecycle Stability

### P1: Define canonical assistant phase and transition owner
- **Purpose:** Replace conflicting activity booleans with a single authoritative transition model for idle/listening/thinking/acting/speaking/error/cancelled states.
- **Dependencies:** Document current event ordering; characterize push-to-talk, hands-free, TTS, wake-word and action flows.
- **Components:** `UiState`, `JarvisViewModel`, `VoiceSessionGate`, `TtsEngine`, `WakeWordService`, `AvatarStateBus`, screens.
- **Complexity/Risk:** L / High.
- **Benefit:** Prevents stale indicators and mic ownership races; enables clear barge-in and recovery semantics.
- **Order:** First architecture project; migrate UI derivations first, then service event sources, with transition tests at each step.

### P1: Validate API 34–36 foreground microphone and wake-word behavior
- **Purpose:** Verify user-start requirements, notification permission, service restart and process-death behavior on actual devices/emulators.
- **Dependencies:** Device matrix and reproducible manual/instrumented scenarios.
- **Components:** `WakeWordService`, `MainActivity`, manifest FGS declarations and onboarding.
- **Complexity/Risk:** M / High.
- **Benefit:** Reliable compliant voice operation and fewer battery/service surprises.
- **Order:** After state transitions are specified; do not claim Android 16 readiness before device evidence.

## Phase 3 — Orchestration and Testability

### P1: Reduce ViewModel responsibilities through use-case boundaries
- **Purpose:** Move discrete voice session, backend settings and command persistence orchestration behind testable collaborators without changing product behavior.
- **Dependencies:** Characterization tests and canonical state work.
- **Components:** `JarvisViewModel`, `MasterOrchestrator`, runtime construction.
- **Complexity/Risk:** L / Medium-high.
- **Benefit:** Smaller failure surface and easier testing.
- **Order:** Extract only where seams map to stable responsibilities.

### P2: Unify Android tool contracts and permission metadata
- **Purpose:** Keep registry schema, risk classification, permission requirement, execution and verification aligned.
- **Dependencies:** Permission inventory; preserve confirmation semantics.
- **Components:** `ToolRegistry`, `ToolExecutor`, `PermissionGate`, `MasterOrchestrator`, mission engine.
- **Complexity/Risk:** M / High.
- **Benefit:** Safer expansion of device actions and less policy drift.
- **Order:** Before adding sensitive tools such as files, messaging or calendar writes.

### P2: Strengthen networking cancellation, timeout and error normalization
- **Purpose:** Ensure cancellation closes streaming calls and timeouts/fallback do not leave stuck UI state.
- **Dependencies:** Existing provider tests; transport seam.
- **Components:** `ProviderRouter`, adapters, `JarvisApiClient`, `AurixBackendClient`, `JarvisViewModel`.
- **Complexity/Risk:** M / Medium-high.
- **Benefit:** More predictable streaming and provider failover.
- **Order:** After state model defines cancellation outcomes.

## Phase 4 — Local-First Memory and Privacy Controls

### P1: Define memory lifecycle and user controls
- **Purpose:** Explain and control conversation history, vector facts, backend sessions and provider data sharing.
- **Dependencies:** Data-flow/retention inventory.
- **Components:** `ChatDb`, `MemoryEngine`, vector stores, backend client/settings, privacy UI.
- **Complexity/Risk:** M / High (data loss/privacy).
- **Benefit:** Clear owner control over sensitive assistant context.
- **Order:** Before enabling more proactive memory ingestion.

### P2: Select durable backend session storage if service is persistent
- **Purpose:** Replace per-process backend maps only if deployment needs restart persistence or multiple instances.
- **Dependencies:** Hosting/data retention requirements; migration plan.
- **Components:** `server/src/memory.js`, backend configuration and deployment.
- **Complexity/Risk:** M / Medium-high.
- **Benefit:** Facts/session history survive restart and scale beyond one process.
- **Order:** After privacy/retention semantics are defined; current process-local design may suit local/single-instance use.

### P2: Review backup and transport policy
- **Purpose:** Decide whether conversation databases/settings should be backed up and prohibit accidental cleartext token transmission by default.
- **Dependencies:** User expectations and local development workflow.
- **Components:** backup rules, `BackendPrefs.normalise`, API URL validation/network security config.
- **Complexity/Risk:** S-M / Medium.
- **Benefit:** Fewer privacy leaks and safer backend setup.
- **Order:** Before production rollout.

## Phase 5 — Mission Reliability and Capability Growth

### P2: Persist mission execution history and explicit progress/cancellation
- **Purpose:** Let the owner see step status, approvals, failures and cancellation outcomes.
- **Dependencies:** Canonical assistant/task state and tool permission contracts.
- **Components:** `MissionEngine`, `MissionTriggers`, mission screens, `AgentLoop`, history store.
- **Complexity/Risk:** L / High.
- **Benefit:** Trustworthy multi-step tasks and recovery.
- **Order:** Extend current bounded engine; never introduce unbounded autonomous behavior.

### P2: Add capabilities through the typed tool registry
- **Purpose:** Add safe app/device, weather/search, maps, calendar or notification actions with explicit approvals.
- **Dependencies:** Tool contract and permission dashboard.
- **Components:** provider capability adapters, `ToolRegistry`, backend `tools.js` where appropriate, UI.
- **Complexity/Risk:** M-L / High for write actions.
- **Benefit:** More useful assistant while preserving review/verification.
- **Order:** One capability at a time, tests and rollback path per tool.

## Phase 6 — Production Hardening and UX

### P1: Release build and R8 validation
- **Purpose:** Produce a shrinkable release only after validating reflection, serialization, services and startup.
- **Dependencies:** Keep-rule inventory, signing configuration, release CI.
- **Components:** Gradle release config, ProGuard rules, CI workflow.
- **Complexity/Risk:** M / Medium-high.
- **Benefit:** Smaller and more production-ready package.
- **Order:** After functional/security work; debug APK remains the current deliverable.

### P2: Add automated device smoke coverage
- **Purpose:** Check onboarding, permissions, voice session, navigation, missions, accessibility setup and process restoration.
- **Dependencies:** Emulator/device CI and stable test seams.
- **Components:** `tools/device-test.sh`, Android test setup, CI.
- **Complexity/Risk:** M / Medium.
- **Benefit:** Catch lifecycle regressions that JVM tests cannot.
- **Order:** Start with permission and voice paths.

### P3: Navigation and accessibility review
- **Purpose:** Validate font scaling, screen-reader semantics, contrast, touch targets and meaningful back-stack behavior; migrate to Navigation Compose only when needed.
- **Dependencies:** UX review and device coverage.
- **Components:** Compose screens and `MainActivity`.
- **Complexity/Risk:** M / Low-medium.
- **Benefit:** More usable and scalable navigation.
- **Order:** After stability/security priorities.

### P4: Branding and cosmetic polish
- **Purpose:** Resolve internal legacy names and refine non-blocking visual details.
- **Dependencies:** Stable data migration and AURIX identity decisions.
- **Components:** package/database identifiers, UI resources/docs.
- **Complexity/Risk:** M / Medium due to installed-app/data compatibility.
- **Benefit:** Cleaner branding.
- **Order:** Last; do not risk user data or working UI for cosmetic consistency.
