@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.ui.tooling.preview

import kotlin.reflect.KClass

/**
 * Test-only stand-in for the upstream `androidx.compose.ui.tooling.preview.PreviewWrapper`
 * annotation, declared under the same FQN so [ee.schimke.composeai.daemon.RenderEngine]'s
 * `resolveWrapperOrNull` (which matches by FQN string) drives the production code path.
 *
 * The real annotation ships in `compose-ui-tooling-preview` 1.11.0-beta+. The daemon module's
 * compose-bom-compat floor pins 1.9.5, so the upstream class isn't on the test classpath and there
 * is no FQN collision. Matches the upstream `wrapper` parameter name (also used by ClassGraph
 * discovery in `gradle-plugin/preview-discovery`).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreviewWrapper(val wrapper: KClass<*>)
