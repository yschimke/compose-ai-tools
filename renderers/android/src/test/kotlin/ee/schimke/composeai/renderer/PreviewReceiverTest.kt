package ee.schimke.composeai.renderer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [resolvePreviewReceiver] — the receiver-resolution
 * logic that makes instance-method previews (Google's
 * `com.android.compose.screenshot` style) render without NPE-ing inside
 * `ComposableMethod.invoke`. Kept out of the Robolectric suite because
 * nothing here touches Android graphics; we just want the three shapes
 * (top-level / object / class) locked in regardless of sandbox state.
 */
class PreviewReceiverTest {

    // Stand-ins for the three source shapes @Preview can appear in.
    object ObjectWithPreview {
        @Suppress("unused")
        fun Preview() {
        }
    }

    class ClassWithPreview {
        @Suppress("unused")
        fun Preview() {
        }
    }

    private class NoDefaultCtor(@Suppress("unused") val x: Int) {
        @Suppress("unused")
        fun Preview() {
        }
    }

    @Test
    fun `object singleton is reused across calls`() {
        val first = resolvePreviewReceiver(ObjectWithPreview::class.java)
        val second = resolvePreviewReceiver(ObjectWithPreview::class.java)
        assertSame(
            "Kotlin object must resolve to its shared INSTANCE field",
            ObjectWithPreview,
            first,
        )
        assertSame(ObjectWithPreview, second)
    }

    @Test
    fun `class with nullary ctor produces a fresh instance per call`() {
        val first = resolvePreviewReceiver(ClassWithPreview::class.java)
        val second = resolvePreviewReceiver(ClassWithPreview::class.java)
        assertNotNull("Regular class must be instantiated via nullary ctor", first)
        assertTrue(first is ClassWithPreview)
        // Each call constructs its own — two invocations of the renderer
        // must not share mutable state across previews.
        assertNotSame(first, second)
    }

    @Test
    fun `top-level FooKt class resolves to null receiver`() {
        // File-level `@Preview fun …` compiles to a static method on the
        // synthetic Kt class. `resolvePreviewReceiver` shouldn't try to
        // construct one — reflection would fabricate an orphaned instance
        // that `Method.invoke` would then ignore anyway, but leaking it is
        // untidy and risks resurrecting a bug where the wrong receiver got
        // picked up by `getClass()`-driven discovery.
        //
        // The Kt class has no `INSTANCE` field and no accessible nullary
        // ctor (synthetic classes are typically final with a private ctor),
        // so we expect `null`. If the Kotlin compiler ever changes to emit
        // a public no-arg ctor on these synthetic classes this test will
        // flip and we'll need a different strategy (Modifier.isStatic
        // check on the resolved Method).
        val ktClass = Class.forName("ee.schimke.composeai.renderer.PreviewReceiverTestFixturesKt")
        val receiver = resolvePreviewReceiver(ktClass)
        assertNull(
            "Top-level preview container class should resolve to null receiver; got $receiver",
            receiver,
        )
    }

    @Test
    fun `class without nullary ctor falls back to null rather than throwing`() {
        // We don't want a preview on a class with only parameterised ctors
        // to blow up discovery — the renderer will still hit the NPE on
        // invoke, but the receiver-resolution step itself should be a
        // graceful null so the failure surfaces through the normal render
        // error path, not as an exception out of `resolvePreviewReceiver`.
        val receiver = resolvePreviewReceiver(NoDefaultCtor::class.java)
        assertNull(receiver)
    }
}
