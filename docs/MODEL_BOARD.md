# Model board

House lineup of LLM models for agent work in this repo, with live costs,
vision support, and the freshest independent coding benchmarks available.
Internal use only — this doc and its scripts are contributor tooling, not
consumer documentation, and must not move under `site/` or the skills bundles.

Last benchmark pull: **2026-09-18/19** via the OpenRouter MCP
(`list-benchmarks`: Artificial Analysis coding index + Design Arena code ELO;
`list-daily-model-rankings`). Re-pull with
[`scripts/model-board.sh`](../scripts/model-board.sh) (`fetch`, then
`report`); this doc records the outcome, the script reproduces it.

## House lineup and roles

| Role | Model (OpenRouter slug) | $/1M in/out | Vision in |
|---|---|---:|---|
| Research / planning | `z-ai/glm-5.3-flash` | 0.09 / 0.30 | text+image+video |
| Implementation (default) | `z-ai/glm-5.3-flash` | 0.09 / 0.30 | text+image+video |
| Implementation (screenshots/video in context) | `google/gemini-3.8-flash` | 0.75 / 3.75 | text+image+file+audio+video |
| Bug fixes / fast loop | `deepseek/deepseek-v4.1-flash` (`#max` routing) | 0.15 / 0.60 | text+image |
| UI work | `meta/muse-spark-1.3-contributor` (Free tier via OpenCode Zen: `$0`) | 0.10 / 0.20 | text+image+file+audio+video |
| Cheap verifier / second opinion | `openai/gpt-5.6-luna` | 0.20 / 1.20 | text+image+file |
| Zero-cost triage | `qwen/qwen3.8-27b` (`:free` endpoints) | 0 / 0 | text+image+video |

Deliberately **not** in the lineup: `deepseek-v4-pro-0813` and full `glm-5.3`
are both `text->text` — they go blind the moment a preview PNG enters context —
and they score below GLM-Flash on coding anyway (see below). Claude Opus/Fable
(81.6/78.0 coding) are last-resort only at $5–10/$25–50 per 1M.

No model here outputs images — all are `->text`. Image "output" in this repo is
the preview PNG rendered by the plugin/CLI/daemon, never the LLM.

## Benchmarks (2026-09-18/19)

Artificial Analysis coding index, top relevant rows (composite incl. SciCode,
Terminal-Bench; per-model effort in brackets):

| Model | Coding | Intelligence | Agentic |
|---|---:|---:|---:|
| Claude Fable 5.1 (max) | 81.6 | 53.4 | 58.0 |
| GPT-5.6 Sol (xhigh) | 78.3 | 44.1 | 47.8 |
| Claude Opus 5 (max) | 78.0 | 50.7 | 56.2 |
| **Gemini 3.8 Flash (high)** | **76.3** | 41.2 | 41.1 |
| Kimi K3 (max) | 76.2 | 43.8 | 50.6 |
| Qwen3.8 Max (0902) | 76.2 | 45.4 | 56.1 |
| GLM-5.3 full (max) | 74.8 | 44.9 | 53.4 |
| Muse Spark 1.2 (xhigh) | 72.2 | 39.8 | 44.0 |
| **GLM-5.3-Flash** | **71.5** | 41.9 | **51.2** |
| GPT-5.6 Luna (max) | 71.4 | 37.5 | 42.7 |
| DeepSeek V4 Flash 0731 | 69.1 | 34.5 | 41.7 |
| **DeepSeek V4 Pro 0813** | **68.8** | 36.3 | 42.3 |
| Qwen3.8 27B (xhigh) | 68.1 | 33.9 | 46.5 |

Notable gaps: **DeepSeek V4.1 Flash has no AA coding score yet** (only
Intelligence 39.5 — too new; predecessor 0731 managed 69.1). **Muse Spark 1.3
has no AA indices yet** (only 1.1/1.2: 71.3/72.2).

Design Arena, code category (human preference ELO, 2026-09-19):

| Model | ELO | Rank |
|---|---:|---:|
| Kimi K3 | 1386 | 1 |
| **Muse Spark 1.3 (max)** | **1373** | **2** |
| Claude Fable 5.1 | 1346 | 4 |
| GLM-5.3 full | 1329 | 6 |
| **Gemini 3.8 Flash** | **1324** | **8** |
| **GLM-5.3-Flash** | **1298** | 15 |

Muse Spark 1.3 is also #1 in `uicomponent` (1382), `website` (1364) and `3d`
(1431) — the UI pick. GLM-Flash's `uicomponent` (1337, #8) is respectable for
a $0.09 model.

Readings: Gemini 3.8 Flash is the strongest correctness-per-dollar model with
vision; GLM-Flash is the value agentic loop (agentic 51.2 beats Gemini's 41.1
and Kimi K3's 50.6 at ~1/8th the price); DeepSeek is the weakest coding scorer
in the lineup — keep it for bulk/off-peak loops, not default implementation.

## Discounts and pricing traps

- DeepSeek V4.1 Flash has **time-of-day pricing**: base $0.15/$0.60 on
  weekends and weekday 00:00–01:00, 04:00–06:00, 10:00–24:00 UTC; **2×**
  ($0.30/$1.20) weekday 01:00–04:00 and 06:00–10:00 UTC. Schedule bulk runs
  off-peak.
- `:free` endpoints (`qwen3.8-27b:free`, `deepseek-v4-flash-0731:free`) for
  zero-cost sweeps; `:batch` variants roughly halve price for non-interactive
  work.
- OpenRouter `low`/`high`/`max` variants are **provider-routing tiers**
  (throughput/latency), not model sizes. Benchmark prices above are at rated
  effort tiers and run ~1.7× base (reasoning tokens bill as output).
- Gemini output costs 5× its input — expensive for long agentic loops.

## opencode setup to match

[`scripts/model-board-bootstrap.sh`](../scripts/model-board-bootstrap.sh)
merges this lineup into `~/.config/opencode/opencode.json` on any machine
(Linux/macOS): root default model, one agent per role above (model pins with
`#variant` where it matters — DeepSeek `#max`), the OpenRouter MCP server
entry, and a `/model-board` command that re-runs the refresh. It preserves
existing keys (providers, credentials) and never writes secrets.

New machine (macOS) one-liner — clones this repo if needed, then bootstraps:

```sh
test -d ~/src/compose-ai-tools || git clone https://github.com/yschimke/compose-ai-tools ~/src/compose-ai-tools
~/src/compose-ai-tools/scripts/model-board-bootstrap.sh
```

Then in the OpenCode TUI run `/mcps`, select **openrouter**, approve OAuth
(dedicated 7-day key, $10 default cap), and restart the service if it was
already running (`opencode service restart`). The script prints these steps
itself with `--dry-run` available to preview the merge first.
