#!/usr/bin/env bash
# Verify — and optionally repair — BuildFetch remote build-cache entries.
#
# Why this exists (issue #2824): an entry can be stored TRUNCATED at rest. It is served as HTTP 200
# with a `content-length` matching the truncated body, so nothing detects the short read until the
# gzip stream runs off the end mid-inflate:
#
#   Failed to load cache entry <key> for task '…': Could not load from remote cache:
#   Unexpected end of ZLIB input stream
#
# Gradle treats that as FATAL (corruption is not a recoverable cache failure), and it cannot
# self-heal: the load aborts before the task executes, so nothing ever pushes a replacement. Neither
# does `composeai.cacheSalt` help for every case — it is an input property on Kotlin compilation
# tasks only, so it cannot orphan a poisoned AGP-task or artifact-transform entry.
#
# The repair: Gradle's LOCAL build cache (`~/.gradle/caches/build-cache-1/<key>`) uses the SAME key
# space and the SAME packed format as the remote. So a machine that has executed the affected task
# holds a byte-valid replacement, and PUTting it under the same key overwrites the truncated object.
#
#   # check only (no token needed beyond read access, nothing is written)
#   scripts/repair-remote-cache.sh --check 12fcc978271d75e6470ed675c023a05d
#
#   # repair (needs a cache:readwrite token)
#   BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN=<rw-token> \
#     scripts/repair-remote-cache.sh 877aca9ad8277aac3a9adf9a18efc1ec
#
# A key whose local copy is missing cannot be repaired from this machine — the task has to have run
# here with the same inputs (same Gradle/AGP/JDK) for the key to exist. When a key only reproduces
# on CI, run `.github/workflows/remote-cache-repair.yml`, which executes the tasks on a runner first.
#
# Exit status: 0 when every requested key ends healthy, 1 otherwise (so CI can gate on it).
set -euo pipefail

CACHE_URL="${COMPOSEAI_CACHE_URL:-https://cache.eu-central-a.buildfetch.com/8ESz2z/gradle}"
LOCAL_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/build-cache-1"
CHECK_ONLY=false

usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

keys=()
while [ $# -gt 0 ]; do
  case "$1" in
    --check | --check-only) CHECK_ONLY=true ;;
    -h | --help) usage 0 ;;
    -*) echo "unknown flag: $1" >&2 && usage 1 ;;
    *) keys+=("$1") ;;
  esac
  shift
done
[ ${#keys[@]} -gt 0 ] || usage 1

TOKEN="${BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN:-${BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN:-}}"
if [ -z "$TOKEN" ]; then
  echo "error: no BuildFetch token in the environment" >&2
  echo "  set BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN (readwrite to repair, readonly to --check)" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Prints "<status> <bytes> <inflated>" for a packed cache entry:
#   ok        — gzip stream terminates (a usable entry)
#   truncated — inflates partially, never reaches end-of-stream (the #2824 signature)
#   invalid   — not a readable gzip stream at all
inspect() {
  python3 - "$1" <<'PY'
import sys, zlib
raw = open(sys.argv[1], 'rb').read()
if not raw:
    print("invalid 0 0"); sys.exit()
d = zlib.decompressobj(16 + zlib.MAX_WBITS)
try:
    out = d.decompress(raw) + d.flush()
except Exception:
    print(f"invalid {len(raw)} 0"); sys.exit()
print(f"{'ok' if d.eof else 'truncated'} {len(raw)} {len(out)}")
PY
}

# GET the remote entry into $tmp/<key>.remote; echoes "missing" when the server has no such key.
fetch_remote() {
  local key="$1" out="$tmp/$key.remote"
  local code
  code="$(curl -sS -u "token-auth:$TOKEN" -o "$out" -w '%{http_code}' "$CACHE_URL/$key")"
  case "$code" in
    200) inspect "$out" ;;
    404) echo "missing 0 0" ;;
    *) echo "http:$code 0 0" ;;
  esac
}

failed=0
for key in "${keys[@]}"; do
  echo "── $key"
  read -r status bytes inflated <<<"$(fetch_remote "$key")"
  printf '   remote: %-9s bytes=%-9s inflated=%s\n' "$status" "$bytes" "$inflated"

  if [ "$status" = "ok" ]; then
    echo "   nothing to do"
    continue
  fi

  local_entry="$LOCAL_CACHE/$key"
  if [ ! -f "$local_entry" ]; then
    echo "   local:  absent — cannot repair from this machine"
    echo "           run the task that produces this key here first, or use the repair workflow"
    failed=1
    continue
  fi

  read -r lstatus lbytes linflated <<<"$(inspect "$local_entry")"
  printf '   local:  %-9s bytes=%-9s inflated=%s\n' "$lstatus" "$lbytes" "$linflated"
  if [ "$lstatus" != "ok" ]; then
    echo "   local copy is itself unusable — refusing to upload it"
    failed=1
    continue
  fi

  if [ "$CHECK_ONLY" = true ]; then
    echo "   --check: would upload the local copy"
    failed=1
    continue
  fi

  code="$(curl -sS -u "token-auth:$TOKEN" -X PUT --upload-file "$local_entry" \
    -o "$tmp/$key.put" -w '%{http_code}' "$CACHE_URL/$key")"
  echo "   PUT:    http=$code"
  case "$code" in
    2*) ;;
    401 | 403)
      echo "           rejected — the token is probably readonly; a cache:readwrite token is needed"
      failed=1
      continue
      ;;
    409 | 412)
      echo "           rejected — the server treats entries as immutable, so it cannot be"
      echo "           overwritten; ask BuildFetch to evict $key instead"
      failed=1
      continue
      ;;
    *)
      echo "           unexpected status; body: $(head -c 200 "$tmp/$key.put" 2>/dev/null)"
      failed=1
      continue
      ;;
  esac

  # Never trust the PUT status alone — the whole bug is a server that reports success while storing
  # something short. Re-read and re-inflate.
  read -r status bytes inflated <<<"$(fetch_remote "$key")"
  printf '   verify: %-9s bytes=%-9s inflated=%s\n' "$status" "$bytes" "$inflated"
  if [ "$status" = "ok" ]; then
    echo "   repaired"
  else
    echo "   STILL BAD after upload — the corruption is in the store, not the transfer;"
    echo "   report this key to BuildFetch (see issue #2824)"
    failed=1
  fi
done

exit "$failed"
