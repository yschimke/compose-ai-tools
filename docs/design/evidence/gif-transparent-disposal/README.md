# Translucent GIF captures smeared their own frames

Frames 30, 36 and 42 of `wear-m3-catalog`'s `MediaTransportMotion` recording — a scalloped
play/pause button morphing against a circle on a transparent canvas — decoded the way a viewer
honours GIF disposal, then composited onto black so the smear is visible in a PNG.

| Pair | What changed |
| --- | --- |
| `transport-{before,after}.png` | `ScrollGifEncoder` wrote `disposalMethod = "none"` on every frame. For frames with alpha that leaves each silhouette on the canvas for the next one to draw over, so the morph accumulates its own outlines into a fat double edge. `restoreToBackgroundColor` clears between frames and each one stands alone |

The measurement behind the pixels, on the same 61-frame recording: **47,609** opaque pixels on the
first frame growing monotonically to **50,780** by the last, because nothing ever cleared. With the
fix it holds flat at **~47,600**.

Opaque sequences are unaffected — they keep `none`, so the scroll captures this encoder was written
for are byte-identical.
