# Agent entrypoints — who reads what, and what it costs

More than one coding agent works this repository. Each resolves its instructions
from a different file, by a different mechanism, and **none of them follows an
ordinary markdown link**. That single fact decides how the repository's guidance is
laid out, so it is written down here with the resolution order verified against each
tool's own documentation rather than assumed.

Companion gate: [`scripts/check-agent-entrypoints.sh`](../scripts/check-agent-entrypoints.sh)
(self-test: `scripts/test-check-agent-entrypoints.sh`, both run by the
[`Agent Entrypoints`](../.github/workflows/agent-entrypoints.yml) workflow on every
PR and push).

## Resolution order, verified

| Agent | Reads | Import mechanism | Reads the others? |
| --- | --- | --- | --- |
| **Claude Code** | `CLAUDE.md` (+ `./.claude/CLAUDE.md`, ancestors, `.claude/rules/`) | `@path` imports, expanded into context at launch, max 4 hops. Import parsing skips code spans and fenced blocks. | **No.** Claude Code reads `CLAUDE.md`, not `AGENTS.md`. Anthropic's own recommendation for a repo that has both is a `CLAUDE.md` whose first line is `@AGENTS.md`. |
| **Codex CLI** | `AGENTS.md` — `~/.codex/`, then git root, then each directory down to cwd (`AGENTS.override.md` takes precedence at each level) | **None.** Instruction composition is directory-walk concatenation only; an `@include` directive is an open feature request ([openai/codex#17401](https://github.com/openai/codex/issues/17401)). | Not by default. `project_doc_fallback_filenames` can add `CLAUDE.md`, but it lives in the *user's* `~/.codex/config.toml` — it cannot be committed, so a repository cannot rely on it. |
| **Gemini CLI** | `GEMINI.md` (name configurable via `context.fileName`) | `@path.md` memory imports, inlined by the import processor. `.md` only; a non-`.md` target warns and fails. | No. |
| **GitHub Copilot** | `.github/copilot-instructions.md`, **and** root `AGENTS.md` natively (nearest file in the directory tree) | None needed for `AGENTS.md`; `copilot-instructions.md` holds Copilot-specific additions. | Reads `AGENTS.md`; does not read `CLAUDE.md`. |

The load-bearing consequence: **root `AGENTS.md` is the only file every agent can
reach, and Codex can only reach it if it is self-contained.** A rule that lives
behind a link, or in a file only one agent imports, is invisible to the rest.

There is a second, harder limit. Codex embeds at most `project_doc_max_bytes` of
`AGENTS.md` into its first-turn instructions — **32768 bytes by default** — and
stops adding content once the budget is spent. It does not warn; the tail is simply
gone. So root `AGENTS.md` has a hard size ceiling, which is why detail lives in
[`docs/AGENTS.md`](AGENTS.md) and gets read on demand instead.

## What this cost before it was fixed

Measured at the commit before this document existed. The repository had no root
`AGENTS.md`; `CLAUDE.md` carried the invariants inline, and `GEMINI.md` was a single
markdown link.

| Agent | Entrypoint | Bytes loaded per turn | CI-enforced invariants reachable |
| --- | --- | --- | --- |
| Claude Code | `CLAUDE.md` (11,088 B) | 11,088 | **5 of 5** |
| Codex CLI | — (no `AGENTS.md` existed) | 0 | **0 of 5** |
| Gemini CLI | `GEMINI.md` (112 B — a link, not an import) | 112 | **0 of 5** |
| Copilot | — (neither file existed) | 0 | **0 of 5** |

Three of the four agents operated here with no repository guidance at all, and the
`No Agent Attribution` gate was the only thing standing between that and a bad
commit on a branch. Note what that means for a Codex or Copilot session: it does not
*deviate* visibly from house style — it goes red on a gate whose rule it was never
shown.

One more thing was in the way, and it is worth recording because it is invisible
from the outside: **`.claude/` was gitignored wholesale.** The harness looks for
`.claude/skills/steward/SKILL.md` before acting on a CI failure or a review comment,
but a skill written there could never have been committed — `.claude/hooks/` and
`.claude/settings.json` were tracked only because someone force-added them. The
ignore is now scoped so the shared agent config (hooks, settings, skills) is checked
in and per-machine session state still is not.

**And the obvious fix would not have worked.** Pointing Codex straight at the
existing `docs/AGENTS.md` (74,203 bytes) would have exceeded the 32 KiB budget by
more than double: only the first 44% would load, and the cut lands between
`## State seams` (byte 30,188) and `## Git conventions` (byte 34,450). Every Git
convention, the entire PR workflow, and all of `## Important constraints` sit past
the cut. A symlink from `AGENTS.md` to `docs/AGENTS.md` would have looked like a fix
and delivered **0 of 5** invariants.

## What it costs now

Run `scripts/check-agent-entrypoints.sh --report` for the live table. At the time of
writing:

| Agent | Entrypoint | Bytes per turn | ~tokens | Invariants |
| --- | --- | --- | --- | --- |
| Claude Code | `CLAUDE.md` + imported `AGENTS.md` | 9,272 | ~2,300 | 5 of 5 |
| Codex CLI | `AGENTS.md` | 7,497 | ~1,900 | 5 of 5 |
| Gemini CLI | `GEMINI.md` + imported `AGENTS.md` | 7,915 | ~2,000 | 5 of 5 |
| Copilot | `.github/copilot-instructions.md` + `AGENTS.md` | 8,029 | ~2,000 | 5 of 5 |

`~tokens` is bytes/4, the usual rough estimate, and is paid on every turn.
`docs/AGENTS.md` (~73 KB, ~18,000 tokens) is **not** in this table: no agent loads it
automatically, and it should stay that way.

Claude Code's per-turn cost went *down* — from 11,088 bytes of invariants plus PR
workflow to 9,272 bytes of the same rules plus a genuinely Claude-only section —
while the other three went from nothing to complete coverage.

## The layout, and why

| Tier | Home | Audience |
| --- | --- | --- |
| CI-enforced invariants + normative PR workflow | root [`AGENTS.md`](../AGENTS.md) | every agent, every turn |
| Architecture, commands, constraints, rationale, worked examples | [`docs/AGENTS.md`](AGENTS.md) and the rest of `docs/` | read on demand, cited from `AGENTS.md` |
| Harness mechanics — `subscribe_pr_activity`, `send_later` check-ins, skill triggering | [`CLAUDE.md`](../CLAUDE.md) and [`.claude/skills/`](../.claude/skills/) | Claude Code only |

Two rules keep it honest:

- **Every entrypoint imports; none restates.** `CLAUDE.md` and `GEMINI.md` open with
  an `@AGENTS.md` import and then carry only what is specific to that agent.
  `.github/copilot-instructions.md` points at `AGENTS.md`, which Copilot reads
  natively. The gate rejects a markdown link in place of an import, and rejects a
  backticked `@AGENTS.md` (both CLIs skip code spans, so a backticked mention is
  documentation, not an import).
- **No rule has two homes.** Each CI-enforced invariant carries a one-per-file HTML
  comment anchor in `AGENTS.md` naming its id — see the `INVARIANTS` list in
  [`scripts/check-agent-entrypoints.sh`](../scripts/check-agent-entrypoints.sh) for
  the ids and the exact anchor format. The gate fails if an invariant has no anchor,
  has two, or if an anchor appears in any other tracked markdown file. Pointer files
  are additionally capped at 4 KiB so a second copy of the rules cannot creep back
  in.

**Adding a CI gate means adding its invariant here too.** A gate that enforces a
rule no agent can see is a gate that fails PRs for reasons the author cannot act on.

## What is not verified here

- The resolution orders above come from each tool's own documentation, cross-checked
  in August 2026, and from the gate's structural checks. They are **not** verified by
  running each agent end to end against this repository — that is the remaining
  acceptance item on [#4590](https://github.com/yschimke/compose-ai-tools/issues/4590)
  and needs a maintainer to dispatch one session of each kind on a small PR and
  confirm none of them trips `No Agent Attribution` or the branch-prefix rule.
- The Codex review workflows (`codex-pr-review.yml`, `codex-pr-review-blessed.yml`)
  currently have their auto-triggers **disabled** pending the review tooling, so
  Codex is not in fact reviewing PRs here today. That lowers the urgency of the
  finding above; it does not change it, because a Codex CLI session run by hand in a
  clone resolves instructions the same way.
