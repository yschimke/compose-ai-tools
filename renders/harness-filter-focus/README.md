# The sidebar-filter captures rendered two ways from the same code

Committed evidence for pinning the focus state of `#cp-search` in the three
page-snapshot states that type into it.

## The flake

`serve-landing-grouped-pages-section-match.dark` was rendered **both** of these
ways on `vscode-preview/main`, from code that did not change in between:

focused — today's baseline | unfocused
--- | ---
![focused](focused.png) | ![unfocused](unfocused.png)

The difference is entirely the field's focus chrome: the `:focus` outline and
Chromium's native `<input type="search">` clear button. `page.fill()` leaves the
field focused, so whether those were painted came down to timing.

That is what put a spurious *"1 changed across 278 captures"* comment on
[#4015](https://github.com/yschimke/compose-ai-tools/pull/4015), whose diff could
not reach that page at all.

## Why it was expensive to leave

Locally the focused variant won **5 runs out of 5**, so whatever loses the focus
needs a loaded machine. Being rare is precisely what made it costly: it does not
show up when you go looking, it shows up on whoever's PR is open — as a visual
diff they have to read, disprove, and then explain in their own review thread.

## The fix, and what it is not

The three filter states now blur the field after typing, so the capture is about
the one thing each of them claims: what the filter did to the **list**. The two
crops above are byte-identical to this PR's before and after, which is the point
— the fix pins CI onto one of the two renderings it was already producing rather
than inventing a third.

The focused field is **not** left uncovered. `serve-landing-sections-filter-focus`
exists for exactly that appearance, and unlike a pixel comparison it *waits* on
`document.activeElement`, so it fails loudly rather than drifting quietly if
anything ever really does steal focus from the box.

This is a fix to the harness, not to the product. No page-side cause was found
for a focused filter losing focus — `<cp-catalog-toolbar>`'s reflow is the
obvious suspect and is already guarded against re-inserting a node where it
already is (which is what a previous round of this same symptom turned out to
be), and it does not run at the 1024×720 capture width. So this removes the
noise without claiming there is nothing behind it; the `filter-focus` state is
what would catch it if there is.
