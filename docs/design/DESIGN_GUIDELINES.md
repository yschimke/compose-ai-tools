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

- **Surface.** Read off the preview's `previews.json` entry by `GuidelineSurfaces.of`, the same in
  a live run, a handoff run and anything else consuming the engine. A preview discovery records a
  `widget` for is a `widget`: a Glance Wear widget (drawn through `WearWidgetPreview` /
  `CapturingWearWidgetPreview`, or fed a `androidx.glance.wear` `@PreviewParameter` provider) or a
  launcher widget (a Glance app widget, a `@LauncherWidgetPreview` / `@LauncherWidgetResize`
  capture). Otherwise a preview with a `device` is a `screen` and anything else a `component` (a
  button sticker, a card). `--surface` overrides for every preview. A rule naming surfaces is asked
  only of those; a rule naming none is asked of every subject.
- **Profile.** A rule naming Remote Compose profiles is asked only of a subject targeting one. A
  Wear widget targets `wear-widgets` (from the manifest's `widget.profile`); `--profile`
  overrides. A launcher widget's profile is chosen at runtime, so discovery leaves it unset.
- **Nothing to ask.** A subject no subject- or set-scoped rule applies to is sent nowhere: its
  result carries `noRules` (why) and the run a problem line, rather than a request whose empty
  reply reads as unreadable at best and a clean pass at worst.
- **Frames.** A catalog's `frames` (a widget in each launcher's host container, a list unrolled, a
  fixed size) are pictures a host that renders designs can draw. A preview run has only each
  preview's own capture, so the prompt names the frames that were not rendered and tells the model
  to judge a rule pointing at one on the pictures it has.
- **Scope.** `subject` rules are asked per subject; `set` rules once per batch, across all of it.
- **Pictures.** Visual rules are left out of a subject with no picture.

## Shared rule packs (`includes`)

A catalog file layers shared packs under its own rules instead of copying general guidance into
every catalog:

```json
"includes": [
  {
    "url": "https://raw.githubusercontent.com/yschimke/compose-ai-tools/<tag>/guidelines/packs/compose-ui.guidelines.json",
    "sha256": "<sha256 of the pack's bytes, lowercase hex>"
  },
  {
    "url": "https://raw.githubusercontent.com/yschimke/compose-ai-tools/<tag>/guidelines/packs/wear-compose.guidelines.json",
    "sha256": "<sha256>"
  }
]
```

An include is `https` only and pinned by `sha256`, so it names one exact pack; optional `exclude`
(rule ids to leave out) and `profiles` (narrows carried rules that name none) tune it. Merging, in
`GuidelinesIncludes` (engine) and `UiBuilderGuidelinesFile.flatten` (Gradle plugin), which must
agree:

- **packs are layers**, in include order, with the catalog's own rules as the last layer: a rule
  replaces one of the same id from an earlier layer, where it stood, so a form-factor pack
  overrides a general one and the catalog overrides both;
- a pack rule naming `platforms` is carried only into a catalog whose `platform` it lists
  (`mobile`, `foundation`, `wear`, `glasses`, `launcher`, `remote-compose`); none means every one;
- an include's `exclude`d ids are left out of that include;
- a pack's frames are added where the catalog does not already ask for the same one;
- a pack may not include another, is at most 1 MiB, its rules pass the same checks as a
  catalog's own, and a file has at most 8 includes.

