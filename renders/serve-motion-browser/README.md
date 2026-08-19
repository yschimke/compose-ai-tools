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
| `browser-at-rest-light.png` | the browser as it loads — every card on its component's baked still, nothing playing |
| `browser-at-rest-dark.png` | the same, dark |
| `browser-playing-light.png` | after **Play all** — every card swapped to its recording, the ▶ cue gone, the button reading Stop all |
| `browser-playing-dark.png` | the same, dark |

Every shot comes out of the preview harness's own path: `serve-motion-index`
for the browser (its `motion-index-playing` state presses Play all over a
stubbed capture) and `serve-landing-declared-themes`'s `actions-menu` state for
the entry point, which is the one fixture that opens that menu.

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

## One card per capture, not per component

A component with two recordings has two things to compare, and that is
frequently the point — "Baseline swaps the shape, Expressive travels between
them" is one component and two captures. The at-rest shot shows the case:
`Switch · On` appears twice, once as *Toggle on* and once as *Thumb settle*.

Their names come from `MotionCaptureLabels`, the same split the viewer's picker
uses, so a recording is called the same thing in both places: the caption's
first clause as the title, the caption in full underneath. Each card deep-links
to `?mode=motion&motion=<id>`, which opens the viewer already on that recording
rather than on the component's first one — so the browser hands off to the
frame-by-frame transport instead of trying to be it.

An `<img>` cannot be paused, sought or rate-controlled from script. That is what
the viewer's canvas player is for, and why every card links to it.
