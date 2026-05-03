# Data-product documentation workplan

This is the repo-internal brief for splitting data-product documentation work
across implementation agents. User-facing review guidance belongs in the
`compose-preview` and `compose-preview-review` skill references; this document
is for contributors changing compose-ai-tools itself.

## Common brief

Goal: make every preview data product understandable to both humans and
agents. Humans need a stable product contract on GitHub; agents using the
tooling need compact recipes that say when to request each product, how to
combine it with others, and what evidence is strong enough to cite in a PR
review.

Before splitting work, read the common contract template in
[DATA-PRODUCTS.md § Documenting a kind](./DATA-PRODUCTS.md#documenting-a-kind).

Shared rules for every implementation agent:

- Keep CI and PR workflows intact: previews, resources, accessibility, and
  other review evidence should stay available through agent/MCP workflows even
  when CLI-facing configuration is reduced.
- Separate contract docs from agent heuristics. Human docs should not need to
  read like agent prompts; skill references should not duplicate every schema
  field.
- For each product, document availability, platform constraints, cost, schema
  shape, media type or transport, failure/unavailable behavior, and at least
  one realistic example.
- Name companion products explicitly. For example, an `a11y/atf` warning is
  easier to review with `a11y/overlay`, `compose/semantics`, and
  `text/strings`.
- Avoid overclaiming from one product. Prefer phrasing such as "the ATF
  product reports..." or "the screenshot shows..." when the evidence is
  tool-specific.

## Documentation levels

Human-facing GitHub docs should define the durable contract:

- Product name, version, producer, and consuming workflows.
- Supported targets, preview modes, daemon requirements, and graceful
  degradation when unavailable.
- Schema fields, correlation keys, binary attachments, and example payloads.
- Privacy/security notes for strings, resources, fonts, traces, and screenshots.
- Relationships between products, especially products that are easy to confuse.

Agent-facing skill references should stay operational:

- Which products to request for a review task.
- Product combinations that give stronger evidence than any single product.
- Audit checklists, fallback behavior, and wording guidance for PR comments.
- Known platform traps, such as Wear clipping, locale fallback, and desktop vs
  Android accessibility differences.

Issue bodies should be implementation work orders:

- Scope, acceptance criteria, example payloads, and tests.
- Links to the human contract section and any agent recipe that will consume
  the product.
- Open questions that need a design decision before implementation.

## Data-product issue clusters

### 1. Accessibility and semantics

Issues:
[#597](https://github.com/yschimke/compose-ai-tools/issues/597),
[#598](https://github.com/yschimke/compose-ai-tools/issues/598),
[#599](https://github.com/yschimke/compose-ai-tools/issues/599),
[#600](https://github.com/yschimke/compose-ai-tools/issues/600),
[#601](https://github.com/yschimke/compose-ai-tools/issues/601),
[#480](https://github.com/yschimke/compose-ai-tools/issues/480).

This agent should define the accessibility stack: ATF findings,
accessibility hierarchy, touch targets, annotated overlays, and raw Compose
semantics. The important split is raw evidence vs reviewer conclusions. The
human docs should say what each product reports; the agent docs should say how
to combine products to check visible text, semantics labels, roles, target
sizes, and new regressions.

### 2. Text, localisation, resources, and fonts

Issues:
[#604](https://github.com/yschimke/compose-ai-tools/issues/604),
[#605](https://github.com/yschimke/compose-ai-tools/issues/605),
[#606](https://github.com/yschimke/compose-ai-tools/issues/606),
[#609](https://github.com/yschimke/compose-ai-tools/issues/609),
[#450](https://github.com/yschimke/compose-ai-tools/issues/450),
[#451](https://github.com/yschimke/compose-ai-tools/issues/451).

This agent should document how rendered strings connect back to resource
usage, translation coverage, locale selection, and font resolution. The
highest-value agent recipe is a localisation audit: render or request preview
variants in target locales, compare visible strings with `text/strings`, check
`i18n/translations` for missing or fallback translations, and cite
`resources/used` when a wrong resource is selected.

### 3. Layout, Wear, and visual geometry

Issues:
[#612](https://github.com/yschimke/compose-ai-tools/issues/612),
[#611](https://github.com/yschimke/compose-ai-tools/issues/611),
[#610](https://github.com/yschimke/compose-ai-tools/issues/610),
[#447](https://github.com/yschimke/compose-ai-tools/issues/447),
[#454](https://github.com/yschimke/compose-ai-tools/issues/454),
[#645](https://github.com/yschimke/compose-ai-tools/issues/645).

This agent should connect layout inspection, device clipping, changed regions,
and static capture behavior. Wear deserves explicit coverage: round displays,
safe areas, clipped top/bottom content, TransformingLazyColumn behavior, and
edge buttons that can be visually present but partly outside the useful bounds.

### 4. Theme, performance, and runtime traces

Issues:
[#603](https://github.com/yschimke/compose-ai-tools/issues/603),
[#602](https://github.com/yschimke/compose-ai-tools/issues/602),
[#607](https://github.com/yschimke/compose-ai-tools/issues/607),
[#608](https://github.com/yschimke/compose-ai-tools/issues/608),
[#585](https://github.com/yschimke/compose-ai-tools/issues/585),
[#586](https://github.com/yschimke/compose-ai-tools/issues/586),
[#449](https://github.com/yschimke/compose-ai-tools/issues/449),
[#452](https://github.com/yschimke/compose-ai-tools/issues/452).

This agent should document design-system and runtime diagnostics: theme
tokens, recomposition, Compose AI traces, generic render traces, and shared
preview context. The agent guidance should explain when these products support
PR review directly, and when they are mainly triage evidence for a follow-up
issue.

### 5. Product surface, protocol, and failure handling

Issues:
[#641](https://github.com/yschimke/compose-ai-tools/issues/641),
[#568](https://github.com/yschimke/compose-ai-tools/issues/568),
[#455](https://github.com/yschimke/compose-ai-tools/issues/455).

This agent should keep the catalogue coherent across human docs, MCP flows,
and the reduced CLI surface. It owns naming, discovery, versioning,
unavailable-product responses, and how test or render failures are surfaced to
reviewers without requiring CLI-only configuration.