**Published flat.** Discovery (`DiscoverPreviewsTask`) and bundling resolve the includes and write
`build/compose-previews/ui-builder.guidelines.json` with the packs merged in and no `includes`
left, so compose-preview-server (which reads that file from the delivery branch, and from a local
module's `build/compose-previews/`) and the browser editor need no change. If a pack cannot be
read the file is published as written, with a warning; the publish workflow's
`validate-ui-builder-guidelines.mjs` fetches every include, checks its pin and its rules before
rendering, so a bad pin fails the publish rather than shipping a catalog without the pack's rules.
The CLI's loader resolves includes itself (`--guidelines` pointing at an unflattened file), and
refuses the whole file when one does not resolve: a check asked of part of the rules must not read
as the whole check.

### The packs

Under [`guidelines/packs/`](../../guidelines/packs/). Every `guidance` is quoted word for word
from developer.android.com and every rule links its page. Material-specific guidance stays in
each catalog's own file (m3-catalog's phone M3 rules, wear-m3-catalog's Wear M3 rules).

| Pack | What it carries |
| --- | --- |
| `compose-ui` | Compose UI on every form factor, not Material: accessibility semantics, text (truncation, font scale, string resources, RTL), theme tokens, measured touch targets and contrast, credentials and text entry, loading and error states, destructive actions. |
| `wear-compose` | Wear OS Compose, not Material: replaces `compose-ui`'s `compose.sign-in.credential-manager` and `compose.input.keyboard-options` with the Wear versions (Credential Manager / OAuth with `RemoteAuthClient` / Data Layer, remote input through `RemoteInputIntentHelper`), and adds minimal typing, sign-in options, no sign-in wall, rotary scrolling and proportional margins. |
| `remote-compose` | Any Remote Compose surface: remote composables only, declarative actions and state, deferred units, text sizing, image descriptions, themed colours, profile operations. No text entry. |
| `launcher-widgets` | Home-screen widgets: one glanceable use case, edge to edge, system corner radius, sizing and breakpoints, touch targets, contrast, type, empty states. Widget surfaces only. |
| `wear-widgets` | Wear OS widgets: drawn whole in the Samsung and Pixel Watch containers, background on the widget document, fixed heights and no nested scrolling, focused and predictable actions, colour roles and type. Widget surfaces on the `wear-widgets` profile. |

Which catalog includes which, in order:

| Catalog | `includes` |
| --- | --- |
| m3-catalog (`mobile`) | `compose-ui` |
| wear-m3-catalog (`wear`) | `compose-ui`, `wear-compose` |
| glimmer-catalog (`glasses`) | `compose-ui` (its touch-target rule names no `glasses`, so it is not carried) |
| remote-m3-catalog `remote-catalog` (Wear widgets) | `remote-compose`, `wear-widgets` |
| remote-m3-catalog `widget-catalog` (launcher widgets) | `remote-compose`, `launcher-widgets` |

`GuidelinesPacksTest` validates them: shape, https sources, known platforms, surfaces and profiles,
ids unique across packs except the overrides a later layer declares, no text-entry rule in the
remote and widget packs, and the matrix above. With `GUIDELINES_VERIFY_QUOTES=1` it also fetches
every source page and checks each guidance appears on it verbatim. Include packs by a release tag,
and re-pin `sha256` when moving to a newer tag.

## Batching

Not one call per preview. Subjects of one surface share a request, up to a budget (default 12
pictures, ~60k input tokens, 16 subjects, 120 verdicts): the rules are sent once, and `set` rules
see the whole batch. The verdict cap — each subject's rules, summed — bounds the *reply*, which is
what takes the time: twelve Wear screens asked 24 rules each is 288 verdicts, a reply that ran past
the request timeout twice, while the request itself came to about 25k input tokens by the batcher's
estimate, well inside the token budget. At 120 a screen batch of that catalog holds five. (A re-run failed the same twelve-screen
request the same way while the component request passed both times: it was the request's size, not
a blip, and asking it again would not have helped.) A picture counts 1,200 tokens, or its pixels
over 750 when that is more — a tall scroll capture or a tablet screen. Each subject carries its render (tagged with its subject id) and optionally its source;
its accessibility data is evidence it is asked for (see *Accessibility evidence*). The model names the subject (`s1`, …) in every
verdict and cites node ids, which map back to bounds for overlays; a visual problem no single node
holds comes back as a region (a fraction box on a numbered picture).

## Evidence loop

The first pass carries what is cheap: one render, a one-line summary of the accessibility data the
host holds, and the preview's **source** — the `@Preview` function from `previews.json`'s `bodyLine` to the end of
its body (`PreviewSourceReader`, capped at 200 lines / 8k chars), sent as a `source` evidence item
and in the prompt. Pictures alone miss code-level problems: on a live run the XPeng screen's render
judged clean, while its fixed 36dp tap targets, two filled buttons, hard-coded type and colours
and unlabelled icon buttons are facts of the code. Each batch carries at most
`GuidelineBudget.maxSourceChars` of source (32k): under pressure, source is given up before a
picture.

1. **Triage** (optional, `--no-triage` to skip). Jev (`typesafe/jev-1.13`, text only, typed
   probabilities, ~$0.0003) is asked per subject whether a dark-theme render, a large-font render or
   its accessibility data would be needed; the host fetches only what it wants above 0.5.
2. **Round 0.** The batch is judged. A rule the model cannot decide is answered `needs_evidence`
   with what it needs (`a11y`, `source`, `scroll-capture`, or a `render` with theme, font scale
   or device) — limited to the kinds the request lists as available. The protocol's
   `a11y-hierarchy` / `semantics` are served as `a11y` by a host offering it.
3. **Rounds 1..n** (`--rounds`, default 1). Only those subjects, only those rules, with the evidence
   gathered. A rule still undecided is reported **unchecked**, never passed.

Every request lists each subject's own rule ids and says no other id is valid, and the engine holds
the reply to that: a verdict whose `ruleId` was not asked of its subject (or of the set), or whose
`subjectId` is outside the request, is dropped and counted in the run's `problems` — a model that
invents `clipping` or `R5` produces no finding without a guide. A reply left with no usable verdict
is asked once more, budget permitting, and is otherwise a failed request.

## Failed requests

`OpenRouterClient` makes each call once; `GuidelineEngine` decides what to do when one fails
(`GuidelineRunOptions.withRetry(GuidelineRetry(…))`):

- **No answer.** A request the transport gave up on comes back as `ModelResponse.NO_ANSWER` (0)
  with what gave up on it — `no complete answer within the 300 s request timeout` for OkHttp's
  call timeout, a read or connect timeout, or a failed connection. OkHttp's own message for all
  three is `timeout`, which is all the old `the model answered 0: {"error":{"message":"timeout"}}`
  said. OpenRouter keeps a slow completion's connection open with whitespace, so the call timeout,
  not the read timeout, is what fires; `--request-timeout <seconds>` (default 300) sets it.
- **Asked again.** No answer, 408, 429, 5xx, an error OpenRouter sent in place of a completion
  (a 200 whose body is an `error`, or a choice ending `finish_reason: "error"`) and a reply with no
  usable verdict are asked again, up to two tries, after a backoff (2 s, doubling, at most 60 s) or
  the server's `Retry-After` — one asking for longer than that is not waited for. 401, 402 and other
  4xx are not.
- **Split.** A batch that still fails is split in half and each half asked, down to single
  subjects, so one slow batch cannot leave every preview in it unchecked. A timeout on a batch of
  several subjects is split at once rather than asked again, since the same request would take as
  long again; a 413 is split without a retry; a 429 is never split.
- **Bounded.** Each retry and each half is a request under `--max-cost`: none is started that the
  cap cannot afford, a failed reply's cost counts, and a request abandoned without an answer —
  which may still have been billed — counts at what the dearest request cost. Past eight failed
  tries in a run, nothing more is retried or split.
- **Reported once.** A problem is recorded for each request that finally failed (with how many
  tries it took), plus one line each for the retries and the splits — never one per attempt.
  `failedRequests` counts only final failures, so a run whose splits all succeeded is complete.

In a Gradle run the CLI's host (`CliEvidenceHost`) supplies `a11y`, `source` and `render`: a
render need becomes a `MatrixCell` (theme → `uiMode`, font scale, device, locale) drawn through the
module's render daemon, one short session per follow-up render — they are few, so that is simpler
than a session held for the run. A layout-direction-only need has no cell and is left undecided.
An `a11y` need is fetched through the `a11y` command's daemon fetch, narrowed to the previews a
round asks about (one fetch per round, through `GuidelineEvidenceHost.prefetch`). Triage runs. In
handoff mode the host serves only what the render job staged: `a11y` and `scroll-capture`.

## Accessibility evidence

A preview's accessibility data — its nodes (id, role, label, bounds, states such as `scrollable`,
`clickable`, `heading`) and the Accessibility Test Framework's measured checks on its render — is
evidence kind `a11y` (`PreviewGuidelineRequests.KIND_A11Y`), offered where the host has it and
served in a follow-up round, not sent with every subject. Most rules are judged from the picture
and the source; the ones that turn on touch-target size, contrast, content descriptions, traversal
order or headings, or a `fail` that should cite its node, are where the system prompt tells the
model to ask for it rather than estimate.

