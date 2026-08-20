# A catalog's motion captures, on one page

A capture is per-component surface, and the viewer's Motion lane is the right
home for reading one. It is the wrong home for the catalog-wide question:
**does this design system move consistently?** Two containers that morph on the
same spatial spring and a third that cross-fades is a system bug, and it is
invisible from three separate component pages that each show their own
recording in isolation.

It is also the only view that answers "what has motion at all". Captures are
rare — most components publish only a still — so before this the way to find
one was to open components until a Motion chip appeared.

| file | what it is |
| --- | --- |
| `entry-before.png` | the catalog landing's `⋯` menu before: one entry, Transparent |
| `entry-after.png` | the same menu with **4 motion captures**, the browser's entry point |
| `browser-at-rest-light.png` | the browser as it loads — one row per component, every card on its baked still, nothing playing |
| `browser-at-rest-dark.png` | the same, dark |
| `browser-playing-light.png` | after **Play all** — every card swapped to its recording, the ▶ cue gone, the button reading Stop all |
| `browser-playing-dark.png` | the same, dark |
| `browser-dark-take.png` | after **Theme → Dark** — the themed card back on its dark take's still, its neighbours untouched |
| `collapse-catalog-before.png` | the real `compose-m3` catalog before the fold: 320 cards over ten files — seventy of them the same two APNGs |
| `collapse-catalog-after.png` | the same catalog after: **5 recordings across 5 components**, on one screen |

Every harness shot comes out of the preview harness's own path:
`serve-motion-index` for the browser (its `motion-index-playing` state presses
Play all over a stubbed capture, its `motion-index-dark-take` state presses
Theme → Dark) and `serve-landing-declared-themes`'s `actions-menu` state for the
entry point, which is the one fixture that opens that menu. The two
`collapse-catalog-*.png` shots are the published `m3.preview.coo.ee/motion`
served from its own metadata, before and after, and are a one-off record of the
change rather than a baseline the bot diffs.

## Why the entry point is a chip in the `⋯` menu

That menu is where this catalog's *destinations* already live — the comparison
views, the parity dashboard, the playground. The motion browser is one of those:
somewhere a visitor goes, not something they read on the way past.

The count is in the label for the same reason the pages chip carries one — one
recording and thirty are different offers — and it is the same count the route
gates on, so the chip never leads to a 404 and a catalog that records nothing is
never offered an empty page.

## Why nothing plays until it is asked to

Same posture as the viewer's lane, and for a reason that is about bytes as much
as taste: an `<img>` starts playing an APNG the moment its `src` is assigned, so
**assigning `src` is starting playback**. A grid that shipped with the recordings
in place would decode thirty animations at once, at a reader who opened the page
to compare them one at a time.

So each card ships with its component's still — the same baked pixels the
catalog grid shows — and swaps to the capture only on a press, or on **Play
all**. Stopping restores the still, which is what actually stops the decoding.
That also makes `prefers-reduced-motion` a non-question here: there is no
autoplay to suppress, and the control is a button rather than a hover, so it
works from a touch screen and a keyboard.

## Grouped by component, one card per distinct recording

A capture is declared on a component but published on every *render* of it: a
catalog's `variants.json` hangs the same recording off the default, the disabled
state, the focus ring, the RTL variant and every breakpoint. Listed one card per
render × capture, `compose-m3`'s five moving components filled this page with
**320 cards pointing at ten files** — the same two APNGs seventy times over under
Icon Button Filled, a screen and a half of identical thumbnails before the next
component reached the page. `collapse-catalog-before.png` is that page.

So the page groups by the component identity the grid already folds its state /
theme / props / size axes onto, and keeps one card per *distinct* capture inside
it. What is folded is the repetition, not the captures: a component with two
genuinely different recordings still shows two cards, because "Baseline swaps
the shape, Expressive travels between them" is one component and two things to
compare. The at-rest shot shows that case — `Switch · On` carries *Toggle on*
and *Thumb settle* side by side — and the component is named once, to the left
of its cards, with the caption every one of its recordings shares printed there
rather than under each.

## The light and the dark take are ONE recording

A catalog records a gesture once per theme and writes the caption once, so
`iconbutton-filled…__light` and `…__dark` are not two recordings to compare —
they are one recording photographed twice, and side by side they were half the
page. They fold onto a single card, and the toolbar's **Theme** control swaps
every such card between its takes in place: the recording, the still it returns
to, the accessible name and the deep link all move together, so a reader who
picked Dark is never left with a light thumbnail over a dark recording or a link
back to the light render. `browser-dark-take.png` is that state.

The control writes the choice where the rest of the catalog reads it — the
`?theme=` param, the catalog's own `localStorage` key, and `cpPageTheme` for the
chrome — so picking Dark here leaves the grid and the viewer dark too. A catalog
that records a single theme is offered no control at all rather than a pair of
buttons one of which does nothing.

## Names, and the hand-off to the viewer

Names come from `MotionCaptureLabels`, the same split the viewer's picker uses,
so a recording is called the same thing in both places: the caption's first
clause as the title, the caption in full underneath (or once, above the cards,
when every recording of a component shares it). Where two captures of one
component would end up with the same title, the ids decide: a light/dark pair is
named `(Light)` / `(Dark)` rather than `1` / `2`, which is a label a reader can
act on — that matters most in the viewer's picker, which still lists both takes.

Each card deep-links to `?mode=motion&motion=<id>`, which opens the viewer
already on that recording rather than on the component's first one — so the
browser hands off to the frame-by-frame transport instead of trying to be it.
An `<img>` cannot be paused, sought or rate-controlled from script. That is what
the viewer's canvas player is for, and why every card links to it.
