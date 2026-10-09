# Design guidelines over rendered previews

A model judges rendered `@Preview`s against their catalog's design guidance — the rules a catalog
publishes as `ui-builder.guidelines.json` beside its `ui-builder.policy.json` — and reports each
broken rule with the nodes it is about, so a finding can be drawn on the render.

UI-builder designs already have this check (compose-ui-builder's editor, compose-preview-server's
`ui_builder_check_design`). This brings the same rules and the same wire shapes to previews, in the
tools that render them.

## Placement

| Piece | Where | Layer |
| --- | --- | --- |
| Wire shapes: catalog guidelines, request, verdict, record, subject, evidence | compose-preview-contracts `design-guidelines-protocol` | 0 |
| Engine: batching, prompt, OpenRouter, Jev triage, evidence loop, cache, annotation | `:design-guidelines` (`guidelines/engine`) | 1 |
| `compose-preview guidelines` | `:cli` (`GuidelinesCommand`) | 1 |
| Preview-diff comment on changed previews | `apply` action pipeline (next) | 1 |
| MCP tools over a live session | compose-preview-server `mcp` (next) | 2 |
| Problems-panel findings | compose-preview-vscode (next) | 3 |
| Findings served with a hosted catalog | render-host `ServeHost` + compose-preview-server data product (next) | 1–2 |

The engine opens no socket and reads no Gradle project. What a host can fetch when a model asks for
more is behind `GuidelineEvidenceHost`; the CLI, the MCP server and a CI step each implement it over
what they hold. It cannot depend on compose-ui-builder (layer above, ships distributions), so the
prompt is ported onto the contract types rather than shared.

## Rules

From the module's `build/compose-previews/ui-builder.guidelines.json` (copied there by discovery
when the catalog commits one, #5733), or `--guidelines <file-or-url>`. A catalog with none has no
check. Per subject:

- **Surface.** A preview with a `device` is a `screen`; anything else is a `component` (a button
  sticker, a card). `--surface` overrides. A rule naming surfaces is asked only of those; a rule
  naming none is asked of every subject.
- **Profile.** A rule naming Remote Compose profiles is asked only of a subject targeting one.
- **Scope.** `subject` rules are asked per subject; `set` rules once per batch, across all of it.
- **Pictures.** Visual rules are left out of a subject with no picture.

## Batching

Not one call per preview. Subjects of one surface share a request, up to a budget (default 12
pictures, ~60k input tokens, 16 subjects): the rules are sent once, and `set` rules see the whole
batch. Each subject carries its render (tagged with its subject id), its accessibility nodes (id,
role, label, bounds), and optionally its source. The model names the subject (`s1`, …) in every
verdict and cites node ids, which map back to bounds for overlays; a visual problem no single node
holds comes back as a region (a fraction box on a numbered picture).

## Evidence loop

The first pass carries what is cheap: one render and the nodes the `a11y` pass already produced.

1. **Triage** (optional, `--no-triage` to skip). Jev (`typesafe/jev-1.13`, text only, typed
   probabilities, ~$0.0003) is asked per subject whether a dark-theme render, a large-font render or
   its accessibility nodes would be needed; the host fetches only what it wants above 0.5.
2. **Round 0.** The batch is judged. A rule the model cannot decide is answered `needs_evidence`
   with what it needs (`a11y-hierarchy`, `semantics`, `source`, or a `render` with theme, font scale
   or device) — limited to the kinds the request lists as available.
3. **Rounds 1..n** (`--rounds`, default 1). Only those subjects, only those rules, with the evidence
   gathered. A rule still undecided is reported **unchecked**, never passed.

The CLI's host lists nothing fetchable yet (a follow-up render needs the live session, which the
`guidelines` command does not keep open after the `a11y` pass), so today triage is skipped and an
undecided rule is unchecked; wiring `MatrixCell` re-renders through the session is the next step.

## Caching and cost

Each result is cached under `build/compose-previews/guidelines/` by (preview id, render sha256,
rules version, model): an unchanged render is not asked again, so a re-run after a small change pays
only for what changed. `--changed-only` narrows to previews whose capture changed; `--max-cost`
stops asking once spent, reporting the rest unchecked.

At the default model (`deepseek/deepseek-v4.1-flash`, called directly) a screen costs roughly
$0.003–0.007; batching shares the rules and system prompt across subjects, so a component batch
costs less per subject. Every record carries the model that actually answered, its provider, the
cost and the generation id — and, for a routed model, the router's reason and probability.

## Keys

`COMPOSE_PREVIEW_OPENROUTER_KEY`, read from the environment only, never from the command line or a
file the CLI writes. In CI it is a secret available only to the job that calls the model (the
publish phase on fork PRs; see Next steps).

## Output

- `build/compose-previews/guidelines.json` — one `ModuleGuidelines` per module: each preview's
  `GuidelineRecordV1` (verdicts, served model, cost), its regions and its unchecked rules. A narrowed
  run merges into what is there.
- `--annotate` writes `<render>.guidelines.png` beside each render with findings: node outlines from
  the accessibility bounds and soft boxes for regions, labelled with the rule id.
- `--json` prints the same; `--fail-on warning|info` sets the exit code.

## Next steps

1. **Follow-up evidence in the CLI.** Keep the render session open after the `a11y` pass and
   implement `GuidelineEvidenceHost.render` through `MatrixCell`/`PreviewOverrides`, so triage and
   `needs_evidence` rounds can draw dark, large-font and device renders.
2. **Preview-diff pipeline.** A `guidelines` pipeline in `.github/actions/apply` beside `a11y`,
   run on the changed ids from `compare-previews.py`, posting a `<!-- guidelines-report -->`
   comment. The OpenRouter key exists only in the publish phase, which runs this command in handoff
   mode (`--renders-dir`) over the renders the render phase handed over.
3. **MCP tools** in compose-preview-server: `check_preview_guidelines` (engine over the live daemon,
   which can fetch every evidence kind) and a keyless `preview_guidelines_prompt`, consuming this
   module's published coordinate.
4. **VS Code**: a `compose-preview-guidelines` diagnostic collection modelled on
   `PreviewA11yDiagnostics`, reading `guidelines.json`, key in `SecretStorage`.
5. **Hosted catalogs**: `ServeGuidelineRecords` in render-host and a `guidelines/result` data
   product in the server, so a published catalog's findings are served beside its renders.
6. **Regions in the contract**: `GuidelineRegionV1` (compose-preview-contracts #157) replaces the
   engine's local `GuidelineRegion` once released.