Up front each subject gets one line instead, from `GuidelineEvidenceHost.summary`:

```
Accessibility: 12 node(s), 1 scrollable; ATF: 2 ERROR TouchTargetSizeCheck. Ask for `a11y` for the nodes and checks.
```

About 30 tokens, against roughly 20 per node and 60 per check for the data (the request caps a
subject at 80 nodes and 40 checks, so up to ~4,000 tokens; a typical screen's 20 nodes and a
couple of checks is ~500). It is worth its place because it decides whether asking would help: a
clean ATF result settles the touch-target and contrast rules without a second round, and the
scrollable count is what tells content scrolled out of view from content clipped. The summary is
shown only while the data is not attached, and only by a host that holds it: a host that would have
to render to summarise (the live CLI before its first fetch) shows none.

Overlays still outline nodes. A verdict names node ids only after an `a11y` round; the annotator
draws from the nodes the host holds (in handoff mode the staged `accessibility.json`, in a live run
every node fetched), not from what the subject carried, so those findings are outlined. A finding
from a model that never asked is marked by its region, as before.

Hosts that can render fetch it on request for the preview that asks, not for every preview before
the first request: the CLI's full mode did that for the whole catalog on every run (the catalog
publish's check paid one ATF render per preview whether any rule needed it). The MCP server and the
VS Code extension do not implement a host yet (see *Next steps*); they should follow the same shape.

