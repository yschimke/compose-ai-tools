# rc-compare — the players a run did not include

Committed evidence for the published parity page (`rc-compare.html`,
[`render-rc-compare-html.mjs`](../../scripts/design-artifacts/render-rc-compare-html.mjs)) when a
catalog's run covered only some of the Remote Compose players.

Each optional lane is opted into per catalog by
[`design-artifacts-reusable.yml`](../../.github/workflows/design-artifacts-reusable.yml)
(`rc-embedded-lane`, `rc-embedded-jvm-lane`, `rc-cmp-wasm-lane`), and the page emits a column only
for a lane the run recorded a verdict for. A wall three columns wide therefore looked like a page
that had lost its players rather than a run that never included them —
[#4998](https://github.com/yschimke/compose-ai-tools/issues/4998) asked exactly that of the live
`remote-m3` wall. The lede now names the absent ones.

| file | what it is |
| --- | --- |
| `rc-compare.before.light.png` | the page as it shipped: three columns, nothing about the other three players |
| `rc-compare.after.light.png` | the same page naming the players the run did not include |
| `rc-compare.after.dark.png` | the same page in the dark scheme |

All three are headless-Chromium captures of the synthetic fixture, in the shape wear-m3-catalog
publishes:

```
node scripts/design-artifacts/rc-compare-fixture.mjs --out <dir> --omit-lanes embedded,cmp-jvm
```

The `before` capture is that same page with the new paragraph stripped, which is the only rendered
difference.
