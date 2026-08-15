# The render-history menu, before and after the port

Committed evidence for `viewer-history.js` → `<cp-history-menu>`.

A port's visual claim is that there is nothing to see, so the evidence is the
pair being *the same file*. All four `serve-viewer-history` captures are
byte-identical between this branch and `origin/main` — closed and open, light
and dark — so the images below are simultaneously the before and the after:

```
cd vscode-extension
rm -rf preview-harness/out
HARNESS_FIXTURE=serve-viewer-history \
  npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot

# then the same at origin/main, and:
cmp before/serve-viewer-history.light.png            after/serve-viewer-history.light.png
cmp before/serve-viewer-history.dark.png             after/serve-viewer-history.dark.png
cmp before/serve-viewer-history-menu-open.light.png  after/serve-viewer-history-menu-open.light.png
cmp before/serve-viewer-history-menu-open.dark.png   after/serve-viewer-history-menu-open.dark.png
# → silent, four times
```

The full 226-capture `pages-snapshot` run was compared across the two refs as
well. Two `serve-landing-*-filtered.dark` captures came back different; re-run in
isolation on this branch they match `main` byte for byte, so that pair is a
focus-state race between the two parallel workers, not this change. Nothing this
change touches renders on a landing page at all.

| file | what it is |
| --- | --- |
| `toggle-row.light.png` / `toggle-row.dark.png` | the identity row: `History 3 versions unstable ▾` closed, between Theme and Overrides, with nothing between the title and the render |
| `menu-open.light.png` / `menu-open.dark.png` | the menu open — the dated versions, `current` marked the way the revision menu marks a pin, and a note saying what the list covers |

Cropped from the `pages-snapshot` captures of `serve-viewer-history.<theme>` and
`serve-viewer-history-menu-open.<theme>`.

## What the port changed that a capture cannot show

`place()` is gone. The old script built the menu at runtime and then went looking
for somewhere to put it — into `.cp-head-toggles` before the Overrides toggle,
with a fallback above the stage for a page whose toggle row predated it. The
server knows where the control belongs, so it declares the tag there and the
placement question stops existing. That fallback is also what
[`renders/header-status-and-history`](../header-status-and-history/README.md)
records going wrong once already: the script anchored to `.cp-viewer-bar`, #3893
stopped emitting it, and the menu silently disappeared. Markup the server emits
cannot fail that way.

The element reads its inputs off `.cp-viewer`, which sits *below* the row that
declares it. Doing that at connect time worked only because the script tag
happened to sit near the end of `<body>` — correct by accident, and not true at
all of a document assembled in one `innerHTML` write. `<cp-history-menu>` waits
for the parse instead.
