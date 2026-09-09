#!/usr/bin/env sh
set -e
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
fi
echo "Gradle is not installed and gradle/wrapper/gradle-wrapper.jar is missing." >&2
echo "Use Android Studio or GitHub Actions, which installs Gradle 8.9 automatically." >&2
exit 127
