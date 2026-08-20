# A live lane that stops painting and never says so (issue #4313)

A live indeterminate progress indicator sat motionless on `preview.coo.ee` with
the socket open and the badge still reading **Live**. The daemon was not the
problem: the frozen frame was ~1.25 s into the animation's 5000 ms cycle, well
past the phase the baked snapshot shows, so the held composition's clock had
been advancing and something downstream had stopped consuming it.

That something is the browser's frame pump. Since #4298 the viewer drains one
frame per animation frame instead of painting from the socket handler, and the
tick re-armed `requestAnimationFrame` *after* the paint with nothing around it.
`frameBlob` throws on a payload `atob` will not decode, so one such frame
unwound the tick before it re-armed and the chain was never scheduled again —
while `frameLoopRunning` stayed `true`, which is the flag `startFrameLoop`
early-returns on, so nothing could restart it for the life of the page.

| file | what it is |
| --- | --- |
| `before.png` | the deployed 1.22.0 `viewer.js`, one undecodable frame at seq 5: the ring is byte-identical at t = 2 s, 6 s and 11 s while the stream keeps arriving |
| `after.png` | the same stream against the fixed bundle: the bad frame is dropped, every later frame paints |

Both are the **real** server-rendered viewer page driving the **real**
`viewer.js` bundle in Chromium. Only the socket is a stand-in: it pushes frames
on the live wire shape (`{type:"frame", seq, codec, dataBase64}`) at the lane's
250 ms cadence, drawing a wear-style sweeping ring so the capture reads as the
reported symptom rather than as coloured squares.

## Re-running it

`page.html` is the viewer page as served (fetch it from any catalog's
`/p/<preview>?mode=live`; a signed-out page renders the sign-in anchor instead
of `#cp-live-toggle`, so swap that in and drop the `disabled` on `#cp-live`),
and `assets/` holds the page's own scripts.

```
node server.mjs                      # SCENARIO=good|badframe, SHAPE=ring, VIEWER_JS=<bundle>
node drive.mjs                       # prints every distinct paint, verdict FROZEN / still animating
OUT=strip.png node filmstrip.mjs     # the contact sheets above
```

`drive.mjs` is what bisected it: `badframe` against the bundle built from
`ae376d3^` keeps animating, and against `ae376d3` freezes after four frames.

The permanent guard is a unit test, not this harness —
`cli/serve-web/test/liveFramePainter.test.ts` drives `pumpFrames` with an
injected scheduler and asserts a throwing paint loses its own frame and nothing
else. The loop had no test at all before, because it was inline in two
components with a `requestAnimationFrame` no test could step.
