# Regenerating the Wear widget fixture

`samples/wear-widget/src/main/kotlin/com/example/wearwidget/ActivitySummaryWidget.kt` is generated
by `WearWidgetCodeExporter` in
[`yschimke/compose-preview-server`](https://github.com/yschimke/compose-preview-server) and checked
in here, because compiling and rendering it is the half of "the generator works" that no test on
that side of the repository split can answer.

Two changes landed there, and the checked-in copy predated both:

| | |
| --- | --- |
| [#525](https://github.com/yschimke/compose-preview-server/pull/525) | A row's own `verticalAlignment` reaches the generated `RemoteRow`. The canvas has always read it and defaults a row to `CenterVertically`; the emitter did not, so a generated widget drew top-aligned. |
| [#524](https://github.com/yschimke/compose-preview-server/pull/524) | The generated `@Preview` is one preview at the container's widest footprint rather than `@PreviewParameter`'s fan-out. |

Both PNGs are this fixture, compiled into `:samples:wear-widget` and rendered by its own preview
lane at 216×124dp.

| File | Shows |
| --- | --- |
| `before-stale-fixture.png` | The checked-in copy. `steps` sits at the top of its row, against the top of `8,412`. |
| `after-regenerated.png` | The regenerated copy. `steps` is centred against `8,412`, as the canvas draws it. |

The visible difference is the row alignment. The preview change is not visible in a single frame —
it shows up as one render file rather than two, `…Squircle_Preview-a0071b12.png` replacing
`…_PARAM_0.png` and `…_PARAM_1.png`.
