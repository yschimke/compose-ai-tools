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
