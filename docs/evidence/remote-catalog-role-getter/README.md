# Remote Material 3 catalog roles reading a sibling's colour

`RemoteCatalogValues.propertyOrNull` matched a role's accessor with `startsWith`, and
`getPrimary` is a prefix of `getPrimaryDim` and `getPrimaryContainer`. Eleven of
`RemoteColorScheme`'s twenty-nine roles collide that way, so each resolved to whichever
sibling `Class.getMethods()` happened to list first — an order the JVM does not specify.

The two "before" images are **both** renders `main` committed as its own baseline for
`Remote theme colours`, at different times, from code that differed in nothing that
touches rendering. That they disagree with each other is the bug; neither is a
regression from the other.

| file | main baseline | what it gets wrong |
| --- | --- | --- |
| `before.png` | [`fe856dcd`](https://github.com/yschimke/compose-ai-tools/commit/fe856dcd926c77afddd6d41afa36710bd2cedad6) (from `ab5e96a2`) | `primary` reads `primaryDim`'s `#FFD0BCFF` |
| `before-alternate.png` | [`5ec464a5`](https://github.com/yschimke/compose-ai-tools/commit/5ec464a5f2b1b3b2377de6e67ed8dbcae870f75a) (from `4342f07d`) | `surfaceContainer` reads `surfaceContainerLow`'s `#FF272430`; `onSurface` reads `onSurfaceVariant`'s `#FFCAC4D0` |
| `after.png` | — | rendered locally with the fix; every role reads its own getter |

`surfaceContainerHigh` is `#FF494453` in all three. Nothing is a prefix of its name, so it
was never reachable by the wrong accessor — which is what makes the collision, rather than
some general instability in the renderer, the explanation.

Across the sixteen most recent baseline updates that touched this file, `main` committed
four distinct PNGs for it and returned to earlier ones repeatedly; in fourteen of the
fifteen whose parent is available, it was the only file under `renders/` that moved.

## What the fixed render turned out to be

`after.png` is **byte-identical to `c8fc1c6d`**, one of the four PNGs `main` was already
rotating through (it committed that content four of the sixteen times). So one of the four
was right all along; the fix does not introduce a new rendering, it makes the render land
on the correct one every time instead of one time in four.

The token sidecar confirms the roles the collision used to swap:

| role | before (`before-alternate.png`) | after |
| --- | --- | --- |
| `primary` | `#FFD0BCFF` — `primaryDim`'s | `#FFE9DDFF` |
| `surfaceContainer` | `#FF272430` — `surfaceContainerLow`'s | `#FF332E3C` |
| `onSurface` | `#FFCAC4D0` — `onSurfaceVariant`'s | `#FFF6EDFF` |

`onPrimaryContainer` and `onSurface` are both `#FFF6EDFF` after the fix. That one is real:
their getter names do not collide, and Wear M3 genuinely gives them the same colour.