The data is derived from the same render, so its bytes are not part of the cache identity, but
whether it could be asked for is (`+a11y` on the render hash): a verdict reached without it (no a11y
pipeline, a daemon that failed) never answers a run that has it. A PR's handoff with staged data and
the catalog publish (which can always fetch it) key the same preview the same way. A live fetch
first drops the previews' old entries from `accessibility.json`, so a failed fetch serves nothing
rather than an older render's nodes, and `--permutations` ids are fetched as their declared
preview with the permutation's overrides.

## Handoff mode (CI)

No Gradle, no daemon:

```
compose-preview guidelines --previews-json <module>/previews.json \
  --a11y-json <module>/accessibility.json --source-root <module-dir> \
  --guidelines <ui-builder.guidelines.json> [--annotate] [--max-cost 0.25]
```

`--previews-json` takes the module's real `previews.json` (each capture names its render, each
preview its `sourceFile` and `bodyLine`) or a flat id list narrowing `--renders-dir`. Paths in it
are confined to the staged tree: the file comes from the PR, possibly a fork, and a `../` must not
make the publish job read a runner file and send it to a model.

## Caching and cost

Each result is cached under `build/compose-previews/guidelines/` by everything its verdict depends
on (`GuidelineResultCache.inputsKey`: preview id, surface, profile, every picture's bytes, the
nodes and checks the subject carries (none, now that they are evidence), source, the rules' full
content, model): an unchanged preview is not asked again, so a re-run after a
small change pays only for what changed. `--changed-only` narrows to previews whose capture changed;
`--max-cost` stops asking before a request expected to cross it (one costing as much as the
dearest request so far), reporting the rest `pending`; a reply that could not be used still counts
against it — and the run asks first about
previews the cache has never held a result for, before those whose earlier verdict went stale. A
run over the whole module prunes the cache to the verdicts it read or wrote.

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
  `GuidelineRecordV1` (verdicts, served model, cost), its regions and its unchecked rules, `pending`
  when it was never asked (a failed request, the cost cap) and `noRules` when nothing applied; and
  the run's `requests`, `failedRequests` and `problems`, so a reader can tell a run that judged
  nothing from a clean pass. A narrowed run merges into what is there. The PR comment lists every
  preview that was not checked, and why, and never says "No findings" when nothing was judged.
