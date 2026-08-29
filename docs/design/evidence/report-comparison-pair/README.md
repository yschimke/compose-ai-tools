# A report filed from a comparison carries the pair

Two states of the server's own bug-report page (`/report-bug`), captured from the committed page
fixtures by `pages-snapshot.spec.mjs`, so they show exactly what the server emits.

| File | Fixture | What it shows |
| --- | --- | --- |
| `before-base-render.png` | `serve-report-bug` | A report filed from the **viewer**: one render under "The base render of that preview". Unchanged by this fix, and the shape a report from the *comparison* also had. |
| `after-pair.png` | `serve-report-bug-compare` | The same page reached from the **focused comparison**: both outer panels under "The pair you were comparing", with the prose naming the one panel that is still missing. |
| `after-pair-dark.png` | `serve-report-bug-compare` | The same, in dark. |

**Why the middle capture is the argument.** The comparison's whole subject is a design reference and
a render disagreeing, and both are ordinary URLs on this server. A report from it used to carry one
of them — [#4765](https://github.com/yschimke/compose-ai-tools/issues/4765), where the filed issue
(`yschimke/wear-m3-catalog#144`) opened showing a single button while the complaint was about how it
differed from the spec beside it. The picture contradicted the report it was filed with.

**What is still a paste, and why the prose changed with the picture.** The diff panel between the two
is composed in the browser out of these very pixels and has no URL, so it cannot be embedded from
here. The old wording — "not the spec triptych, the wipe, or any other view the browser composes" —
was right about a page showing one render and wrong about this one: what is missing is no longer
everything the browser composes but one specific panel. Saying which is what tells a reporter whether
a capture is still worth taking, and the capture control that takes it is a button away in the
launcher.

The per-preview report (`ServeIssueReport`, the "report a catalog issue" form on the comparison
itself) carries the same pair as a two-cell markdown table, which GitHub lays out side by side. That
one has no page of its own to screenshot: it lives in a hidden input and is only visible once filed.
`ServeIssueReportTest` holds its shape, and the committed `serve-reference-compare.html` fixture
carries the exact body the server now serves.
