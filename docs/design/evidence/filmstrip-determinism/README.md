# `SharedElementFilmstripPreview` determinism (issue #4097)

`before-run-1.png` and `before-run-2.png` are two renders of the **same commit**, produced
back-to-back by `./gradlew :samples:android:composePreviewRender --rerun
-PcomposePreview.filter=SharedElementFilmstripPreview` before the fix. They differ across roughly
one to three of the five panels (2.99% / 7.97% / 10.96% of the image, depending on which pair you
compare) — the filmstrip re-froze its panels somewhere new on every run, which is what made the
visual-diff bot report this preview as "changed" on PRs that touch nothing near it.

`after.png` is the render the fix produces. Five consecutive `--rerun` renders were byte-identical
(`md5 9bc7cb12cea3bc44c8dfc41aef0f3405`), and each panel now sits where the transition's own easing
puts it at its labelled fraction — pinned by `SharedElementFilmstripPixelTest`.
