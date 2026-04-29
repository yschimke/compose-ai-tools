# Contributing to `:daemon:harness`

Quick notes for daemon-harness maintainers. The harness drives the preview daemon end-to-end via
JSON-RPC over stdio; see `docs/daemon/TEST-HARNESS.md` for the architecture.

## When to run `./gradlew :daemon:harness:regenerateBaselines`

The PNG baselines under `daemon/harness/baselines/desktop/<scenario>/` are the
ground-truth images each real-mode scenario pixel-diffs against. Regenerate them when:

- you intentionally change a fixture composable (`daemon/desktop`'s
  `RedFixturePreviews.kt`) — the rendered output changes by design;
- you upgrade Compose Desktop / Skiko and the per-pixel jitter pushes scenarios past the
  default `PixelDiffTolerance` (per-pixel ≤ 3, aggregate ≤ 0.5%, absolute cap 50);
- you change the daemon's render pipeline (`DesktopHost`, `RenderEngine`, `RenderSpec`) in a way
  that changes the pixels of an existing baseline;
- you add a new real-mode scenario whose first run captures a fresh baseline.

The reviewer of the PR eyeballs the resulting baseline diff in the PR's git diff (PNGs render
inline on GitHub) — that human sanity check is what makes the captured-PNG approach safe at v1.5
scope. Don't regenerate to "fix" a flaky pixel-diff without explaining why the underlying jitter
landed; flaky baselines are usually a renderer determinism bug worth chasing.

## How `regenerateBaselines` differs from a normal `test` run

```
./gradlew :daemon:harness:regenerateBaselines
```

is equivalent to running `:daemon:harness:test` with
`-Pharness.host=real -Dcomposeai.harness.regenerate=true` and a `*RealModeTest` filter. The
`composeai.harness.regenerate=true` system property flips the
`HarnessTestSupport.regenerateBaselines()` switch — every scenario's
`diffOrCaptureBaseline(...)` call short-circuits to "always overwrite" rather than
"capture-on-first-run-and-diff-thereafter". Two runs in a row should produce byte-identical
PNGs; if they don't, the desktop renderer has a non-determinism source worth chasing
(typically: timestamp metadata in PNG chunks, a frame-rate-driven animation, or non-deterministic
font hinting).

## How to add a new real-mode scenario

1. Add the scenario's fake-mode counterpart first (if it doesn't already exist) under
   `src/test/kotlin/ee/schimke/composeai/daemon/harness/S<N>...Test.kt`. Keep the gap-flag KDoc
   pattern: the v1 daemon doesn't implement everything TEST-HARNESS § 3 imagines; the existing
   tests document each gap so they flip the moment the gap closes.
2. Add the real-mode test as `S<N>...RealModeTest.kt` in the same directory. Skip via
   `Assume.assumeTrue(HarnessTestSupport.harnessHost() == "real")` so the default fake-mode CI
   run is unaffected.
3. Use `realModeScenario(...)` to build the launcher + manifest.
4. If the scenario produces PNGs, call `diffOrCaptureBaseline(...)` for each — it handles the
   capture-on-first-run vs pixel-diff vs regenerate-overwrite branching for you.
5. Run `./gradlew :daemon:harness:test -Pharness.host=real --tests "*S<N>..."` once to
   capture the baseline PNGs, then commit them under
   `daemon/harness/baselines/desktop/s<N>/`.
6. Confirm `./gradlew :daemon:harness:test` (default fake) still skips the new test (it
   prints `s<N>...real_mode SKIPPED` in the JUnit output) and the existing fake-mode catalogue
   stays green.

## Don't widen `:daemon:core`

The harness's renderer-agnostic invariant (DESIGN § 4) is that the production classpath only
depends on protocol types + `RenderHost` + `JsonRpcServer`. The desktop daemon's main classes
are pulled in via `testImplementation` only — they're on the harness's *test* runtime classpath
but never on the production one. New tests that need fixture composables should add them to
`daemon/desktop`'s `testFixtures` source set (alongside `RedSquare`, `BlueSquare`,
`GreenSquare`, etc.), not to a new module. The `PreviewManifestRouter` is a test-only shim
gated on `composeai.harness.previewsManifest`; it goes away once `B2.2 — IncrementalDiscovery`
lands a real `previewId` field on `RenderRequest`.
