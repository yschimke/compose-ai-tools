# Desktop generated-preview reproduction

Related: tools #5729 and compose-preview-daemon #160.

## Versions and trace

Reproduced from compose-ai-tools `4a6e84123228298756f2c421acca70ae4a0e0d54`
with the unchanged published daemon BOM **3.18.6**, Kotlin **2.4.21**,
Compose Multiplatform **1.11.1**, Material 3 **1.11.0-alpha07**, Temurin **17.0.20.1+1**.
Daemon regression coverage starts at `2da42ee2` (3.18.8-SNAPSHOT), running both
that production Compose line and **1.12.0-rc01 / Material 3 1.12.0-alpha03**.
`git diff v3.18.6..2da42ee2 -- daemon/desktop renderers/desktop` is empty.

The current server generated entry declares `@Preview(widthDp, heightDp)`.
`PlaygroundPreviewDiscoverer` reads the compiled facade/method IDs;
`PlaygroundCompileService` retains those IDs and the compiled output in the token;
`PlaygroundPreviews.previewManifestJson` reconstructs the manifest. The first-frame
service and redeemed live sessions both use that manifest. Daemon routing resolves
its `params` to pixel dimensions; desktop `RenderEngine` reads `captures[].settle`
through `PreviewIndex.staticSettleFor` before rendering the PNG.

## Dimension reproduction

`PlaygroundDesktopRenderTest` compiles a small colored Compose fixture, excludes the
annotation definitions from discovery, and passes the synthesized manifest through
the actual published daemon router, host and PNG encoder. It covers directory and
jar output with the same Kotlin file-facade-shaped IDs.

| Request | Before: jar output | After: directory and jar |
| --- | --- | --- |
| Phone, 412×915 dp at 2× | 800×1600 px | 824×1830 px |
| Tablet, 1280×800 dp at 2× | 800×1600 px | 2560×1600 px |

Before images have identical SHA-256
`4a825c743b70be1388ecc6ca073d55293e94403673731e518ead23a96052ac40`.
The failing baseline is from the unmodified `PlaygroundPreviews.kt` on tools main.
Directory output already honors both sizes on current main. The production server
currently compiles to directories, so this jar reproduction does **not** establish
which condition caused the historical deployed report. Missing/unreadable output
and unmatched IDs now produce distinct diagnostics with their path and IDs.

## Dialog reproduction and policy

A real Material `AlertDialog` uses a known green container so opacity is measurable.
On both tested Compose lines, the unannotated still and explicit 80 ms capture have
zero opaque green pixels; auto settle and an exact 1000 ms capture each have 62,828.
The committed dialog images come from the forward line, matching the reported
Material version. `unsettled.png` reproduces the missing settle request;
`auto.png` is the bounded settled still; `early.png` and `exact.png` show explicit
phase capture before and after the entrance animation.

The current daemon settles correctly when given the request. The local metadata
bug discarded `@SettledPreview` entirely, and generated entries have no such
annotation. Ordinary CMP playground stills now request auto settle bounded at
1000 ms. Explicit settle timestamps retain precedence and discovery's clamps.
Motion, interaction, scrolling and Roborazzi timeline annotations do not acquire a
default settle. Android defaults, daemon recording and interactive paths are unchanged.
This does not add playground motion fan-out support.

## Verification and dependency ordering

Run `scripts/agent-gradle.sh :render-host:check`. The desktop dimension test uses the
published 3.18.6 pin; no local daemon artifact or cross-repository `includeBuild`.
In the daemon repository, run
`scripts/agent-gradle.sh :daemon:desktop:test --tests '*RenderEngineDialogSettleTest*'`
for production and the automatically finalized forward-runtime regression.

No daemon production change or new daemon release is needed for this tools fix.
The daemon PR adds regression coverage only. Land/release tools, wait for its
release to be non-draft and the `render-host` coordinate to resolve from Maven
Central, then bump the tools version in compose-preview-server in a separate PR.
Release the server and update any deployed host through its normal process.
The existing daemon 3.18.7 Renovate PR is independent. Never pin a downstream
consumer to either repository's local SNAPSHOT or an unpublished release number.

## Remaining upstream reports and workarounds

No existing upstream workaround is removed. RectList/EDT handling, native library
pairing, renderer/consumer Compose alignment, and server aspect-ratio rejection
remain in place. The old light-theme report is separate: this fixture explicitly
chooses its container color and does not verify the server's theme generation.
The historical deployed default-size cause still needs its runtime diagnostics;
the confirmed jar fallback and lost settle metadata are the local defects fixed here.

## Validation limits in this session

The seven focused metadata/render regressions pass. The full render-host module
run reached 508 passing tests and two unrelated namespace-probe failures:
`bwrap: Can't mount proc on /newroot/proc: Operation not permitted` and
`unshare: mount /proc failed: Operation not permitted`. No test was skipped or
changed to hide those failures. Repository-wide `check` was attempted in both
repositories and stops at the missing Android SDK; it does not establish Android
validation. The daemon desktop check and renderer suite pass 374 tests, with four
pre-existing skips.
