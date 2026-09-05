package ee.schimke.composeai.renderer

import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod

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

/**
 * Resolves a preview that takes no seeded arguments, whether or not its parameters declare
 * defaults.
 *
 * `getDeclaredComposableMethod(name)` searches for the exact signature `(Composer, int)` — a
 * preview with **no value parameters**. A preview whose parameters all declare defaults is an
 * equally supported shape: it is what a production composable annotated `@Preview` in place almost
 * always looks like (`modifier: Modifier = Modifier`), and it is the whole of the **parameter
 * knob** format. It compiles to `(realParams…, Composer, int changed, int default)`, which that
 * lookup cannot see, so resolution threw `NoSuchMethodException` before composition started — an
 * `.error.json` where the capture should be.
 *
 * The ordinary render path and both daemons already fell back to
 * [PreviewParameterSupport.findDefaultedComposableMethod]; the **focus**, **motion** and **scroll**
 * lanes did not, so a parameter-knob preview baked its resting capture and then failed every
 * interaction-state, motion and scroll cell built on top of it. In a catalog those cells are the
 * kit-comparison addresses, so the loss was 222 renders in `m3-catalog` with the manifest still
 * reporting success. This is the one place all four lanes now agree.
 */
internal fun resolveDefaultedOrPlain(clazz: Class<*>, functionName: String): ComposableMethod =
  runCatching {
    clazz.getDeclaredComposableMethod(functionName)
  }
  .getOrElse { failure ->
    PreviewParameterSupport.findDefaultedComposableMethod(clazz, functionName) ?: throw failure
  }
