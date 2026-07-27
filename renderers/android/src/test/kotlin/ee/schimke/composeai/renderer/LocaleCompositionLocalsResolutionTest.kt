package ee.schimke.composeai.renderer

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Compose UI that HAS the locale locals, standing in for a newer Compose than the renderer's
 * compat BOM. The bridge only needs the shape — a static accessor returning a providable local — so
 * this is a faithful stand-in for `androidx.compose.ui.platform.CompositionLocalsKt`, and lets the
 * positive path be tested on a classpath that doesn't ship the real thing.
 */
internal object FakeCompositionLocalsKt {
  @JvmStatic
  fun getLocalProvidableLocaleList(): ProvidableCompositionLocal<Any> = compositionLocalOf {
    "unset"
  }
}

/**
 * Stands in for `androidx.compose.ui.text.intl.LocaleList`, constructed from a language-tag string.
 * Records the tags it was built with: `ProvidedValue.value` isn't public API on the compat BOM, so
 * this is how a test sees what the bridge actually handed to Compose.
 */
internal class FakeLocaleList(val languageTags: String) {
  init {
    lastTags = languageTags
  }

  companion object {
    @JvmStatic var lastTags: String? = null
  }
}

/** A Compose UI whose `CompositionLocalsKt` predates the accessor (present, but wrong shape). */
internal object AccessorlessCompositionLocalsKt

/**
 * A stand-in Compose UI classpath: serves [names] and records every lookup, so a test can assert
 * both *what* resolved and *how often* — the point of the resolution cache. Each instance also acts
 * as its own cache key, keeping the cases independent.
 */
private class StubClasspath(private val names: Map<String, Class<*>>) {
  val lookups = AtomicInteger()

  /** A unique, empty loader: only the identity matters, since [findClass] does the resolving. */
  val key: ClassLoader = object : ClassLoader(null) {}

  val findClass: (String) -> Class<*> = { name ->
    lookups.incrementAndGet()
    names[name] ?: throw ClassNotFoundException(name)
  }
}

/**
 * The reflective half of [LocaleCompositionLocals], driven against stub classpaths.
 *
 * The stand-in classpath is injected as a lookup function rather than a class loader: `Class.forName`
 * rejects a class whose name doesn't match the one requested, so a loader that maps
 * `androidx.compose…` to a fake can never satisfy it. Deliberately NOT a Robolectric test either —
 * the sandbox resolves `Class.forName` against its own loader regardless of what is passed in.
 */
class LocaleCompositionLocalsResolutionTest {

  private fun newerCompose() =
    StubClasspath(
      mapOf(
        "androidx.compose.ui.platform.CompositionLocalsKt" to FakeCompositionLocalsKt::class.java,
        "androidx.compose.ui.text.intl.LocaleList" to FakeLocaleList::class.java,
      )
    )

  private fun provide(cp: StubClasspath, tags: String = "de-DE") =
    LocaleCompositionLocals.providedValue(tags, cp.key, cp.findClass)

  @Test
  fun `a compose with locale locals gets the requested locale provided`() {
    // The regression this test exists to prevent: before, EVERY failure — including a renamed
    // accessor — collapsed to null, so the bridge could be entirely dead with the suite still
    // green. Nothing asserted that a Compose which HAS the locals actually receives them.
    FakeLocaleList.lastTags = null

    assertNotNull(provide(newerCompose()))

    assertEquals("de-DE", FakeLocaleList.lastTags)
  }

  @Test
  fun `the reflective resolution is cached per class loader`() {
    val cp = newerCompose()

    repeat(5) { assertNotNull(provide(cp)) }

    // Two classes resolved, once — not once per composition. Every preview render asks for this.
    assertEquals(2, cp.lookups.get())
  }

  @Test
  fun `older compose versions without locale locals remain supported`() {
    val olderCompose = StubClasspath(emptyMap())

    assertNull(provide(olderCompose))
    // The miss is cached too: a per-render ClassNotFoundException is the expensive default case.
    assertNull(provide(olderCompose))
    assertEquals(1, olderCompose.lookups.get())
  }

  @Test
  fun `a compose whose accessor is missing degrades instead of failing the render`() {
    val accessorless =
      StubClasspath(
        mapOf(
          "androidx.compose.ui.platform.CompositionLocalsKt" to
            AccessorlessCompositionLocalsKt::class.java
        )
      )

    assertNull(provide(accessorless))
  }
}
