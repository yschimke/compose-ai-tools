# Token usage

Rough estimates for how much LLM context an agent burns when driving
compose-ai-tools through the documented audit recipes. The intent is to
give skill authors and integrators a budget to design against — exact
counts depend on the tokenizer, the daemon's wired producers, and the
preview under inspection.

Conversions used throughout: ~3.5 chars/token for dense JSON,
~4 chars/token for prose, ~1500 input tokens per UI PNG read at
typical preview dimensions. Out-token estimates include both the
LLM-emitted tool call (~30–80 tok) and a short reasoning/comment
paragraph (~150 tok).

## Fixed per-session overhead

Skill instructions are paged in lazily. The first time the agent enters
a section, the corresponding doc lands in context.

| Doc | Bytes | Tokens (in) | Loaded when |
|---|---:|---:|---|
| `compose-preview-review/SKILL.md` | 4 178 | ~1 050 | review skill triggered |
| `compose-preview-review/references/agent-audits.md` | 22 032 | ~5 510 | first audit |
| `compose-preview/SKILL.md` | 10 019 | ~2 510 | paired skill referenced |
| `compose-preview/references/mcp.md` | 9 571 | ~2 390 | first MCP call |
| `compose-preview/references/data-products.md` | 7 357 | ~1 840 | first `get_preview_data` |

Practical baselines:

- **CLI-only audit** (a11y, `show`): ~6.5 k input tokens before the
  first query.
- **MCP-driven audit** (any `get_preview_data` / `record_preview`):
  ~13.3 k input tokens before the first query.

These are paid once per agent session and benefit fully from prompt
caching on subsequent turns.

## Per-audit query cost

Marginal cost of running each audit's suggested query once, on top of
the baseline above. Numbers come from the canonical example payloads
in `agent-audits.md` — real responses run 1.5–3× larger because the
doc snippets show one preview / one finding / one resource reference.

| # | Audit | Suggested query | Resp. JSON (chars → tok) | PNG reads | Marginal in | Marginal out |
|---:|---|---|---:|---:|---:|---:|
| 1 | Accessibility | `compose-preview a11y --filter … --json --fail-on warnings` | 372 → 105 | 1× annotated | ~1 600 | ~180 |
| 2 | Localisation | `render_preview` + `get_preview_data text/strings` + `resources/used` | 172 + 442 + 372 → 280 | 0–1× rendered | ~280 (no PNG) / ~1 800 (with PNG) | ~210 |
| 3 | Wear / round-device | `get_preview_data render/deviceClip` | 175 → 50 | 1× rendered | ~1 550 | ~180 |
| 4 | Text overflow / readability | `get_preview_data text/strings` | 471 → 135 | 0–1× | ~135 / ~1 635 | ~180 |
| 5 | Resource & theme provenance | `get_preview_data resources/used` | 610 → 175 | 0 | ~175 | ~180 |
| 6 | Visual regression | `compose-preview show --filter … --json --changed-only` | 376 / changed → 110 each | 2× (PNG + diff) per changed | ~3 100 per changed preview | ~180 |
| 7 | Recomposition | `list_data_products` + `get_preview_data compose/recomposition` | ~3–5 KB + 240 → 970–1 500 | 0 | ~1 000–1 600 | ~200 |
| 8 | State restoration / lifecycle | `record_preview` (4 events: click + pause/resume + probe) | req 396 + resp ~1.5–2 KB → 470–600 | 0–N frames | ~600, +1 500 per frame read | ~280 |
| 9 | A11y-driven interaction | `record_preview` (click + probe) ± `a11y/hierarchy` lookup | req 331 + resp ~600 + hier ~0.5–2 KB | 0 | ~330–800 | ~250 |
| 10 | Failure triage | `get_preview_data test/failure` | 680 → 195 | 0 | ~195 | ~180 |

## Per-audit grand total (single preview)

Baseline + one run of the suggested query, including the relevant PNG
read where the audit calls for one. Visual regression assumes 5 changed
previews.

| Audit | In | Out |
|---|---:|---:|
| 1. Accessibility (CLI) | ~8.1 k | ~180 |
| 2. Localisation (MCP, with PNG) | ~15.1 k | ~210 |
| 3. Wear round-device (MCP) | ~14.9 k | ~180 |
| 4. Text overflow (MCP) | ~13.5 k | ~180 |
| 5. Resource provenance (MCP) | ~13.5 k | ~180 |
| 6. Visual regression (CLI), 5 changed | ~22.0 k | ~250 |
| 7. Recomposition (MCP) | ~14.5 k | ~200 |
| 8. State restoration (MCP, 1 frame) | ~15.4 k | ~280 |
| 9. A11y interaction (MCP) | ~14.0 k | ~250 |
| 10. Failure triage (MCP) | ~13.5 k | ~180 |

## Caveats

- Example payloads in `agent-audits.md` are deliberately minimal.
  `text/strings`, `resources/used`, and `a11y/hierarchy` on a non-toy
  preview routinely run 2–5× larger because of more nodes / refs.
- `list_data_products` size depends on how many producers the daemon
  has wired. A modern daemon with the full extension set advertised
  is ~5–8 KB (~1.5–2.3 k tokens).
- Visual regression scales linearly per changed preview (~3 k input
  tokens each, dominated by the two PNG reads). A UI PR with 10
  changed previews is ~30 k input on top of baseline.
- `record_preview` response size scales with `fps × duration`: each
  captured frame adds a `frames[]` entry plus optional metadata. The
  APNG/MP4 capture path keeps per-frame JSON small; embedded-PNG debug
  mode grows fast.
- These numbers count tokens crossing the LLM/tool boundary, not
  Anthropic billing on cached prefixes. With prompt caching the
  ~13 k MCP baseline is paid once per session and replayed from cache
  on later turns.
