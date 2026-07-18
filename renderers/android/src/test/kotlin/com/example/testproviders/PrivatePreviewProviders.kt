@file:Suppress("unused")

package com.example.testproviders

/**
 * Test fixtures for [ee.schimke.composeai.renderer.PreviewManifestLoaderProviderTest].
 *
 * These live in a package OTHER than the renderer's so that `private`/package-private access is
 * exercised cross-package — the exact condition under which `Method.invoke` throws
 * `IllegalAccessException` without `setAccessible(true)` (issue #2493). They're referenced only by
 * fully-qualified name via `Class.forName`, never by symbol, so `private` top-level visibility is
 * fine.
 */

/**
 * A `private` top-level provider. Kotlin compiles this to a package-private JVM class, so both its
 * no-arg constructor and its `getValues()` accessor are unreachable from the renderer's package
 * unless the loader opens them up with `isAccessible = true`.
 */
private class PrivateStringProvider {
  val values: Sequence<String> = sequenceOf("alpha", "beta", "gamma")
}

/** A public provider whose `getValues()` throws while the sequence is being driven. */
class ThrowingProvider {
  val values: Sequence<String>
    get() = throw IllegalStateException("provider boom")
}
