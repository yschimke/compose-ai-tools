# Data-product payload fixtures

Recorded `{ kind, payload }` envelopes, one file per harness scenario, used as the
**second** validation source in
[`scripts/validate-report-schemas.mjs`](../../../scripts/validate-report-schemas.mjs):
every schema in `schema/` is checked against its hand-authored
`x-composeai.example` *and* against every matching payload embedded here.

That second source is the one that catches a schema which is self-consistent but no
longer describes what the daemon actually emits.

These were read out of `vscode-extension/preview-harness/fixtures/` until the VS Code
extension moved to
[yschimke/compose-preview-vscode](https://github.com/yschimke/compose-preview-vscode).
The validator tolerated a missing fixtures directory — a sparse-checkout
accommodation — so the split would not have *broken* it; it would have quietly
dropped half the validation and left every schema checked only against its own
example. They are copied here instead, because the payloads are this repo's own
daemon output, and the extension only ever held recorded copies.

Regenerate a fixture from the extension's harness if a payload shape changes; the
`kind` values covered here are:

| file | kinds |
| --- | --- |
| `history-diff.json` | `history/diff/regions` |
| `inspection-tree.json` | `compose/semantics`, `layout/inspector` |
| `permissions.json` | `compose/permissions` |
| `remotecompose-state.json` | `compose/remotecompose` |
| `text-strings.json` | `text/strings`, `fonts/used`, `i18n/translations` |
| `theming.json` | `compose/theme`, `compose/wallpaper` |