- `--annotate` writes `<render>.guidelines.png` beside each render with findings: outlines (solid for
  nodes from the accessibility bounds, dashed for regions) drawn just outside what they mark and
  never filled over it, each with a numbered badge. Findings are numbered in the order they are
  listed, counting only those with a mark; the PR comment lists them under the same numbers.
- `--json` prints the same; `--fail-on warning|info` sets the exit code.

## Wrapper source

A preview is often a single call into the catalog's frame (`WearList() = WearScreen { … }`), and
the frame is where the time text, the scaffold and the theme come from. Shown only the preview's
body, a model reported "ScreenScaffold's timeText is not set" for a screen whose frame supplies
it. `PreviewSourceReader.readWithCallees` therefore appends the bodies of the functions the preview
**directly calls** that its own module defines — one level deep, at most 3 of them within 4k
characters — found through a per-module `SourceIndex` of `fun Name(` declarations under the `src`
directory holding the preview. Library composables are never in that index, so they are never
pulled in. In handoff mode the index is bounded by `--source-root`: a fork's staged tree cannot
make the walk read past it.

The picture description also states the capture's scroll mode in Gradle mode, as handoff mode
already did: at the END of a scroll, the time text has scrolled away rather than gone missing.

## Scrolling and measured checks

A finding like "the footer is cut off" means nothing until the model knows whether the container
scrolls. Each subject's accessibility nodes carry their `states` (`scrollable`, `clickable`, …) and
the viewport's pixel size, and a node extending past the viewport is marked `off:bottom` (or
`top`, `left`, `right`). The system prompt says content cut along a scrollable axis is scrolled
away, not clipped. The nodes arrive with `a11y` evidence; up front the summary line says how many
nodes scroll.

When the renderer wrote a long screenshot (the preview's `render/scroll/long` data product, under
`data/render-scroll-long/`), the render phase stages it beside the render in the handoff as
`<render>_SCROLL_long.png` (a LONG-only preview, which has no other capture, is judged on it), and handoff mode offers it as
requestable evidence: kind `scroll-capture` (`PreviewGuidelineRequests.KIND_SCROLL_CAPTURE`), listed
in `evidenceAvailable` only for previews that have one (`GuidelineEvidenceHost.available(id)`). A
`needs_evidence` verdict asking for it is answered in a follow-up round from the staged file
(`HandoffEvidenceHost`); the publish job renders nothing and builds nothing. A preview with no
extra capture offers what it did before — nothing. The `apply` action runs `--rounds 2`, and every
round counts against the one `guidelines-max-cost` budget.

Where the a11y pipeline ran, the Accessibility Test Framework results in `accessibility.json` are
served with the nodes as `a11y` evidence and listed per subject as measured checks, and the prompt
says they decide the touch-target and contrast rules over the model's estimate from the picture.
On a PR the a11y pipeline checks exactly the previews the PR changed — the set this check stages —
so every staged preview has its data. Without the a11y pipeline (`only:
compose`) a subject has neither nodes nor checks, and the prompt asks for a region for every
visible failure instead, so it can still be marked. The scroll *range* and *position*
(`verticalScrollAxisRange`, `CollectionInfo`) are not in the accessibility data the daemon
produces; only the `scrollable` state is.

## Hosting the results

