# A design page takes the whole viewport width

Issue #4750. A design page's subject is one wide image — a Figma specimen sheet
the design file drew thousands of pixels across — and the chrome held it inside
the same 1440px column the prose pages use. On the 2560px display the issue was
filed from, the sheet landed in a little over half the glass and every specimen
on it was sub-pixel.

Both shots are the committed `serve-design-page` fixture, rendered through the
preview-harness' static server in a **2560×1328** viewport (the reporter's) —
not the harness' own 1024px capture, which is below the old cap and where this
change is invisible by construction.

| file | what it is |
| --- | --- |
| `before-1440-column.png` | the sheet capped at 1440px, ~44% of the viewport unused on either side |
| `after-full-width.png` | `<body class="cp-wide">` — the sheet, and the chrome above it, span the viewport |

The stage keeps the sheet's own aspect ratio (`--cp-page-aspect`, from the
export's viewBox), so a wider stage is a proportionally taller one: the sheet is
drawn larger and scrolls, rather than being scaled to fit. That is the trade the
issue asks for — the specimens are legible at 1:1 now, and the page's existing
zoom/pan gestures still address the rest.

Only the design page opts in. A page of prose still wants the measure.
