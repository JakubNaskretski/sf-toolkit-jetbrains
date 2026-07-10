#!/usr/bin/env bash
# Build the plugin and install it into the newest local IntelliJ config (macOS/Linux).
# Usage: ./install-local.sh [--no-build]   — restart the IDE afterwards.
set -euo pipefail
cd "$(dirname "$0")"

if [[ "${1:-}" != "--no-build" ]]; then
  if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
  fi
  ./gradlew buildPlugin -q
fi

ZIP=$(ls -t build/distributions/sf-toolkit-*.zip | head -1)
[[ -n "$ZIP" ]] || { echo "no plugin zip in build/distributions"; exit 1; }

case "$(uname)" in
  Darwin) CONFIG_ROOT="$HOME/Library/Application Support/JetBrains" ;;
  *)      CONFIG_ROOT="$HOME/.config/JetBrains" ;;
esac
CONFIG=$(ls -td "$CONFIG_ROOT"/{IntelliJIdea,IdeaIC}* 2>/dev/null | head -1)
[[ -n "$CONFIG" ]] || { echo "no IntelliJ config dir under $CONFIG_ROOT — launch the IDE once first"; exit 1; }

mkdir -p "$CONFIG/plugins"
rm -rf "$CONFIG/plugins/sf-toolkit"
unzip -o -q "$ZIP" -d "$CONFIG/plugins"
echo "installed $(basename "$ZIP") → $CONFIG/plugins/sf-toolkit — restart the IDE"
