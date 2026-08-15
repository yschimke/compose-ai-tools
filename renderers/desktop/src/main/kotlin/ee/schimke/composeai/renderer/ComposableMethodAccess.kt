package ee.schimke.composeai.renderer

import androidx.compose.runtime.reflect.ComposableMethod

/**
 * Opens a resolved preview entrypoint for reflective invocation.
 *
 * Kotlin `private fun` previews are idiomatic — a preview is usually only ever called by tooling,
 * so there is nothing to export — and they compile to JVM-private static methods on the file's
 * synthetic `FooKt` class. [androidx.compose.runtime.reflect.getDeclaredComposableMethod] still
 * *resolves* them (it scans `declaredMethods`, which includes private ones), but the reflective
 * `ComposableMethod.invoke` that follows throws `IllegalAccessException: ComposableMethod cannot
 * access … with modifiers "private static final"` unless the underlying JVM method is opened first.
 *
 * The desktop **daemon** ([ee.schimke.composeai.daemon.RenderEngine]) and the **Android** renderer
 * ([ee.schimke.composeai.renderer.PreviewRenderStrategy]) already did this, but the **standalone**
 * desktop renderer — the one `composePreviewRenderAll` / `compose-preview bundle render` runs, and
 * the one `compose-preview serve --module` bootstraps with before it starts its (capable) daemon —
 * did not. So the same preview rendered through MCP and failed through Gradle, and Serve could exit
 * during bootstrap on a preview its own daemon would have drawn (issue #3873). Every desktop
 * rendering path — default, animated, focus, scroll — routes its resolution through here so the
 * three renderers agree on which previews are renderable.
 *
 * Guarded with `runCatching`: a SecurityManager or strong module encapsulation can refuse
 * `setAccessible`, in which case we still attempt the invoke (which succeeds for public/internal
 * previews) rather than failing resolution outright.
 */
internal fun ComposableMethod.openForInvoke(): ComposableMethod = also {
  runCatching { it.asMethod().isAccessible = true }
}
