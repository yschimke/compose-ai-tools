# preview server — split-Skiko repair + baked fallback (before / after)

Evidence for compose-ai-tools#4220: `m3-catalog`'s live render lane on `preview.coo.ee` tripped on
`UnsatisfiedLinkError: ParagraphKt._nGetUnresolvedCodepointsCount`, and the tripped breaker took the
whole catalog's images down with it.

Reproduced end to end on a local `compose-preview serve`, fetching the real published catalog:

```
compose-preview serve --catalogs m3-catalog@yschimke/m3-catalog \
  --trust-store <store trusting yschimke/m3-catalog@design-artifacts/*> \
  --allow-render-trusted --public
```

The bundle records `org.jetbrains.skiko:skiko-awt:0.148.2` and **no** `skiko-awt-runtime-<host>` —
the platform native reaches a Gradle-resolved classpath as a transitive artifact, not as a recorded
coordinate. Promoted ahead of the server sidecar, those bindings link against the sidecar's own
`libskiko` 0.144.6, which never exported the symbol.

| capture | state |
| --- | --- |
| `catalog.before.png` | on `origin/main` — lane tripped, every card is "Preview image failed to load" while the banner promises baked snapshots |
| `catalog.after-lane-down.png` | with the latch fix, Skiko repair deliberately disabled so the lane still trips — banner unchanged, every card serves its published baked PNG |
| `catalog.after-lane-repaired.png` | with both fixes — the native is resolved alongside its bindings, the lane never trips, no banner, live sessions offered again |

The middle capture is the one that isolates the recovery half: it is a genuinely broken lane that no
longer blacks out the pixels it was never the source of.
