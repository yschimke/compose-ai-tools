# A report filed from the viewer carries a parity locator

[#5000](https://github.com/yschimke/compose-ai-tools/issues/5000). `parity/issues.json` is built
from the `compose-parity-locator/v1` fence, and only the focused comparison emitted one. The
**viewer** — the report form reachable from every preview page and every catalog grid card — filed a
complete-looking issue with a `parity:` label and no fence, so `buildIssueIndex` skipped it as
`NO_LOCATOR` and the component's card never badged. Both ends were silent:
[m3-catalog#256](https://github.com/yschimke/m3-catalog/issues/256) and
[#269](https://github.com/yschimke/m3-catalog/issues/269) are open, labelled, and absent from the
index, while the form beside them told the reporter their label fed it.

The fix is in [`yschimke/compose-preview-server`](https://github.com/yschimke/compose-preview-server),
which owns the server since extraction #4732. The viewer's report context now names the preview's
design reference — resolved exactly as the comparison link beside it resolves one — so
`ServeIssueReport.locator` returns a locator, and the block's one override-dependent field is left as
an `{{overrides}}` placeholder the page's own script fills from live control state, on the same pass
that fills `{{render}}`. The *score* stays exclusive to the comparison: it is the only page that
measures one.

The shots are the committed page fixture, production CSS and JS, with the report disclosure opened:

```sh
UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :server:test --tests '*ServeWebFixtureTest*'
```

## Before

The panel a viewer report is filed from, with no locator in its body — so nothing it files reaches
the index the note under "Where does it belong?" promises.

![The viewer's report panel before: Summary, then Where does it belong?, then the submit button](report-form-before.png)

## After

The body now carries a locator, and the locator is what puts **Show this issue on** in the panel —
the scope control the comparison has had since batch 02, offering the same component-wide or
variant-only choice.

![The viewer's report panel after: Summary, then Show this issue on, then Where does it belong?, then the submit button](report-form-after.png)

## The body itself

What the served form now prefills for `com.example.ProfileScreenPreview`, appended after the
existing prose table (unchanged above this point):

```
[Open this preview](https://preview.coo.ee/compose-m3/p/com.example.ProfileScreenPreview)

```compose-parity-locator/v1
repository: yschimke/compose-ai-tools
system: compose-m3
component: Profile/Screen
preview: com.example.ProfileScreenPreview
reference: profile-screen-figma
variant:
overrides: {}
revision: yschimke/compose-ai-tools@design-artifacts/compose-m3
```
```

The template the page's script fills is the same body with `overrides: {{overrides}}` on that line.
A visitor with scripting off files the served form above, whose overrides are the ones their own URL
asked for and whose render link is the matching one.
