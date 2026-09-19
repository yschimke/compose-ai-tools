#!/usr/bin/env bash
# Self-test for the opencode bootstrap script.
#
# The bootstrap rewrites the user's live opencode.json, so its failure mode is
# a clobbered config: existing keys dropped, invalid JSON written, or the
# managed block landing anywhere but the file it was asked to merge. This
# drives it against synthetic configs in a temp dir and asserts preservation
# both ways — managed keys appear, unmanaged keys survive byte-identical.
#
#   scripts/test-model-board-bootstrap.sh
set -uo pipefail

repo_root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel 2>/dev/null)" \
  || repo_root="$(cd "$(dirname "$0")/.." && pwd)"
BOOT="$repo_root/scripts/model-board-bootstrap.sh"
fail=0

check() {
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf 'ok: %s\n' "$desc"
  else
    printf 'FAIL: %s\n' "$desc" >&2
    fail=1
  fi
}

check "bash syntax" bash -n "$BOOT"
check "executable bit" test -x "$BOOT"
check "--help exits 0" "$BOOT" --help
if "$BOOT" --bogus >/dev/null 2>&1; then
  printf 'FAIL: unknown flag exits 0\n' >&2; fail=1
else
  printf 'ok: unknown flag exits nonzero\n'
fi

tmp="$(mktemp -d)" || exit 1
trap 'rm -rf "$tmp"' EXIT
# The bootstrap only probes for the CLI, never invokes it; stub it so this
# test runs on machines (and CI runners) without opencode installed.
mkdir -p "$tmp/bin"
printf '#!/bin/sh\nexit 0\n' > "$tmp/bin/opencode"
chmod +x "$tmp/bin/opencode"
PATH="$tmp/bin:$PATH"
export PATH

# The installer must be the V2 line: the bare opencode.ai/install URL
# resolves to 1.x (seen live on a Mac mini). Every code mention must be v2.
if grep -n "https://opencode\.ai/install" "$BOOT" | grep -v "v2/install" >/dev/null; then
  printf 'FAIL: bare V1 installer URL present\n' >&2; fail=1
else
  printf 'ok: installer URL is V2-only\n'
fi

# A V1 CLI is refused: the managed config uses V2 agent/command shapes.
mkdir -p "$tmp/v1bin"
printf '#!/bin/sh\necho "opencode version 1.18.31"\n' > "$tmp/v1bin/opencode"
chmod +x "$tmp/v1bin/opencode"
if PATH="$tmp/v1bin:$PATH" "$BOOT" --config "$tmp/cfg.json" --repo "$tmp/repo" >/dev/null 2>&1; then
  printf 'FAIL: V1 CLI accepted\n' >&2; fail=1
else
  printf 'ok: V1 CLI refused\n'
fi

# A V2 CLI proceeds.
mkdir -p "$tmp/v2bin"
printf '#!/bin/sh\necho "opencode v2.0.9"\n' > "$tmp/v2bin/opencode"
chmod +x "$tmp/v2bin/opencode"
if PATH="$tmp/v2bin:$PATH" "$BOOT" --dry-run --config "$tmp/cfg.json" --repo "$tmp/repo" >/dev/null 2>&1; then
  printf 'ok: V2 CLI accepted\n'
else
  printf 'FAIL: V2 CLI refused\n' >&2; fail=1
fi

# Managed keys appear; unmanaged keys survive.
cat > "$tmp/cfg.json" <<'EOF'
{"$schema": "https://opencode.ai",
 "provider": {"google": {"npm": "@ai-sdk/google"}},
 "permissions": [{"action": "shell", "resource": "git push *", "effect": "ask"}]}
EOF
"$BOOT" --config "$tmp/cfg.json" --repo "$tmp/repo" >/dev/null
python3 - "$tmp/cfg.json" "$tmp/repo" <<'EOF'
import json, sys
cfg = json.load(open(sys.argv[1]))
repo = sys.argv[2]
assert cfg["model"] == "openrouter/z-ai/glm-5.3-flash", cfg.get("model")
assert cfg["agents"]["fix"]["model"] == "openrouter/deepseek/deepseek-v4.1-flash#max"
assert cfg["agents"]["ui"]["model"] == "opencode/muse-spark-1.3-contributor-free"
assert repo in cfg["commands"]["model-board"]["template"], "repo path missing from command template"
assert cfg["mcp"]["openrouter"]["url"] == "https://mcp.openrouter.ai/mcp"
assert cfg["provider"] == {"google": {"npm": "@ai-sdk/google"}}, "provider clobbered"
assert cfg["permissions"] == [{"action": "shell", "resource": "git push *", "effect": "ask"}], "permissions clobbered"
EOF
if [ $? -eq 0 ]; then printf 'ok: merge preserves unmanaged keys\n'; else printf 'FAIL: merge preserves unmanaged keys\n' >&2; fail=1; fi
check "backup written" test -f "$tmp/cfg.json.bak"

# Missing config is created, not crashed on.
"$BOOT" --config "$tmp/fresh/cfg.json" --repo "$tmp/repo" >/dev/null
check "missing config created" python3 -c "import json; json.load(open('$tmp/fresh/cfg.json'))"

# Invalid JSON is refused, not overwritten.
printf '{broken' > "$tmp/bad.json"
if "$BOOT" --config "$tmp/bad.json" --repo "$tmp/repo" >/dev/null 2>&1; then
  printf 'FAIL: invalid JSON accepted\n' >&2; fail=1
else
  printf 'ok: invalid JSON refused\n'
fi
check "invalid JSON left intact" test "$(cat "$tmp/bad.json")" = "{broken"

# --dry-run changes nothing.
before="$(cat "$tmp/cfg.json")"
"$BOOT" --dry-run --config "$tmp/cfg.json" --repo "$tmp/repo" | python3 -c "import json,sys; json.load(sys.stdin)" \
  && [ "$(cat "$tmp/cfg.json")" = "$before" ]
if [ $? -eq 0 ]; then printf 'ok: dry-run is side-effect free\n'; else printf 'FAIL: dry-run is side-effect free\n' >&2; fail=1; fi

exit "$fail"
