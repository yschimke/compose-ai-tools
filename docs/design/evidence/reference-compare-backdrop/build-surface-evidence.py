import base64, os, sys

W = "samples/design-catalog-wear-m3/build/compose-previews/renders"
M = "samples/design-catalog-m3/build/compose-previews/renders"

# (heading, note, png, resolved backdrop, source rung)
ROWS = [
    ("Typical component — Wear FilledButton",
     "51% opaque, ink luminance 217. Legible on the dark stage; the light-on-light ink fights a white one.",
     f"{W}/FilledButton-f83b812e.png", "#1C1B1F", "catalog.surface"),
    ("Outlined button — Wear OutlinedButtonSticker",
     "5% opaque, ink luminance 188. Almost the whole sticker is alpha; the outline and label are all there is.",
     f"{W}/OutlinedButtonSticker-8d071c35.png", "#1C1B1F", "catalog.surface"),
    ("Text — Wear TextMaxLinesTruncated",
     "8% opaque, ink luminance 255 — pure white glyphs on nothing. This is the worst case: on white it is a blank panel.",
     f"{W}/TextMaxLinesTruncated-7f494e93.png", "#1C1B1F", "catalog.surface"),
    ("Wear screen — TimeTextScaffoldTemplate (Large Round)",
     "78% opaque. A full-screen capture paints most of its own ground, but the round mask leaves the corners clear.",
     f"{W}/TimeTextScaffoldTemplate_Large_Round-954aa5b2.png", "#1C1B1F", "catalog.surface"),
    ("Mobile screen — AppScaffoldTemplate (light)",
     "100% opaque — the contrast case. A full mobile screen paints every pixel, so the stage behind it is invisible either way.",
     f"{M}/AppScaffoldTemplate_Light-8f254cf4.png", "#FFFFFF", "catalog.surface"),
]

CHECKER = ("repeating-conic-gradient(#e6e6ea 0% 25%, #f7f7fa 0% 50%) 50% / 14px 14px")

def b64(p):
    with open(p, "rb") as f:
        return base64.b64encode(f.read()).decode()

cards = []
for heading, note, png, backdrop, source in ROWS:
    if not os.path.exists(png):
        print("MISSING", png, file=sys.stderr); continue
    data = f"data:image/png;base64,{b64(png)}"
    cards.append(f"""
    <section class="row">
      <h2>{heading}</h2>
      <p class="note">{note}</p>
      <div class="panels">
        <figure><figcaption>Before — checkerboard</figcaption>
          <div class="stage" style="background:{CHECKER}"><img src="{data}" alt=""></div></figure>
        <figure><figcaption>Before — fixed white (what the scorer still uses)</figcaption>
          <div class="stage" style="background:#fff"><img src="{data}" alt=""></div></figure>
        <figure class="after"><figcaption>After — resolved <code>{backdrop}</code> <span>{source}</span></figcaption>
          <div class="stage" style="background:{backdrop}"><img src="{data}" alt=""></div></figure>
      </div>
    </section>""")

html = f"""<!doctype html>
<html><head><meta charset="utf-8"><title>Backdrop evidence</title>
<style>
  body {{ font: 14px/1.5 -apple-system, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 28px 32px;
         background: #fbfbfd; color: #1a1a1f; }}
  h1 {{ font-size: 20px; margin: 0 0 4px; }}
  .lede {{ margin: 0 0 24px; color: #55555f; max-width: 76ch; }}
  .row {{ margin: 0 0 26px; padding: 0 0 22px; border-bottom: 1px solid #e4e4ea; }}
  .row:last-child {{ border-bottom: 0; }}
  h2 {{ font-size: 14px; margin: 0 0 2px; }}
  .note {{ margin: 0 0 10px; color: #55555f; font-size: 12.5px; }}
  .panels {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; align-items: start; }}
  figure {{ margin: 0; }}
  figcaption {{ font-size: 11.5px; color: #6a6a76; margin: 0 0 6px; display: flex; gap: 6px;
                align-items: baseline; flex-wrap: wrap; }}
  figcaption code {{ font-size: 11px; color: #1a1a1f; }}
  figcaption span {{ font-size: 10.5px; color: #8a8a96; }}
  .after figcaption {{ color: #1a6b3a; font-weight: 600; }}
  .stage {{ display: grid; place-items: center; min-height: 150px; padding: 12px;
            border-radius: 8px; border: 1px solid #dcdce4; overflow: hidden; }}
  .stage img {{ max-width: 100%; max-height: 210px; width: auto; height: auto; display: block; }}
</style></head>
<body>
  <h1>Per-preview backdrop — real catalog renders</h1>
  <p class="lede">Every image is an actual committed <code>@Preview</code> render from
  <code>samples/design-catalog-wear-m3</code> and <code>samples/design-catalog-m3</code>, unmodified.
  All are RGBA with real alpha; only the ground behind them changes across the three columns.</p>
  {''.join(cards)}
</body></html>"""

out = sys.argv[1]
with open(out, "w") as f:
    f.write(html)
print("wrote", out)
