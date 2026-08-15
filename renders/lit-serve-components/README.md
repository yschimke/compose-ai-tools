# Lit serve components — `<cp-backend-badge>` and `<cp-group-memory>`

Committed evidence for the ports of `assets/backend-badge.js` and
`assets/viewer-groups.js` onto the Lit toolchain in
[`cli/serve-web`](../../cli/serve-web/README.md).

Both are **behaviour** ports, not redesigns: the served markup keeps the same
classes and ids, and `serve.css` is untouched apart from a `display: none` rule
for the new no-op `<cp-group-memory>` tag. So the point of these captures is that
they did **not** move — and that the badge still paints, which a diff of the
Kotlin alone cannot show.

| file | what it is |
| --- | --- |
| `badge-snapshot-before.png` / `-after.png` | the viewer at rest. The badge reads **▪ Snapshot**, taken from `.cp-viewer[data-snapshot-backend]`, and the drawers on the right (**Size**, **Locale & text**) are the `details.cp-group` elements whose open state `<cp-group-memory>` remembers |
| `badge-spec-before.png` / `-after.png` | the same viewer after the **Spec** chip flips `data-mode` to `spec` at runtime. The badge follows to **◇ Figma** — the outline diamond, because an imported design reference must not wear a renderer's ▶/▪ icon — which is the `MutationObserver` path end to end in a real browser |

Cropped from the `pages-snapshot` harness captures of
`serve-viewer.light` and `serve-viewer-path-spec-lane.light`:

```
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

The before/after pairs are **byte-identical**, and so are 197 of the 198 page
captures the harness produces. The one that differs,
`serve-viewer-cross-product-subtree-open.light.png`, differs only in trailing
blank page background below the footer (1024 px tall before, 966 after) with
every pixel of content identical; the base tree produces 966 too when the same
capture is run narrowed (`HARNESS_FIXTURE=serve-viewer-cross-product`), so the
extra strip is a run-shape artifact on the base side rather than something these
ports introduced. Two full runs of the ported tree are byte-identical to each
other.
