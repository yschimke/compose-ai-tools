# The viewer's Source lane

The code a developer needs in order to *use* a component, on the stage where its render was.
Captures come from the preview-harness (`pages-snapshot.spec.mjs`, the `serve-viewer-source`
fixture) at 1024×720, full page, in both themes.

## What it is

Every catalog card already answered "what does it look like?" and "where is it declared?" (the
`source` link, which opens the preview's own Kotlin on GitHub — annotations, sticker frame, click
tally and variant knobs included). Neither answers the question a developer actually arrives with:
**what do I type to get this?**

The panel answers it with `PlaygroundSourceCleaner`'s output — the same derivation the playground
handoff seeds from, so the code you read and the code you can Run are the same code, and cannot
drift apart into two answers.

![The Source lane in light theme: the chip pressed beside the Live preview chip, the hint reading
"usage source — not a render", and the panel on the stage holding five imports and a nine-line
@Preview composable, with a Copy button and links to the playground and the whole
sticker](source-panel-light.png)

## Not a renderer, so not in the renderer menu

The lane control is a **chip on the toolbar**, beside the design-spec chip, not an `<option>` in the
combo next to it. That combo is headed "Switch renderer" and answers *which engine drew this?*; the
spec chip answers *what was it specified as?*; this answers *what do I type to get this?* Three
different questions, so three controls — the same reasoning that pulled the spec lane out of the
combo in the first place.

It is offered independently of the playground link. Reading the code is useful on any host that can
browse a catalog; *running* it needs one that can compile that catalog, which most of the public
deployment's catalogs have none of. So Source-without-playground is the common case, and the panel
shows the "open in playground →" link only when the server says there is one.

## Both themes

The stage's own backing exists to give a *render* a known field to sit on (the Day / Night / Transparent
chips), and the backend badge names the engine that drew one. Over a code panel neither means
anything — on a dark page the backing framed the panel in a white card and the badge sat on the
first line of source — so both are suppressed for the duration of the lane and the panel brings its
own surface:

![The same lane in dark theme: the panel on its own dark surface with no white stage card behind it
and no backend badge over the code](source-panel-dark.png)

## At rest

Before the chip is pressed the page is the ordinary viewer, and the panel — though present in the
markup — is empty and hidden. That is deliberate on both counts. Server-rendering it empty gives it
a stable place in the stage so nothing jumps the first time it is opened; leaving it *unfilled*
means the snippet is fetched from `/usage/<id>` on first press, so the many visitors who never open
it never cost the host the GitHub read that deriving one takes on a cold cache.

![The same viewer at rest: the Source chip un-pressed beside the Live preview chip, the render on
the stage, and no panel](chip-resting-light.png)
