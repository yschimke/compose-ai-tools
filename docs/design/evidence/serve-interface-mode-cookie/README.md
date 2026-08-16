# Catalog / Dev is a cookie, not a URL parameter

The two presentations the header switch selects, captured from the committed page fixtures
(`vscode-extension/preview-harness/fixtures/pages/*.html`) so they show exactly what the server
emits for each mode:

| File | Fixture | Mode |
| --- | --- | --- |
| `catalog-mode.png` | `serve-component-browser-home` | Catalog — the streamlined component browser |
| `dev-mode.png` | `serve-home-index` | Dev — the full interface (menu, status, settings) |

Captured by serving the fixture directory alongside
`cli/src/main/resources/ee/schimke/composeai/cli/serve/assets` as `/assets/serve/fixture` and
screenshotting each page at 1280×760 in headless Chromium — the same pages
`pages-snapshot.spec.mjs` shoots for the visual-diff bot.

**These pixels are what the cookie now chooses between; the change that added this directory does
not move them.** It changes how the choice travels — a host-wide `cp_chrome` cookie the server
reads, instead of `?chrome=` appended by script to every same-origin link on the page — so the
fixture diff that accompanied it is confined to the two inline `<script>` blocks. See
[the URL-state section of the server docs](../../../public-preview-server.md#except-catalog--dev-which-is-a-mode-you-are-in).
