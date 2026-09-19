#!/usr/bin/env bash
# Bootstrap an opencode setup matching docs/MODEL_BOARD.md on any machine
# (Linux/macOS). Merges the house lineup into the global opencode.json:
# root default model, one agent per role, the OpenRouter MCP entry, and a
# `/model-board` refresh command.
#
# Existing keys (providers, credentials, permissions, …) are preserved; only
# the managed keys are overwritten. Secrets are never written — OpenRouter
# auth stays an interactive `/mcps` OAuth step owned by the user.
#
# Usage:
#   scripts/model-board-bootstrap.sh [--repo <checkout>] [--config <file>] [--dry-run] [--install]
#
#   --repo    compose-ai-tools checkout the /model-board command points at
#             (default: the checkout containing this script)
#   --config  opencode.json to merge into
#             (default: $XDG_CONFIG_HOME/opencode/opencode.json or
#             ~/.config/opencode/opencode.json)
#   --dry-run print the merged config, change nothing
#   --install install the opencode CLI first if it is missing
#             (official installer; without it the script only prints the command)
#
# Afterwards: `opencode service restart`, then in the TUI `/mcps` → openrouter
# → approve OAuth.
set -uo pipefail

REPO=""
CONFIG=""
DRY_RUN=0
INSTALL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --install) INSTALL=1; shift ;;
    -h|--help)
      sed -n '2,/^set /p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

if ! command -v python3 >/dev/null 2>&1; then
  printf 'python3 is required (macOS: xcode-select --install)\n' >&2
  exit 2
fi

if [ -z "$REPO" ]; then
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
if [ -z "$CONFIG" ]; then
  CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/opencode/opencode.json"
fi

# V2 only: the managed config uses V2 agent/command shapes, and the V1
# installer at opencode.ai/install resolves to the 1.x line.
V2_INSTALL="curl -fsSL https://opencode.ai/v2/install | bash"

if ! command -v opencode >/dev/null 2>&1; then
  if [ "$INSTALL" -eq 1 ]; then
    sh -c "$V2_INSTALL"
  else
    printf 'opencode CLI not found; install V2 first:\n' >&2
    printf '  %s\n' "$V2_INSTALL" >&2
    printf 'or re-run with --install\n' >&2
    exit 2
  fi
else
  ver="$(opencode --version 2>/dev/null | grep -o '[0-9][0-9.]*' | head -1)"
  case "$ver" in
    2*|"")
      # Empty means unparseable output: proceed, the merge itself is validated.
      ;;
    *)
      printf 'opencode %s is V1; this setup needs V2 (config schema differs):\n' "${ver:-unknown}" >&2
      printf '  %s\n' "$V2_INSTALL" >&2
      exit 2
      ;;
  esac
fi

if [ "$DRY_RUN" -eq 1 ]; then
  DRY=1
else
  mkdir -p "$(dirname "$CONFIG")"
  if [ ! -f "$CONFIG" ]; then
    printf '{\n  "$schema": "https://opencode.ai"\n}\n' > "$CONFIG"
  fi
  cp "$CONFIG" "$CONFIG.bak"
  DRY=0
fi

python3 - "$CONFIG" "$REPO" "$DRY" <<'PYEOF' || exit 1
import json, os, sys

path, repo, dry = sys.argv[1], sys.argv[2], sys.argv[3] == "1"

TEMPLATE = (
    "In the compose-ai-tools checkout at {repo}: refresh the model boards and compare "
    "against docs/MODEL_BOARD.md. Run `scripts/model-board.sh fetch` with OPENROUTER_API_KEY "
    "in the environment (key from the OpenRouter dashboard, never committed) saving a snapshot "
    "to /tmp, then `scripts/model-board.sh report --snapshot <file> --top 15`. Summarize only "
    "what changed versus the doc tables: new models scoring above the house lineup, price moves, "
    "fresh vision-capable contenders. Update the doc tables and pull date if anything material changed."
).format(repo=repo)

MANAGED_AGENTS = {
    "plan": {
        "description": "Research and planning on GLM-5.3-Flash (cheap, vision input).",
        "mode": "all",
        "model": "openrouter/z-ai/glm-5.3-flash",
        "system": "Research and plan. Prefer reading code and docs over changing them; present findings with file references.",
    },
    "implement": {
        "description": "Default implementation on GLM-5.3-Flash.",
        "mode": "all",
        "model": "openrouter/z-ai/glm-5.3-flash",
        "system": "Implement the requested change with minimal diffs. Verify with the repo's own checks.",
    },
    "implement-visual": {
        "description": "Implementation with screenshots or video in context (Gemini 3.8 Flash).",
        "mode": "all",
        "model": "openrouter/google/gemini-3.8-flash",
        "system": "Screenshots or preview renders are in context. Read them before and after each change; iterate until the pixels match the intent.",
    },
    "fix": {
        "description": "Fast bug-fix loop on DeepSeek V4.1 Flash (max routing).",
        "mode": "all",
        "model": "openrouter/deepseek/deepseek-v4.1-flash#max",
        "system": "Fix the reported failure with the smallest correct change. Reproduce first when a reproduction exists.",
    },
    "ui": {
        "description": "UI work on Muse Spark 1.3 (free tier, top design-arena ranks).",
        "mode": "all",
        "model": "opencode/muse-spark-1.3-contributor-free",
        "system": "UI work: iterate against preview renders. Judge taste against the rendered pixels, not the code shape.",
    },
}

if dry and not os.path.exists(path):
    cfg = {}
else:
    with open(path) as f:
        cfg = json.load(f)
if not cfg:
    cfg = {"$schema": "https://opencode.ai"}

cfg["model"] = "openrouter/z-ai/glm-5.3-flash"
agents = cfg.setdefault("agents", {})
agents.update(MANAGED_AGENTS)
commands = cfg.setdefault("commands", {})
commands["model-board"] = {
    "description": "Refresh coding-leaderboard snapshot and compare against the house lineup.",
    "template": TEMPLATE,
}
mcp = cfg.setdefault("mcp", {})
mcp.setdefault("openrouter", {
    "type": "remote",
    "url": "https://mcp.openrouter.ai/mcp",
    "enabled": True,
})

if dry:
    print(json.dumps(cfg, indent=2))
else:
    with open(path, "w") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    print("updated " + path + " (backup at " + path + ".bak)")
PYEOF

if [ "$DRY_RUN" -eq 0 ]; then
cat <<'EOF'
Next steps:
  1. opencode service restart
  2. In the TUI: /mcps -> openrouter -> approve OAuth (7-day key, $10 cap)
  3. Refresh the boards any time: /model-board
EOF
fi
