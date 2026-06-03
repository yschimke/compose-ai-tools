package ee.schimke.composeai.preview

/**
 * Marks a `@Composable` function whose body is an `androidx.xr.compose.spatial.Subspace { … }` as a
 * renderable **XR spatial preview**. Discovered by the compose-preview Gradle plugin by FQN —
 * consumers depend on `ee.schimke.composeai:preview-annotations` to use it.
 *
 * Unlike a normal `@Preview`, XR subspace previews aren't captured to a single PNG. They're
 * rendered by a separate `:renderer-xr` Robolectric task that composes the subspace offline under a
 * fake XR runtime (no headset / OpenXR / SceneCore native), recovers each panel's pose + size, and
 * writes a `scene.json` (`SpatialScene`) describing the layout for the VS Code 3D spatial-layout
 * viewer.
 *
 * **Tag every `SpatialPanel` you want in the scene** with
 * `androidx.xr.compose.subspace.semantics.testTag(...)`: the tag becomes the panel's id (and its
 * `<id>.png` texture path), and an untagged `SpatialPanel` produces no spatial-semantics node and
 * is therefore invisible to the recorder. See docs/design/XR_SPATIAL_PREVIEW.md.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class XrSubspacePreview
