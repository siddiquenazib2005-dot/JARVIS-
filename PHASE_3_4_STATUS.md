# AURIX — Phase 3 & Phase 4 status

Built in one pass on top of Phase 1 (UI + offline command layer) and Phase 2
(real multi-provider backend). **PC Connect was intentionally excluded** at the
user's request; the old socket bridge was deleted from the codebase.

## Phase 3 — Screen automation + Missions

| Feature | File | Chat commands |
|---|---|---|
| Tap by visible text | `automation/ScreenAutomation.kt` | `tap send`, `click login` |
| Long press | same | `long press message` |
| Type into focused field | same | `type hello there` |
| Scroll up/down | same | `scroll down`, `upar scroll` |
| Scroll until text found | same | `scroll until checkout` |
| Swipe gestures | same | `swipe left` |
| Back / Home / Recents / Lock | same | `go back`, `go home`, `recent apps`, `lock screen` |
| Read what's on screen | same | `what's on my screen` |
| Saved multi-step routines | `missions/MissionEngine.kt` | `list missions`, `run mission driving` |
| Create a routine | same | `create mission night: torch off, mute, open settings` |
| Delete a routine | same | `delete mission night` |
| Ad-hoc chains | same | `open whatsapp then scroll down then read screen` |

Missions persist in `aurix_missions` prefs as JSON and ship with three starter
routines (`good morning`, `driving`, `night mode`). Steps execute sequentially
on a background scope with a 900 ms settle delay, entirely offline.

All gestures go through the existing hardened `JarvisAccessibilityService`
(serialized action mutex, retries, verification). If the service is off, AURIX
opens the Accessibility settings page instead of failing silently.

## Phase 4 — Notifications, OTP, floating avatar

| Feature | File | Chat commands |
|---|---|---|
| Notification capture | `notifications/NotificationRelay.kt` | `read my notifications` |
| OTP auto-extract | same | `otp`, `verification code` |
| Floating bubble | `overlay/FloatingAvatarService.kt` | `show bubble`, `hide bubble` |

- Notifications live in a 60-item **in-memory** ring buffer only — nothing about
  your messages or OTPs is written to disk.
- OTP detection requires a verification keyword nearby (`otp`, `verification
  code`, `2fa`, …) so amounts, dates and phone numbers are never mistaken for a
  code. Codes expire from recall after 10 minutes.
- The bubble is a draggable overlay: drag to reposition, tap to open AURIX. It
  runs as a `specialUse` foreground service so Android keeps it alive.

## Permissions added

- `FOREGROUND_SERVICE_SPECIAL_USE` — bubble service on Android 14+
- Notification listener service declaration (`BIND_NOTIFICATION_LISTENER_SERVICE`)
- `<queries>` intent filters so app lookup works reliably on Android 11+
- Removed the incorrect `<uses-permission android:name="…BIND_ACCESSIBILITY_SERVICE">`
  entry (that constant belongs on the service, not in `uses-permission`)

Each capability asks for its own permission the first time it's used, and
degrades to a helpful sentence instead of crashing when denied.

## Not included

- **PC Connect / desktop bridge** — removed on request (`bridge/SocketServer.kt`
  deleted, `pcBridgeInfo` action and its command route removed).
