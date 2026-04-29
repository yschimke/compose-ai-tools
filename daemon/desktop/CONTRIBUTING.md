# Contributing to `:daemon:desktop`

## No-mid-render-cancellation invariant — **review checklist**

Per [DESIGN.md § 9](../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement) and
[PREDICTIVE.md § 9](../docs/daemon/PREDICTIVE.md#9-decisions-made), the desktop daemon must never
abort a render mid-flight. Half-disposed Compose graphs leak `Recomposer` references, partly-built
`ImageComposeScene`s leak Skia native `Surface`s, and the worst observable failure shape is silent
visual drift across previews — caught only by CI pixel-diff. Aborts also defeat the daemon's
warm-runtime amortisation by forcing a sandbox tear-down.

**Reviewer checklist** for any change under `daemon/desktop/src/main/`:

1. **No `Thread.interrupt()` calls** on the render thread (or anywhere reachable from
   `DesktopHost`'s render loop). The render thread's `Thread` reference is private to
   `DesktopHost`; no test or production code outside this module should be obtaining it.
2. **No `Thread.interrupted()` polling** inside the render loop body or `RenderEngine.render`.
   The render thread runs to completion regardless of interrupt status; the only legitimate use of
   `Thread.currentThread().interrupt()` is the standard "restore interrupt status after a caught
   `InterruptedException` on *this* thread" pattern (see `DesktopHost.runRenderLoop`'s
   `catch (e: InterruptedException)` block).
3. **Shutdown is poison-pill, not abort.** `DesktopHost.shutdown` enqueues `RenderRequest.Shutdown`
   on the queue and joins the worker; it must not call `interrupt()`, must not close any in-flight
   stream the engine is using, and must not narrow the timeout below the worst-case render budget
   (currently 30s for the Gradle plugin's daemon disposal path).
4. **`scene.close()` in a `try/finally`.** Every `ImageComposeScene` constructed in `RenderEngine`
   must be closed in a `finally` even when the render body throws — this releases the native Skia
   `Surface`. B-desktop.1.4's render body already does this; future renderers (B-desktop.1.7+) must
   preserve it.
5. **SIGTERM hook stays minimal.** `DaemonMain.installSigtermShutdownHook` should only close stdin
   and call `host.shutdown(timeoutMs)`. Anything more (e.g. forcibly killing threads, calling
   `System.halt`) defeats the invariant the hook is there to enforce.

If you need to introduce *any* of `Thread.interrupt()`, `Thread.interrupted()`, or
`Thread.currentThread().interrupt()` under `daemon/desktop/src/main/`, please justify it in
the PR description and tag a maintainer for review. The regression test
[`CancellationInvariantTest`](src/test/kotlin/ee/schimke/composeai/daemon/CancellationInvariantTest.kt)
(B-desktop.1.6) covers the runtime case; this checklist covers the static case.

A static-check rule (detekt or similar) would automate this, but introducing detekt for a single
rule isn't worth the dependency churn — see B-desktop.1.6's task brief. If detekt lands later for
unrelated reasons, this checklist is the source for the rule body.
