# Accessibility legend: a merged stop's rolled-up label (issue #4253)

The accessibility overlay for wear-m3-catalog's `button-filled__ideal__default`
(`?knob.icon=true&fontScale=1`) — the render the issue reported, with the
hierarchy the live daemon returned for it:

```json
{"label":"","states":["clickable"],"boundsInScreen":"16,16,209,120"}
{"label":"Filled","role":"TextView","merged":false,"boundsInScreen":"104,50,181,86"}
```

`before.png` — the focus stop reads `(unlabelled)` beside a button with the word
"Filled" plainly on it. ATF reports each element's own `contentDescription` /
`text`, and a Wear `Button(icon = …, label = { Text("Filled") })` keeps the click
on the merging surface and the copy on the child nobody stops on.

`after.png` — the same overlay with the label rolled up from the descendants the
stop merges, which is what TalkBack announces.

Regenerate (both frames, from the repo):

```bash
A11Y_LABEL_DEMO_DIR=/tmp/a11y-evidence \
  ./gradlew :data-a11y-core:testDebugUnitTest --tests '*AccessibilityLabelDemoRender*'
```

Set `A11Y_LABEL_DEMO_SOURCE=<png>` to overlay a real render (these frames used
the reported one); without it the generator paints a stand-in of the same shape.
