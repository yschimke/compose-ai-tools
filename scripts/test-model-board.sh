#!/usr/bin/env bash
# Self-test for the model-board refresh script.
#
# The script's failure mode is a quiet one: a renderer that stops matching
# permaslugs still prints tidy tables and still exits 0, while the lineup
# scorecard silently degrades to UNSCORED everywhere. So this drives `report`
# against a synthetic snapshot and asserts BOTH directions — dated permaslugs
# resolve to house slugs, and genuinely absent models are flagged, not hidden.
#
#   scripts/test-model-board.sh
set -uo pipefail

repo_root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel 2>/dev/null)" \
  || repo_root="$(cd "$(dirname "$0")/.." && pwd)"
BOARD="$repo_root/scripts/model-board.sh"
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

check "bash syntax" bash -n "$BOARD"
check "executable bit" test -x "$BOARD"
check "--help exits 0" "$BOARD" --help
if "$BOARD" frobnicate >/dev/null 2>&1; then
  printf 'FAIL: unknown subcommand exits 0\n' >&2; fail=1
else
  printf 'ok: unknown subcommand exits nonzero\n'
fi
if "$BOARD" report --bogus >/dev/null 2>&1; then
  printf 'FAIL: unknown flag exits 0\n' >&2; fail=1
else
  printf 'ok: unknown flag exits nonzero\n'
fi
# fetch must refuse to run without a key rather than curling anonymously.
if env -u OPENROUTER_API_KEY "$BOARD" fetch >/dev/null 2>&1; then
  printf 'FAIL: fetch without OPENROUTER_API_KEY exits 0\n' >&2; fail=1
else
  printf 'ok: fetch without OPENROUTER_API_KEY refuses\n'
fi

tmp="$(mktemp -d)" || exit 1
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/snap.json" <<'EOF'
{"aa_coding": {"meta": {"as_of": "2026-09-18T00:00:00Z"}, "data": [
  {"model_permaslug": "anthropic/claude-fable-5.1-20260831", "display_name": "Claude Fable 5.1", "coding_index": 81.6, "pricing": {"prompt": "0.000005"}},
  {"model_permaslug": "z-ai/glm-5.3-flash-20260826", "display_name": "GLM-5.3-Flash", "coding_index": 71.5, "pricing": {"prompt": "0.00000015"}},
  {"model_permaslug": "deepseek/deepseek-v4-flash-20260731", "display_name": "DeepSeek V4 Flash 0731", "coding_index": 69.1, "pricing": {"prompt": "0"}},
  {"model_permaslug": "deepseek/deepseek-v4-pro-20260813", "display_name": "DeepSeek V4 Pro 0813", "coding_index": 68.8, "pricing": {"prompt": "0.00000132"}}
]},
"arena": {"meta": {"as_of": "2026-09-19T00:00:00Z"}, "data": [
  {"model_permaslug": "meta/muse-spark-1.3-20260902", "display_name": "Muse Spark 1.3 Max", "elo": 1373, "win_rate": 59.2},
  {"model_permaslug": "z-ai/glm-5.3-flash-20260826", "display_name": "GLM-5.3-Flash", "elo": 1298, "win_rate": 49.7},
  {"model_permaslug": "google/gemini-3.8-flash-20260902", "display_name": "Gemini 3.8 Flash", "elo": 1324, "win_rate": 53.0}
]},
"models": {"data": []}}
EOF

out="$("$BOARD" report --snapshot "$tmp/snap.json" --top 1)"
# Dated permaslug resolves: GLM-Flash is rank 2 but must appear with --top 1.
case "$out" in
  *"GLM-5.3-Flash"*"71.5"*) printf 'ok: dated permaslug resolves into top-N\n' ;;
  *) printf 'FAIL: dated permaslug missing from report\n' >&2; fail=1 ;;
esac
# Scorecard carries the lineup slug with its arena ELO.
case "$out" in
  *'`z-ai/glm-5.3-flash`'*1298*) printf 'ok: scorecard row carries arena ELO\n' ;;
  *) printf 'FAIL: scorecard row wrong\n' >&2; fail=1 ;;
esac
# MMDD house slug resolves against a YYYYMMDD board pin.
case "$out" in
  *'`deepseek/deepseek-v4-pro-0813`'*68.8*) printf 'ok: MMDD slug resolves to dated pin\n' ;;
  *) printf 'FAIL: MMDD slug did not resolve\n' >&2; fail=1 ;;
esac
# Contributor-suffixed slug resolves to the dated arena row.
case "$out" in
  *'`meta/muse-spark-1.3-contributor`'*1373*) printf 'ok: contributor slug resolves to arena row\n' ;;
  *) printf 'FAIL: contributor slug did not resolve\n' >&2; fail=1 ;;
esac
# Genuinely absent models are flagged, not silently dropped: V4.1 Flash is in
# the house lineup but in neither board of this fixture.
case "$out" in
  *'`deepseek/deepseek-v4.1-flash`'*UNSCORDED*) printf 'ok: unscored lineup model flagged\n' ;;
  *) printf 'FAIL: unscored lineup model not flagged\n' >&2; fail=1 ;;
esac
# Value check only admits >= 70 coding.
case "$out" in
  *"DeepSeek V4 Pro 0813"*) printf 'FAIL: sub-70 model leaked into value check\n' >&2; fail=1 ;;
  *) printf 'ok: value check threshold holds\n' ;;
esac
# stdin path renders the same scorecard.
if "$BOARD" report --top 1 < "$tmp/snap.json" | grep -q 'Lineup scorecard'; then
  printf 'ok: stdin snapshot renders\n'
else
  printf 'FAIL: stdin snapshot broken\n' >&2; fail=1
fi

exit "$fail"
