package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.annotation.Implements

/**
 * Pins the `@Implements` annotation form on [ShadowAmbientLifecycleObserver] (issue #1244).
 *
 * The shadow must declare `@Implements(className = "androidx.wear.ambient.AmbientLifecycleObserver")`
 * rather than `@Implements(value = AmbientLifecycleObserver::class)`. Robolectric's
 * `ShadowMap.obtainShadowInfo` reads `className()` first and only falls back to `value().getName()`
 * when `className` is empty; the class-literal form stores a deferred `Class<?>` reference in the
 * annotation proxy, and calling `.value()` forces the JVM's `AnnotationInvocationHandler` to
 * resolve `androidx.wear.ambient.AmbientLifecycleObserver` against the shadow's defining loader.
 * On daemon classpaths that ship the shadow next to a mismatched / stale wear AAR (the bug report
 * in issue #1244 surfaced this against the wear sample) that resolution throws
 * `TypeNotPresentException` deep inside Robolectric's `createClassLoaderConfig` loop — before
 * `SandboxHoldingRunner.getExtraShadows`'s gate has a chance to intercept the next sandbox.
 *
 * Touching `value()` here makes the regression noisy: if anyone reverts the annotation to the
 * class-literal form, the deferred proxy resolution kicks in inside this test instead of mid-render
 * in a daemon's stderr.
 */
class ShadowAmbientLifecycleObserverAnnotationTest {

  @Test
  fun `shadow declares className not value`() {
    val annotation =
      ShadowAmbientLifecycleObserver::class.java.getAnnotation(Implements::class.java)
    assertNotNull("ShadowAmbientLifecycleObserver must carry @Implements", annotation)
    assertEquals(
      "ShadowAmbientLifecycleObserver must target AmbientLifecycleObserver by className",
      "androidx.wear.ambient.AmbientLifecycleObserver",
      annotation!!.className,
    )
    // `value()` must default to `void.class` so the annotation proxy never has to resolve a
    // deferred Class<?> reference. If the class-literal form sneaks back in, this line throws
    // `TypeNotPresentException` on any loader without the wear AAR.
    assertEquals(
      "@Implements.value() must default to void.class so no deferred class resolution runs",
      Void.TYPE,
      annotation.value.java,
    )
  }
}
