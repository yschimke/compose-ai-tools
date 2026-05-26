# Next-session handoff: `:tui-cli` + Mosaic fork

Branch: `agent/mosaic-tui-preview` (compose-ai-tools), 6 commits ahead of `main`.
Mosaic fork: `yschimke/mosaic#1` (`compose-ai-tools` branch).

## Where you're picking up

The TUI module is in tree and runnable, the e2e harness captures 9 screenshots end-to-end,
and the build is wired through `mavenLocal()` against a local Mosaic publication. **Nothing
in `:tui-cli` consumer code uses the fork's new composables yet** — the upgrade is plumbing
only. The fork's `RawText` and `Image` composables are what the next session should adopt.

Latest commit on this branch:

```
30ce15b docs(tui-cli): note env-allowlist now covers Mosaic publish hosts
```

## Step 1 — verify the env-allowlist update reached your session

Before doing anything else, run:

```bash
for url in \
  https://download.jetbrains.com/kotlin/native/llvm-11.1.0-linux-x64-2.tar.gz \
  https://ziglang.org/download/0.15.1/zig-x86_64-linux-0.15.1.tar.xz \
  https://download.java.net/java/early_access/jextract/21/1/openjdk-21-jextract+1-2_linux-x64_bin.tar.gz
do
  printf '%-90s ' "$url"; curl -sI -o /dev/null -w 'HTTP %{http_code}\n' "$url"
done
```

- **All three `200`/`302`** → clean path, follow Step 2a.
- **Any `403`** → session is on the old policy. Use Step 2b instead (patched path that
  works today).

## Step 2a — clean publish (preferred, requires env allowlist active)

```bash
# 1. Make sure the mosaic checkout is at the right branch and has NO local patches.
cd /home/user/mosaic 2>/dev/null || git clone --branch compose-ai-tools https://github.com/yschimke/mosaic.git /home/user/mosaic
cd /home/user/mosaic
git checkout compose-ai-tools
git diff --quiet && echo "clean ✓" || { git diff; echo; echo "STOP — revert any patches first (Step 2b set them; they're not part of PR #1)"; exit 1; }

# 2. Publish — JDK 21 because mosaic build-support uses HttpClient.use { } (Java 21+ AutoCloseable).
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew \
  :mosaic-runtime:publishJvmPublicationToMavenLocal \
  :mosaic-runtime:publishKotlinMultiplatformPublicationToMavenLocal \
  :mosaic-terminal:publishJvmPublicationToMavenLocal \
  :mosaic-terminal:publishKotlinMultiplatformPublicationToMavenLocal \
  :mosaic-tty:publishJvmPublicationToMavenLocal \
  :mosaic-tty:publishKotlinMultiplatformPublicationToMavenLocal \
  :mosaic-tty-terminal:publishJvmPublicationToMavenLocal \
  :mosaic-tty-terminal:publishKotlinMultiplatformPublicationToMavenLocal \
  -x test

# 3. Verify the JVM jar contains the JNI .so resources that this session's publish
#    lacks. Presence of the native libs is the key signal — that's the difference
#    between a runtime-usable artefact and a build-test-only one.
unzip -l ~/.m2/repository/com/jakewharton/mosaic/mosaic-tty-jvm/0.19.0-SNAPSHOT/mosaic-tty-jvm-0.19.0-SNAPSHOT.jar | grep -E '\.(so|dylib|dll)$'
# Expected: at least one libmosaic-*.so (linux-x86_64, linux-aarch64, macos-arm64, mingw-x64).
# Empty result = the JNI step didn't run; back to Step 2b.

# 4. Validate consumer build picks up the fresh artefacts.
cd /home/user/compose-ai-tools
./gradlew :tui-cli:installDist
```

## Step 2b — patched publish (what this session uses)

If hosts are still 403, the local patches authored in commit `b0e18d7`'s session are
needed. They live in `/home/user/mosaic` as **uncommitted local changes** (`git status` in
that checkout shows them). What they do:

| File | Patch |
| --- | --- |
| `addAllTargets.gradle` | Removes `linuxArm64 / linuxX64 / macosArm64 / mingwX64` targets — only `jvm()` survives. Stops cklib's eager LLVM fetch. |
| `mosaic-tty/build.gradle` | Comments out `apply plugin: 'co.touchlab.cklib'`, the `cklib { }` block, the `${target.name}JniZigBuild` task, `apply plugin: 'de.infolektuell.jextract'`, the `jextract.libraries.register` block, the `jdk22Compilation` create, and the `from(jdk22Compilation.output)` in mainJar. |

With them in place, Step 2a's publish command works in this session too — but the
resulting `mosaic-tty-jvm` jar has **no platform `.so` resources**. The build verifies
end-to-end (`:tui-cli:installDist` passes); the runtime TUI will throw
`UnsatisfiedLinkError` the moment it engages raw-mode terminal I/O.

If you find yourself reaching for Step 2b, also file an upstream issue on the env
config — the patches are a stopgap, not a long-term plan.

## Step 3 — adopt the fork's composables in `:tui-cli`

Once the publish has real JNI libs, the actual user-visible work begins.

### 3a. Swap `PreviewViewPane.kt` to truecolor via `RawText`

Today's pane uses `AnsiImage.renderAscii` — grayscale luminance ramp, ugly but layout-
safe. The fork's `RawText` composable accepts a width hint, so we can render the
truecolor `AnsiImage.render` output (which emits `\e[38;2;R;G;B;48;2;R;G;Bm▀` per cell)
without breaking Mosaic's width tracker.

