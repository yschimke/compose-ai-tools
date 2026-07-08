@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.ui.tooling.preview

import kotlin.reflect.KClass

/**
 * Test-only stand-in for the upstream `androidx.compose.ui.tooling.preview.PreviewWrapper`
 * annotation, declared under the same FQN so the gradle plugin's class-file scan
 * (`extractWrapperFqn`) sees the same annotation shape it does in production.
 *
 * The real annotation ships in `compose-ui-tooling-preview` 1.11.0-beta+. The daemon module's
 * compose-bom-compat floor pins 1.9.5, so the upstream class isn't on the test classpath and there
 * is no FQN collision. Matches the upstream `wrapper` parameter name (also used by ClassGraph
 * discovery in `gradle-plugin/preview-discovery`).
 *
 * **Retention.** Mirrors upstream: `AnnotationRetention.BINARY` — the annotation is emitted into
 * the class file but **not** retained at runtime, so `Method.annotations` will never include it.
 * Issue #1440: an earlier version of this stand-in used `AnnotationRetention.RUNTIME`, which made
 * the daemon's `Method.annotations`-based `resolveWrapperOrNull` path appear to work in tests even
 * though production previews never resolved the wrapper. The production fix routes the wrapper FQN
 * from `previews.json` (read at discovery time from the class-file annotation tables) into
 * [ee.schimke.composeai.daemon.RenderSpec.wrapperClassName]; the regression test now exercises that
 * path explicitly.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class PreviewWrapper(val wrapper: KClass<*>)
