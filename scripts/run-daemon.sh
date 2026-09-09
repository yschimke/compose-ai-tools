#!/usr/bin/env bash
#
# Run the preview daemon for a consumer module.
#
# The daemon itself lives in compose-preview-daemon (consumed here at the
# `composeai-preview-daemon` catalog pin); the renderer-agnostic protocol smoke
# launcher went with it. This script covers the per-module path only.
#
# Usage (Gradle path or filesystem path):
#      scripts/run-daemon.sh :samples:android
#      scripts/run-daemon.sh samples/android-daemon-bench
#    Runs `<module>:composePreviewDaemonStart` to materialize
#    `<module>/build/compose-previews/daemon-launch.json`, then exec's the
#    JVM exactly as that descriptor specifies (javaLauncher, classpath, JVM
#    args, system properties, working directory, mainClass). Same shape the
#    VS Code extension uses on the per-save hot path.
#    The module must apply `id("ee.schimke.composeai.preview")`.
#
# stdin/stdout are the JSON-RPC channel; stderr is free-form log
# (PROTOCOL.md § 1). Send `shutdown` then `exit` JSON-RPC requests, or close
# stdin, to terminate the daemon.
#
# The DaemonExtension `enabled` flag defaults to true; when the descriptor
# reports `enabled: false` we still spawn the JVM (the rest of the
# descriptor's fields are populated honestly per DaemonClasspathDescriptor
# kdoc) and print a one-line note on stderr. Flip
# `composePreview { daemon { enabled = true } }` in the module's build script
# to silence the note.
#
# Requires: bash, python3 (for JSON), the bundled ./gradlew.

set -euo pipefail

cd "$(dirname "$0")/.."

usage() {
    sed -n '3,/^$/{s/^# \{0,1\}//;p;}' "$0"
    exit "${1:-0}"
}

MODULE=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage 0 ;;
        --) shift; break ;;
        -*) echo "[run-daemon] unknown flag: $1" >&2; usage 2 ;;
        *)
            [ -z "$MODULE" ] || { echo "[run-daemon] only one module accepted" >&2; usage 2; }
            MODULE="$1"; shift ;;
    esac
done

if [ -z "$MODULE" ]; then
    echo "[run-daemon] a module argument is required; the bare protocol smoke daemon now lives in compose-preview-daemon" >&2
    usage 2
fi

# Accept either Gradle path (`:samples:android`) or filesystem path
# (`samples/android` / `samples/android/`).
case "$MODULE" in
    :*)
        GRADLE_PATH="$MODULE"
        FS_PATH="${MODULE#:}"; FS_PATH="${FS_PATH//://}"
        ;;
    *)
        FS_PATH="${MODULE%/}"
        GRADLE_PATH=":${FS_PATH//\//:}"
        ;;
esac

DESCRIPTOR="$FS_PATH/build/compose-previews/daemon-launch.json"

echo "[run-daemon] $GRADLE_PATH:composePreviewDaemonStart" >&2
./gradlew "${GRADLE_PATH}:composePreviewDaemonStart" --console=plain >&2

if [ ! -f "$DESCRIPTOR" ]; then
    echo "[run-daemon] descriptor not produced: $DESCRIPTOR" >&2
    echo "[run-daemon] does $GRADLE_PATH apply id(\"ee.schimke.composeai.preview\")?" >&2
    exit 1
fi

echo "[run-daemon] exec from $DESCRIPTOR" >&2

exec python3 -c '
import json, os, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
if not d.get("enabled"):
    print(
        "[run-daemon] note: descriptor enabled=false; spawning anyway "
        "(set composePreview.daemon.enabled = true to silence)",
        file=sys.stderr,
    )
java = d.get("javaLauncher") or "java"
argv = [java]
argv += list(d.get("jvmArgs", []))
for k, v in d.get("systemProperties", {}).items():
    argv.append(f"-D{k}={v}")
cp = d.get("classpath") or []
if cp:
    argv += ["-cp", os.pathsep.join(cp)]
argv.append(d["mainClass"])
wd = d.get("workingDirectory")
if wd:
    os.chdir(wd)
os.execvp(argv[0], argv)
' "$DESCRIPTOR"