Find the call site at
[`PreviewViewPane.kt:53`](src/main/kotlin/ee/schimke/composeai/tui/ui/PreviewViewPane.kt):

```kotlin
val art = remember(png.path, png.lastModified(), width, rows) {
  AnsiImage.renderAscii(png, maxCols = width, maxRows = rows - 2)
}
// …
for (line in art) Text(line.padEnd(width).take(width))
```

Replace with:

```kotlin
val art = remember(png.path, png.lastModified(), width, rows) {
  AnsiImage.render(png, maxCols = width, maxRows = rows - 2)  // truecolor
}
// …
for (line in art) RawText(line, displayWidth = width)  // RawText is the fork's escape-safe Text
```

Verify by re-running the e2e harness and eyeballing
`tui-cli/build/e2e-screenshots/wide/01-initial.png` — the centre pane should show colour
instead of grayscale ASCII.

### 3b. Try the fork's `Image` composable as a second option

If `RawText` works, the fork's `Image` composable that internally picks Kitty Graphics
Protocol when available (falling back to half-blocks when not) is the higher-fidelity
upgrade. Replace the entire `for line in art` block with:

```kotlin
Image(painter = ImagePainter.File(png.toPath()), modifier = Modifier.size(width, rows - 2))
```

Under kitty in the e2e harness, the capture should show the *actual PNG* drawn into the
cell grid instead of an ASCII or half-block approximation. That's the visual proof that
the Kitty Graphics dispatch worked.

### 3c. Tidy

- Delete `AnsiImage.renderAscii` if step 3b succeeds (the truecolor `render` is still
  useful as a stdout dumper for non-Mosaic consumers).
- Strike items 2 + 3 from `LIMITATIONS.md` — they're closed by adoption.
- Update `MOSAIC-IMAGE-RFC.md`'s acceptance test paragraph to say "this consumer's tree
  no longer has `AnsiImage.renderAscii`."

## Step 4 — file the four sibling RFCs upstream

The mouse / resize / text-field / redraw RFCs at the repo root are written to be filed
back against `JakeWharton/mosaic`:

| RFC | File against |
| --- | --- |
| `MOSAIC-MOUSE-RFC.md` | new issue |
| `MOSAIC-RESIZE-RFC.md` | new issue or PR adding KDoc |
| `MOSAIC-TEXTFIELD-RFC.md` | new issue |
| `MOSAIC-REDRAW-RFC.md` | new issue + small renderer PR |

Pick whichever has clearest upstream interest. Mouse is the highest-value because the
decoder already exists (`terminal.MouseEvent` is in 0.18); composition-side surface is the
only gap. Filing that one with a "I'm happy to PR steps 1–3" note is the most actionable.

## Step 5 — push downstream once the loop is green

The `compose-preview-tui` install dir is `tui-cli/build/install/compose-preview-tui/`.
After step 3 lands and screenshots show real images, hand the user the new
`wide/01-initial.png` so they can compare against the grayscale ASCII version this branch
shipped with.

## Open questions / things I left undone

- **`Capabilities.kittyGraphics`** is populated by Mosaic's startup probe but I never
  verified empirically that it's `true` under kitty-under-Xvfb. If the `Image`
  composable's Kitty path doesn't trip on the harness, that's the explanation — fall
  back to half-blocks via the composable's own dispatch, no consumer change needed.
- **Bracketed paste in `App.kt`'s filter editor** — `terminal.BracketedPasteEvent`
  exists, our code doesn't consume it. Either gate on the upstream `BasicTextField`
  landing (`MOSAIC-TEXTFIELD-RFC.md`), or hand-roll a wrapper that buffers between
  `BracketedPasteEvent(start=true)` and `BracketedPasteEvent(start=false)` if you want
  paste support sooner.
- **The doubled status bar** in `wide/06-data-pane-focused.png` — `MOSAIC-REDRAW-RFC.md`
  proposes a renderer-side fix. Until that lands, the consumer workaround is to make
  every variable-width `Text` in `StatusBar.kt` `.padEnd(width)` against the column
  budget. Not done because the right fix is in the renderer.
- **`compose-preview-tui` not on `:cli`'s launcher** — currently a standalone binary.
  Worth a CLI subcommand (`compose-preview tui`) that just `exec`s the tui-cli launcher
  for discoverability.

## Files to read first

In order:

1. [`MOSAIC-FORK.md`](MOSAIC-FORK.md) — current state of the wiring + workflow.
2. [`LIMITATIONS.md`](LIMITATIONS.md) — what each RFC closes.
3. [`MOSAIC-IMAGE-RFC.md`](MOSAIC-IMAGE-RFC.md) — what the fork's `RawText` / `Image`
   actually do (step 3 above is the consumer adoption).
4. [`src/main/kotlin/ee/schimke/composeai/tui/ui/PreviewViewPane.kt`](src/main/kotlin/ee/schimke/composeai/tui/ui/PreviewViewPane.kt)
   — the centre pane that's the first adoption target.
5. [`src/test/kotlin/ee/schimke/composeai/tui/e2e/KittyE2ETest.kt`](src/test/kotlin/ee/schimke/composeai/tui/e2e/KittyE2ETest.kt)
   — the verification harness.

Skip the other RFC files until you actually start on those features.
