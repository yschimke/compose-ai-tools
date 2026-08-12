# Live-lane mouse-drag selection — before / after

Rendered evidence for #3697. Both frames are **real live-lane output**: pulled
off the `/{system}/ws/{previewId}` stream of a daemon-backed
`compose-preview serve --catalogs compose-m3`, driving
`textfield-filled__ideal__default__dark` with the exact wire sequence a browser
drag produces —

```
click       (410, 116)     ← focuses the field, caret lands past the last glyph
pointerDown (147, 116)  ┐  ← the viewer defers the press until the first move,
pointerMove (164, 116)  ┘    so these two arrive in one tick, nothing between
pointerMove … × 11         ← on out to (344, 116), past the end of the text
pointerUp   (344, 116)
```

| File | What it shows |
|---|---|
| `mouse-drag-before.png` | `main`: no highlight at all. The press at x=147 never became the selection anchor, so the drag extended from the caret the click left at the last offset — and since it ends past the end of the text, that range is empty. Only the caret differs from the pre-drag frame (0.07% of pixels). |
| `mouse-drag-after.png` | The same drag with the press settled by a render before the move can arrive: the selection anchors where the user pressed and `led` is highlighted (1.49% of pixels). |

The failing half is a race, not a coordinate error: with a render tick — or just
20 ms — between the press and the first move, `main` selects too. That is why
every existing drag test renders between dispatches and none of them caught it,
and why the fix puts the settling render inside `ScenePointerDispatch.press`
rather than in any one call site.
