---
title: Reference
layout: default
nav_order: 2
has_children: true
permalink: /reference/
---

# Reference

The renderer can produce structured data alongside each PNG —
accessibility findings, semantic hierarchies, layout trees, theme
tokens, recomposition heat maps, and more. Each is a **data product**
identified by a namespaced *kind* string with its own JSON schema.

This section has one page per product. Each page follows the same
shape: at-a-glance metadata, what the product answers (and what it
deliberately does not), use cases, payload shape, and how to enable it.

## How to read these pages

A data product is `(kind, schemaVersion, payload)`:

- **kind** — namespaced string (`a11y/atf`, `compose/recomposition`, …). Reserved namespaces: `a11y/*`, `layout/*`, `compose/*`, `resources/*`, `text/*`, `render/*`, `fonts/*`, `test/*`, `i18n/*`, `history/*`, `uia/*`.
- **schemaVersion** — positive integer, owned by the kind. Bumped only on incompatible payload changes; additive fields don't bump.
- **payload** — JSON. Inline for small payloads (~64 KB), or a path to a sibling file the renderer wrote (lifecycle matches the PNG).

Each kind has exactly one `@Serializable` definition in a
`:data-<product>-core` module published to Maven Central. MCP clients
in any language can generate parsers from those without depending on
the Compose runtime, daemon, or AndroidX. The matching
`:data-<product>-connector` module is daemon glue and is internal.

## Catalogue

| Product | Kind(s) | Mode | Notes |
|---|---|---|---|
| [Accessibility](./a11y) | `a11y/atf`, `a11y/hierarchy`, `a11y/overlay`, `a11y/touchTargets` | a11y | ATF findings, semantic tree, annotated overlay, 48 dp targets. |
| [Ambient (Wear)](./ambient) | `compose/ambient` | default | Drive `AmbientMode` overrides for Wear ambient previews. |
| [Focus](./focus) | `compose/focus` | default | Focused-node snapshot + directional traversal overrides. |
| [Fonts](./fonts) | `fonts/used` | default | Font families with weight/style fallback chain. |
| [History diff](./history) | `history/diff/regions` | default | Per-pixel bounding boxes of changed regions vs. another history entry. |
| [Layout inspector](./layout-inspector) | `layout/inspector`, `compose/semantics` | default | Compose layout/component hierarchy with bounds, modifiers, source refs. |
| [Pseudolocale](./pseudolocale) | `localeTag = en-XA`/`ar-XB` | default | Runtime accent / bidi pseudolocalisation. Android: text + layout direction. CMP Desktop: layout direction. |
| [Recomposition](./recomposition) | `compose/recomposition` | instrumented | Per-node recomposition counts; heat-map source. |
| [Render trace](./render) | `render/composeAiTrace`, `render/trace`, `render/deviceBackground`, `render/deviceClip` | default | Pipeline timing, device clip / background derivation. |
| [Resources](./resources) | `resources/used` | default | `R.*` references resolved during render. |
| [Scroll captures](./scroll) | `render/scroll/gif`, `render/scroll/long` | default | Long-form scrolling captures and animated GIFs (renderer-side, image-only). |
| [Strings & i18n](./strings) | `text/strings`, `i18n/translations` | default | Drawn text + locale coverage. |
| [Theme](./theme) | `compose/theme` | default | Resolved Material 3 tokens + which nodes consumed them. |
| [UIAutomator hierarchy](./uiautomator) | `uia/hierarchy` | default | Selector-shaped Compose nodes for UIAutomator-style targeting. |
| [Wallpaper](./wallpaper) | `compose/wallpaper` | default | Material You seed colour → derived scheme. |
| [Test failure](./test-failure) | `test/failure` | failed render | Postmortem bundle after a `renderFailed`. |

## Surfaces

Two ways to consume a product:

1. **MCP** — `list_data_products`, `subscribe_preview_data`,
   `get_preview_data` on the
   [`compose-preview-mcp`](https://github.com/yschimke/compose-ai-tools/tree/main/mcp)
   server. The right path for any agent that's already driving previews
   through MCP.
2. **CLI / Gradle** — when a kind is enabled in the consumer's
   `composePreview { ... }` config, the renderer writes the same payload
   to `build/compose-previews/data/<previewId>/<kind-with-slashes-as-dashes>.json`
   on every render. CLI / CI consumers read those files directly.

For the full wire-protocol contract — error codes, transports,
re-render semantics, extension activation — see
[`docs/daemon/DATA-PRODUCTS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/daemon/DATA-PRODUCTS.md).
