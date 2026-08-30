#!/usr/bin/env bash
#
# JARVIS device test / development-loop helper
# --------------------------------------------
# Safe, read-only-first workflow:
#   ./gradlew assembleDebug
#       -> adb install -r APK
#       -> force-stop + launch JARVIS
#       -> collect logcat
#       -> verify package / AccessibilityService / process state
#       -> save artifacts, exit non-zero on failure
#
# REQUIRES ADB over Wireless Debugging (Android 11+). Setup (one time):
#   1. Settings -> About phone -> tap Build number 7x (enables Developer Options)
#   2. Settings -> System -> Developer Options -> Wireless Debugging -> ON
#   3. Tap "Wireless Debugging" -> note the PAIRING port + 6-digit code
#   4. From this shell:  adb pair localhost:<PAIR_PORT>   # enter code when prompted
#   5. Note the CONNECTION port shown, then:
#        export ADB_PORT=<CONNECTION_PORT>
#        ./tools/device-test.sh
# Or (PC alternative): plug USB, run `adb tcpip 5555` on the PC, then
#   `export ADB_PORT=5555` and connect via the phone's LAN IP instead of localhost.
#
# No device settings are changed by this script. It only installs, launches,
# inspects and reports.

set -u

# ---- configuration -------------------------------------------------------
PKG="com.jarvis.ai"
SERVICE="$PKG/.accessibility.JarvisAccessibilityService"
ACTIVITY="$PKG/.MainActivity"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
ADB_PORT="${ADB_PORT:-5555}"
ARTIFACT_DIR="${ARTIFACT_DIR:-/sdcard/Download/jarvis-test}"
BUILD="${BUILD:-0}"          # set BUILD=1 (or pass --build) to run gradle first
TIMEOUT="${TIMEOUT:-20}"     # seconds to wait for launch / logcat capture

FAIL=0

log()  { echo "[device-test] $*"; }
ok()   { echo "  [OK]   $*"; }
bad()  { FAIL=1; echo "  [FAIL] $*"; }
die()  { log "ERROR: $*"; exit 1; }

# ---- 0. locate adb -------------------------------------------------------
ADB="$(command -v adb 2>/dev/null || true)"
[ -z "$ADB" ] && die "adb not found in PATH. Install android-tools in Termux (pkg install android-tools)."
log "adb: $ADB ($($ADB version 2>/dev/null | head -1))"

# ---- 1. ensure a device is connected ------------------------------------
connect_if_needed() {
  if $ADB devices 2>/dev/null | grep -qw device; then
    return 0
  fi
  log "No device connected. Trying 'adb connect localhost:$ADB_PORT' ..."
  $ADB connect "localhost:$ADB_PORT" >/dev/null 2>&1 || true
  sleep 2
  $ADB devices 2>/dev/null | grep -qw device
}

if ! connect_if_needed; then
  cat <<EOF

==================================================================
ADB NOT CONNECTED
------------------------------------------------------------------
Enable Wireless Debugging and pair once:
  Settings -> Developer Options -> Wireless Debugging -> ON
  adb pair localhost:<PAIR_PORT>        # 6-digit code from the screen
  export ADB_PORT=<CONNECTION_PORT>
  ./tools/device-test.sh
Or from a PC over USB:  adb tcpip 5555  ->  adb connect <phone-ip>:5555
==================================================================
EOF
  die "ADB connection required (see above)."
fi

DEVICE="$($ADB devices 2>/dev/null | awk '/\tdevice/{print $1; exit}')"
log "device: $DEVICE"

# ---- 2. optional build ---------------------------------------------------
if [ "${1:-}" = "--build" ] || [ "$BUILD" = "1" ]; then
  log "Building debug APK (./gradlew assembleDebug) ..."
  ( cd "$REPO_ROOT" && ./gradlew assembleDebug ) || die "gradle build failed"
fi

[ -f "$APK" ] || die "APK not found at $APK (run './gradlew assembleDebug' first or pass --build)."

# ---- 3. install ----------------------------------------------------------
log "Installing $APK"
if $ADB install -r -t "$APK" 2>&1 | tee /tmp/jarvis_install.log | grep -qiE 'success|already'; then
  ok "APK installed"
else
  bad "APK install failed"; cat /tmp/jarvis_install.log
fi

