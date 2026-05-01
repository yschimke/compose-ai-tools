#!/usr/bin/env bash
#
# Kill any running preview daemon JVM.
#
# Companion to scripts/run-daemon.sh — the inverse operation. Useful when
# iterating on daemon code: the running JVM keeps serving renders from the
# JAR it was spawned with even after a `./gradlew :daemon:android:assemble`,
# so a manual kill is required to pick up the rebuilt JAR. The VS Code
# extension also exposes `Compose Preview: Restart Preview Daemon` for the
# same purpose; this script is the equivalent for terminal-driven workflows.
#
# Usage:
#   scripts/kill-daemon.sh           # SIGTERM (graceful — shutdown handlers run)
#   scripts/kill-daemon.sh --force   # SIGKILL (only if SIGTERM is ignored)
#   scripts/kill-daemon.sh --list    # list matching processes, kill nothing
#
# Pattern matches the daemon's main class — `ee.schimke.composeai.daemon.DaemonMain`
# — which is unique enough to avoid clipping the Gradle daemon, the Kotlin daemon,
# or anything else on PATH.

set -euo pipefail

PATTERN='ee\.schimke\.composeai\.daemon\.DaemonMain'

case "${1:-}" in
  --list)
    pgrep -fl "$PATTERN" || {
      echo "no compose-ai-daemon process matches $PATTERN" >&2
      exit 0
    }
    ;;
  --force)
    if pgrep -f "$PATTERN" > /dev/null; then
      pkill -9 -f "$PATTERN"
      echo "SIGKILL sent" >&2
    else
      echo "no compose-ai-daemon process to kill" >&2
    fi
    ;;
  ""|--help|-h)
    if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
      sed -n '3,21p' "$0"
      exit 0
    fi
    if pgrep -f "$PATTERN" > /dev/null; then
      pkill -f "$PATTERN"
      echo "SIGTERM sent — JsonRpcServer shutdown handlers will run before exit" >&2
    else
      echo "no compose-ai-daemon process to kill" >&2
    fi
    ;;
  *)
    echo "unknown argument: $1" >&2
    echo "usage: $0 [--list|--force|--help]" >&2
    exit 2
    ;;
esac