- **Bundles carry them.** When `build/compose-previews/guidelines.json` exists, `BundlePreviewTask`
  (`guidelineResultsFiles`) carries it as the bundle entry `guidelines.json` — only when it reads
  as a report. The annotated `*.guidelines.png` overlays are **not** carried: a host draws findings
  from the verdicts' nodes and regions.
- **render-host loads them.** `ServeGuidelineResultsStore.load(bundleDir)` reads that entry fail-soft
  into `ServeGuidelineResults` (`catalog`, `model`, `records: Map<previewId, GuidelineRecordV1>`),
  keeping only the contract-shaped record of each result and bounding it (known verdicts, clamped
  text, cleaned ids, regions inside the picture). `ServeHost.guidelineResultFor(previewId)` is the
  host API, `null` by default.
- **Publishing produces them.** `design-artifacts-reusable.yml` takes `guidelines: true`, the
  `openrouter_key` secret and `guidelines-max-cost` (default `1.00`). Its "Check design guidelines"
  step runs `compose-preview guidelines` before `bundle pack`, so the published bundle carries the
  results; without the secret, or with a CLI older than 2.38.0, it is skipped with a warning, and it
  never fails the publish.

## Publishing results

A catalog publish (`design-artifacts-reusable.yml`, single-job pipeline) carries the engine cache
between runs: `actions/cache` restores `<module>/build/compose-previews/guidelines/` under
`guidelines-<system>-…` before "Check design guidelines" and saves it afterwards, whether or not the
check finished. Every publish therefore carries the **whole** catalog's results in
`guidelines.json` — this run's answers plus every cached one — and the step summary reports how
many previews were checked this run, answered from the cache, and left pending.

- **First run versus incremental.** The first publish of a catalog asks about every preview, at
  roughly $0.003–0.007 a screen and less per component (batches share the rules and prompt). Under
  the default `guidelines-max-cost` of `1.00` that is a few hundred previews; a bigger catalog stops
  at the cap with the rest pending. After that a publish pays only for previews whose render,
  source, nodes or rules changed — usually cents — and a rule edit re-asks every preview it applies
  to, since the rules' content is part of the key.
- **Convergence.** A capped run spends its budget on previews never checked before, so coverage
  grows by about a cap's worth each publish until every preview has a result; only then do stale
  previews (re-rendered since their last verdict) compete for it. A preview pending after a cap
  carries no verdicts, not its stale ones — an old render's findings would be about pixels the
  catalog no longer draws. Raise `guidelines-max-cost` for one publish to converge at once.
- **Cache scope.** Actions caches are per-branch with fallback to the default branch, so a publish
  from `main` warms every branch's first run, and a branch's own saves stay on that branch. Each save
  is keyed by commit and run (caches are immutable), and a restore takes the newest save for the
  catalog.