# ---- 4. launch -----------------------------------------------------------
mkdir -p "$ARTIFACT_DIR" 2>/dev/null || ARTIFACT_DIR="$REPO_ROOT/device-artifacts"
ARTIFACT_DIR="${ARTIFACT_DIR:-$REPO_ROOT/device-artifacts}"
mkdir -p "$ARTIFACT_DIR"

log "Force-stopping and launching $PKG"
$ADB shell am force-stop "$PKG" 2>/dev/null || true
sleep 1
$ADB shell am start -n "$ACTIVITY" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null 2>&1 || bad "am start failed"
sleep 3
if $ADB shell ps -A 2>/dev/null | grep -qi "$PKG"; then
  ok "JARVIS process is alive after launch"
else
  bad "JARVIS process not found after launch"
fi

# ---- 5. collect logcat ---------------------------------------------------
log "Capturing logcat ($TIMEOUT s) -> $ARTIFACT_DIR/logcat.txt"
$ADB logcat -c 2>/dev/null || true
( sleep "$TIMEOUT"; $ADB logcat -d -b all > "$ARTIFACT_DIR/logcat.txt" 2>/dev/null ) &
LOGPID=$!
# trigger a little activity
$ADB shell input tap 540 1200 2>/dev/null || true
wait "$LOGPID" 2>/dev/null || true
[ -s "$ARTIFACT_DIR/logcat.txt" ] && ok "logcat saved ($(wc -l < "$ARTIFACT_DIR/logcat.txt") lines)" || bad "no logcat captured"

# ---- 6. verification ----------------------------------------------------
echo
log "=== Accessibility / package verification ==="

# 6a. package installed
if $ADB shell pm list packages 2>/dev/null | grep -qi "package:$PKG"; then
  ok "Package $PKG is installed"
else
  bad "Package $PKG NOT installed"
fi

# 6b. service declared in package
if $ADB shell dumpsys package "$PKG" 2>/dev/null | grep -qi "JarvisAccessibilityService"; then
  ok "AccessibilityService declared in manifest"
else
  bad "AccessibilityService NOT declared"
fi

# 6c. service enabled (Settings.Secure enabled_accessibility_services)
ENABLED="$($ADB shell settings get secure enabled_accessibility_services 2>/dev/null)"
if echo "$ENABLED" | grep -q "$SERVICE"; then
  ok "AccessibilityService is ENABLED"
elif [ -z "$ENABLED" ] || [ "$ENABLED" = "null" ]; then
  bad "AccessibilityService is DISABLED (enable in Settings -> Accessibility -> JARVIS)"
else
  bad "AccessibilityService not in enabled list: $ENABLED"
fi

# 6d. service connected / running (process + bound service)
if $ADB shell ps -A 2>/dev/null | grep -qi "$PKG"; then
  ok "JARVIS process alive (service host running)"
else
  bad "JARVIS process not alive"
fi
if $ADB shell dumpsys activity services "$PKG" 2>/dev/null | grep -qi "JarvisAccessibilityService"; then
  ok "AccessibilityService bound/running"
else
  bad "AccessibilityService not reported as running (may need a screen tap or enable)"
fi

# 6e. basic safe operation: send a benign accessibility-driven command via the
#     app's own input surface. We verify the service can react by checking it
#     stays alive and logs after an input event. A full UI action (e.g. "open
#     whatsapp") must be issued through the app UI / voice, not from here.
$ADB shell input tap 540 1200 2>/dev/null || true
sleep 2
if $ADB shell ps -A 2>/dev/null | grep -qi "$PKG"; then
  ok "Process survived a safe input event (service responsive)"
else
  bad "Process died after input event"
fi

# ---- 7. artifacts + summary ---------------------------------------------
cp "$APK" "$ARTIFACT_DIR/jarvis-debug.apk" 2>/dev/null || true
{
  echo "device: $DEVICE"
  echo "pkg: $PKG"
  echo "apk: $APK"
  echo "time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$ARTIFACT_DIR/MANIFEST.txt"

echo
if [ "$FAIL" = "0" ]; then
  log "ALL CHECKS PASSED -> artifacts in $ARTIFACT_DIR"
  exit 0
else
  log "SOME CHECKS FAILED (see [FAIL] above) -> artifacts in $ARTIFACT_DIR"
  exit 1
fi
