#!/usr/bin/env bash
# Model-board refresh: re-pull the coding leaderboards behind docs/MODEL_BOARD.md
# and render a markdown report against the house lineup.
#
# Nothing here runs evals. All scores come from other people's work, fetched
# live from OpenRouter's Data API (Artificial Analysis indices + Design Arena
# ELO — the same datasets the OpenRouter MCP `list-benchmarks` tool serves).
#
# Usage:
#   OPENROUTER_API_KEY=sk-or-... scripts/model-board.sh fetch [--out snapshot.json]
#   scripts/model-board.sh report [--snapshot snapshot.json] [--top 15]
#
# `fetch` needs an API key (any valid OpenRouter key; inference keys work).
# `report` is offline: it reads a snapshot from a file or stdin. Never commit
# snapshots — they go stale within weeks; the doc records the outcome.
set -uo pipefail

# House lineup, kept in sync with docs/MODEL_BOARD.md. `aa` is the Artificial
# Analysis permaslug prefix (dated upstream); empty means "unscored, match by
# display name only".
LINEUP="z-ai/glm-5.3-flash|Research/planning + default implementation
google/gemini-3.8-flash|Implementation with screenshots/video
deepseek/deepseek-v4.1-flash|Bug fixes / fast loop
meta/muse-spark-1.3-contributor|UI work (free tier)
openai/gpt-5.6-luna|Cheap verifier
qwen/qwen3.8-27b|Zero-cost triage
deepseek/deepseek-v4-pro-0813|Text-only implementation (no vision)
z-ai/glm-5.3|Text-only planning/implementation (no vision)"

usage() {
  sed -n '2,/^set /p' "$0" | sed 's/^# \?//'
  exit "${1:-0}"
}

fetch() {
  local out=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --out) out="$2"; shift 2 ;;
      -h|--help) usage 0 ;;
      *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
  done
  if [ -z "${OPENROUTER_API_KEY:-}" ]; then
    printf 'OPENROUTER_API_KEY is not set (any valid OpenRouter key works)\n' >&2
    exit 2
  fi
  python3 - "$out" <<'EOF'
import json, sys, urllib.request, datetime
key = __import__("os").environ["OPENROUTER_API_KEY"]
out = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else ""
def get(path):
    req = urllib.request.Request("https://openrouter.ai" + path,
                                 headers={"Authorization": "Bearer " + key})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)
snap = {
    "fetched_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "aa_coding": get("/api/v1/benchmarks?source=artificial-analysis&task_type=coding&max_results=60"),
    "arena": get("/api/v1/benchmarks?source=design-arena&arena=models&category=codecategories&max_results=60"),
    "models": get("/api/v1/models"),
}
text = json.dumps(snap)
if out:
    open(out, "w").write(text + "\n")
    print("wrote " + out, file=sys.stderr)
else:
    print(text)
EOF
}

report() {
  local snap="" top=15 tmp=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --snapshot) snap="$2"; shift 2 ;;
      --top) top="$2"; shift 2 ;;
      -h|--help) usage 0 ;;
      *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
  done
  if [ -z "$snap" ]; then
    tmp="$(mktemp)" || exit 1
    trap "rm -f $tmp" EXIT
    cat > "$tmp"
    snap="$tmp"
  fi
  python3 - "$snap" "$top" "$LINEUP" <<'EOF'
import json, sys, re
def price(prompt):
    if prompt is None:
        return None
    s = str(prompt)
    try:
        return float(s) * 1e6 if "M" not in s else float(s.replace("$", "").split("/")[0])
    except Exception:
        return None
def per_m(prompt):
    p = price(prompt)
    return ("$%.2f" % p).rstrip("0").rstrip(".") if p is not None else "?"
def base(slug):
    # Collapse dated variant suffixes so monthly pins resolve to house slugs:
    # "-20260826" (YYYYMMDD), "-202609" (YYYYMM), "-0813" (MMDD).
    return re.sub(r"-\d{4}(\d{2}(\d{2})?)?$", "", slug)
def match(rows, slug):
    for r in rows:
        ps = r.get("model_permaslug", "")
        if ps == slug or ps.startswith(slug + "-"):
            return r
    want = base(slug)
    for r in rows:
        ps = r.get("model_permaslug", "")
        got = base(ps)
        if got == want or got.startswith(want + "-") or want.startswith(got + "-"):
            return r
    return None
snap = json.load(open(sys.argv[1]))
top = int(sys.argv[2])
lineup = [line.split("|") for line in sys.argv[3].strip().split("\n")]
slugs = [l[0] for l in lineup]
aa = snap["aa_coding"]["data"]
arena = snap["arena"]["data"]
print("## AA coding index (as of %s)" % snap["aa_coding"].get("meta", {}).get("as_of", "?"))
print("| # | Model | Coding | $/1M in |")
print("|---:|---|---:|---:|")
for i, r in enumerate(aa, 1):
    ps = r["model_permaslug"]
    if i > top and not any(ps == s or ps.startswith(s + "-") or ps.startswith(s + ":") for s in slugs):
        continue
    print("| %d | %s | %s | %s |" % (i, r["display_name"], r["coding_index"], per_m((r.get("pricing") or {}).get("prompt"))))
print()
print("## Design Arena code ELO (as of %s)" % snap["arena"].get("meta", {}).get("as_of", "?"))
print("| # | Model | ELO | Win% |")
print("|---:|---|---:|---:|")
for i, r in enumerate(arena[:top], 1):
    print("| %d | %s | %s | %s |" % (i, r["display_name"], r.get("elo"), r.get("win_rate")))
print()
print("## Lineup scorecard")
print("| Role | Slug | AA coding | Arena ELO | Flags |")
print("|---|---|---:|---:|---|")
for slug, role in lineup:
    a = match(aa, slug)
    e = match(arena, slug)
    flags = []
    if a is None:
        flags.append("UNSCORDED on AA coding (too new?)")
    if e is None:
        flags.append("no arena code row")
    print("| %s | `%s` | %s | %s | %s |" % (role, slug,
        a["coding_index"] if a else "—", e.get("elo") if e else "—", "; ".join(flags)))
print()
print("## Value check: cheapest models scoring >= 70 AA coding")
def cost(r):
    p = price((r.get("pricing") or {}).get("prompt"))
    return p if p is not None else float("inf")
for r in sorted([r for r in aa if (r["coding_index"] or 0) >= 70], key=cost)[:8]:
    print("- %s: coding %s at %s/M in" % (r["display_name"], r["coding_index"], per_m((r.get("pricing") or {}).get("prompt"))))
EOF
}

cmd="${1:-}"
case "$cmd" in
  fetch) shift; fetch "$@" ;;
  report) shift; report "$@" ;;
  -h|--help|help) usage 0 ;;
  *) usage 2 ;;
esac
