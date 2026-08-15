# Private `@Preview` renders — issue #3873

Visual evidence for the standalone Desktop renderer's `setAccessible` fix. The captures come from
`./gradlew :samples:cmp:composePreviewRenderAll` against
[`samples/cmp/.../PrivatePreviews.kt`](../../../samples/cmp/src/main/kotlin/com/example/samplecmp/PrivatePreviews.kt),
whose previews and `PreviewParameterProvider` are all declared `private`.

## Before

**No image, by construction** — that is the bug. Every private preview failed at reflective
invocation, so the renderer wrote a `.error.json` where the PNG should be and
`composePreviewRenderAll` failed the build:

```
$ ls samples/cmp/build/compose-previews/renders/Private*
PrivateBadgePreview_Private_badge-1c0a3d6e.png.error.json
PrivateTonePreview_Private_tone-0bb684c0_Indigo.png.error.json
PrivateTonePreview_Private_tone-0bb684c0_Moss.png.error.json
```

```json
{
  "schema": "compose-preview-error/v1",
  "exception": "java.lang.IllegalAccessException",
  "message": "class androidx.compose.runtime.reflect.ComposableMethod cannot access a member of class com.example.samplecmp.PrivatePreviewsKt with modifiers \"private static final\""
}
```

The failing frame is `ComposableMethod.invoke → DesktopRendererMainKt.InvokeComposable`: resolution
succeeded (`getDeclaredComposableMethod` scans `declaredMethods`, private members included) and only
the invoke was refused. The same previews rendered fine through the MCP daemon throughout, which is
what made the failure confusing — and `compose-preview serve --module` bootstraps through this
renderer, so one private preview could take the server down before its capable daemon ever started.

## After

All three render, and the build is green.

### `PrivateBadgePreview` — a private parameterless preview

![Private badge preview rendered](after-private-badge.png)

### `PrivateTonePreview` — both rows of a private `@PreviewParameter` fan-out

Serve derives a parameterized preview's row entries from these fan-out PNGs, so a row that never
rendered is a row missing from the catalog. Both rows are present, named by the label the provider
yields:

| `_Indigo` | `_Moss` |
| --- | --- |
| ![Indigo row rendered](after-private-tone-indigo.png) | ![Moss row rendered](after-private-tone-moss.png) |

## Standing coverage

These previews live in the sample rather than in a scratch fixture on purpose: no sample exercised a
private `@Preview` before, which is how the gap shipped. From now on
`:samples:cmp:composePreviewRenderAll` draws them on every run,
[`PrivatePreviewRenderTest`](../../../samples/cmp/src/test/kotlin/com/example/samplecmp/PrivatePreviewRenderTest.kt)
asserts the PNGs landed with no `.error.json` beside them, and the CI visual-diff bot picks the
images up like any other sample preview.