- **One identity in both pipelines.** A result is filed under its `inputsKey`, never under a module
  or a path, and the render part of it (`renderHash`) is the PNG's sha256 plus, for a scrolling
  preview, `+scroll:<sha256 of its long screenshot>` — the same in a Gradle run (the publish) and a
  handoff run (a PR's check), which is what lets a PR read the publish's cache (below). The PR's
  renders and the publish's are the same bytes for an unchanged preview: same Gradle plugin,
  renderer and devices, and on wear-m3-catalog all 1148 `:catalog` renders on
  `compose-preview/main` (the apply action's baselines) hash identically to the catalog publish's.
  The other inputs line up as long as both sides run the same CLI release (its `REQUEST_FORMAT`),
  model and rules, and the PR leaves `guidelines-surface`/`guidelines-profile` unset; a preview
  whose staged callee files hit the stage step's bound, or whose render differs, simply misses and
  is asked.
- **Sharded publishes do not check.** With `render-shards` of 2 or more the catalog renders in
  `render-shard` jobs and only bundles reach the merge job, which has no Gradle build or render
  daemon to fetch evidence from. Checking there would mean rendering the catalog again, so the step
  and its cache stay on the single-job path.

## The PR check

A PR's check (the `apply` action, `guidelines: true`) is bounded and incremental:

- **Only the PR's own previews.** The render phase stages previews whose render the PR changed or
  whose source file it touched. A change to the guidelines file pulls in no other preview: the
  comment says so in one line — that the catalog publish on the default branch re-checks them
  (cheaply, from its cache) when `guidelines-cache-key` names that publish's cache, and otherwise
  that they were not re-checked, since a sharded publish or one without `guidelines: true` never
  checks. `guidelines-rules-sweep: 'true'` restores the old second tier (at
  most 24 other previews of the module, staged as `<module>.rules-changed/`).
- **At most `guidelines-max-previews` (default 30).** Ranked: a changed render before a source-only
  selection; within those, new previews and the largest render diffs first (`diff`, the share of
  pixels `compare-previews.py copy-changed` measured as changed). **One render of each preview
  function goes first**: `WearList_192dp`…`_240dp` or a dozen `_VARIANT_` cells are one design,
  and its best-ranked render says most of what the siblings would, so 30 slots reach 30 functions
  instead of three; the siblings follow in rank order while slots remain. Previews past the limit
  are listed in the comment as NOT checked, "over this PR's limit of N" — never dropped. The job
  holding the key cuts the handoff to the limit again (`guidelines-budget.py --trim`), since the
  handoff is the PR's own build's output. `guidelines-max-cost` stays the money cap.
- **Reuses the default branch's verdicts.** With `guidelines-cache-key: guidelines-<system>-`
  (and `guidelines-cache-path` if the publish has a `working-directory`), the check restores the
  catalog publish's result cache with `actions/cache/restore` — restore only, a PR run never saves
  — and copies it into each staged module (`guidelines-budget.py --seed-cache`). A preview whose
  render, source, rules and model are what the default branch already checked is answered
  from it at no cost; the comment counts those. Typical hit: a PR editing one function in a file
  stages every preview of that file, and only the edited one is asked. The publish phase of a split
  workflow runs on `workflow_run`, in the default branch's cache scope, so it reads the caches the
  default-branch publish saved; a single-job `pull_request` run reads them through its base. Any
  cache the handoff itself carried is deleted first: a cached verdict is served as a verdict.

## Next steps

1. **Done: annotated images in the PR comment.** The publish phase pushes one picture per preview
   with findings to `compose-preview/guidelines/pr` in `artifact-repository` and embeds it pinned
   to that commit: the render with its findings marked when one names a node or a region on it,
   otherwise the render itself, captioned "Nothing marked". `guidelines-report.py --stage-images`
   chooses and copies those pictures, so the comment and the push cannot disagree. A preview two
   modules both discover (a desktop module re-rendering a multiplatform module's previews) is
   staged once, in the module holding its source, and reported once.
2. **Done: preview-diff pipeline** — see the `apply` action's `guidelines` input: the render phase
   stages changed previews (`guidelines-stage.py`: renders, source, nodes, rules) into the handoff;
   the phase holding `openrouter-key` runs handoff mode and posts `<!-- guidelines-report -->`.
   A PR that changes the guidelines file (a rebase can bring one in) no longer selects the module's
   other previews unless `guidelines-rules-sweep` asks (see *The PR check*); the catalog publish
   re-checks every preview against new rules. The comment leads with any of the PR's own previews
   that were not checked.
3. **MCP tools** in compose-preview-server: `check_preview_guidelines` (engine over the live daemon,
   which can fetch every evidence kind) and a keyless `preview_guidelines_prompt`, consuming this
   module's published coordinate.
4. **VS Code**: a `compose-preview-guidelines` diagnostic collection modelled on
   `PreviewA11yDiagnostics`, reading `guidelines.json`, key in `SecretStorage`.
5. **Hosted catalogs**: render-host part done (see *Hosting the results*); the server still needs a
   `guidelines/result` data product served from `ServeHost.guidelineResultFor`, and its bundle host
   implementing that from `ServeGuidelineResultsStore`.
6. **Done: regions in the contract** — verdicts carry `GuidelineRegionV1` (contracts 3.24.0).
