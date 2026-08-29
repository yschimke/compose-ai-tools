package ee.schimke.composeai.data.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:data-render-core` has no Compose dependency — that is the whole reason [LinkBufferComposer]
 * goes through reflection — so the flag holder is synthesized here under the real FQN, which also
 * means these tests never mutate the runtime the build itself composes with.
 *
 * Serves [LinkBufferComposer.FLAGS_CLASS] by defining a class with a static boolean field of the
 * right name, so `Class.forName(FLAGS_CLASS, …, loader)` resolves exactly as it does on a render
 * classpath. Everything else delegates to the test classloader.
 */
private class FlagsClassLoader(private val fieldName: String = LinkBufferComposer.FLAG_FIELD) :
  ClassLoader(FlagsClassLoader::class.java.classLoader) {

  val defined: Class<*> by lazy {
    val bytes = flagsClassBytes(LinkBufferComposer.FLAGS_CLASS.replace('.', '/'), fieldName)
    defineClass(LinkBufferComposer.FLAGS_CLASS, bytes, 0, bytes.size)
  }

  override fun loadClass(name: String, resolve: Boolean): Class<*> =
    if (name == LinkBufferComposer.FLAGS_CLASS) defined else super.loadClass(name, resolve)

  fun flagValue(): Boolean =
    defined.getDeclaredField(fieldName).also { it.isAccessible = true }.getBoolean(null)
}

/**
 * A runtime that *has* the flag holder but cannot produce it — the half-resolved Compose classpath
 * shape. Distinct from `ClassLoader(null)`, which reports honest absence.
 */
private class BrokenFlagsClassLoader : ClassLoader(null) {
  override fun loadClass(name: String, resolve: Boolean): Class<*> =
    if (name == LinkBufferComposer.FLAGS_CLASS) throw LinkageError("half-resolved runtime")
    else super.loadClass(name, resolve)
}

class LinkBufferComposerTest {

  @Test
  fun unsetPropertyIsNotRequested() {
    assertFalse(LinkBufferComposer.requested(null))
    assertFalse(LinkBufferComposer.requested(""))
    assertFalse(LinkBufferComposer.requested("   "))
    assertEquals(LinkBufferComposer.Request.Off, LinkBufferComposer.request(null))
  }

  @Test
  fun booleanValuesParse() {
    assertTrue(LinkBufferComposer.requested("true"))
    assertTrue(LinkBufferComposer.requested(" TRUE "))
    assertFalse(LinkBufferComposer.requested("false"))
    assertEquals(LinkBufferComposer.Request.Required, LinkBufferComposer.request("true"))
    assertEquals(LinkBufferComposer.Request.Off, LinkBufferComposer.request("false"))
  }

  @Test
  fun autoIsRequestedButNotRequired() {
    assertTrue(LinkBufferComposer.requested(LinkBufferComposer.AUTO))
    assertEquals(LinkBufferComposer.Request.Preferred, LinkBufferComposer.request(" AUTO "))
  }

  @Test
  fun aTypoInTheFlagIsAnError() {
    val failure =
      runCatching { LinkBufferComposer.requested("ture") }.exceptionOrNull()
        as? IllegalArgumentException
    assertTrue(failure!!.message!!.contains(LinkBufferComposer.PROPERTY))
    // Names the third value too, or `auto` is undiscoverable from the one message that lists them.
    assertTrue(failure.message!!.contains(LinkBufferComposer.AUTO))
  }

  @Test
  fun notRequestedNeverTouchesTheRuntime() {
    val loader = FlagsClassLoader()

    assertEquals(
      LinkBufferComposer.Outcome.NotRequested,
      LinkBufferComposer.applyIfRequested(loader, null),
    )
    assertFalse(loader.flagValue())
  }

  @Test
  fun requestedSetsTheFlagOnThatClassLoadersRuntime() {
    val loader = FlagsClassLoader()

    assertEquals(
      LinkBufferComposer.Outcome.Enabled,
      LinkBufferComposer.applyIfRequested(loader, "true"),
    )
    assertTrue(loader.flagValue())
  }

  @Test
  fun applyingTwiceIsIdempotent() {
    val loader = FlagsClassLoader()

    LinkBufferComposer.applyIfRequested(loader, "true")
    LinkBufferComposer.applyIfRequested(loader, "true")

    assertTrue(loader.flagValue())
  }

  @Test
  fun aRuntimeWithoutTheFlagFailsLoudlyRatherThanSilentlyRenderingTheOldComposer() {
    // A Compose old enough to predate the opt-in, or new enough to have finished the migration.
    val failure = runCatching {
      LinkBufferComposer.applyIfRequested(object : ClassLoader(null) {}, "true")
    }
      .exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure!!.message!!.contains(LinkBufferComposer.FLAG_FIELD))
  }

  @Test
  fun aRuntimeWhoseFlagWasRenamedAlsoFailsLoudly() {
    val failure = runCatching {
      LinkBufferComposer.applyIfRequested(FlagsClassLoader("isSomethingElse"), "true")
    }
      .exceptionOrNull()

    assertTrue(failure is IllegalStateException)
  }

  @Test
  fun autoEnablesTheFlagWhereTheRuntimeHasIt() {
    val loader = FlagsClassLoader()

    assertEquals(
      LinkBufferComposer.Outcome.Enabled,
      LinkBufferComposer.applyIfRequested(loader, LinkBufferComposer.AUTO),
    )
    assertTrue(loader.flagValue())
  }

  /**
   * `auto` degrades for a version floor, not for anything that happens to throw. A runtime that has
   * the flag but cannot hand it over may well be able to *set* it, so absorbing that would render
   * the old composer under a notice blaming the Compose version and throw the real cause away.
   */
  @Test
  fun autoStillFailsWhenTheFlagCannotBeReadRatherThanIsAbsent() {
    for (raw in listOf(LinkBufferComposer.AUTO, "true")) {
      val failure = runCatching {
        LinkBufferComposer.applyIfRequested(BrokenFlagsClassLoader(), raw)
      }
        .exceptionOrNull()

      assertTrue("$raw should fail, not degrade", failure is IllegalStateException)
      // Names the real cause instead of asserting a version floor that was never established.
      assertTrue(failure!!.message!!.contains("LinkageError"))
      assertTrue(failure.cause is LinkageError)
    }
  }

  @Test
  fun autoRendersOnWhateverComposerARuntimeWithoutTheFlagHas() {
    // The module this exists for: `:samples:sdk-matrix` pins the `compose-bom-compat` floor on
    // purpose, so a repo-wide opt-in has to survive it rather than fail the whole render.
    assertEquals(
      LinkBufferComposer.Outcome.Unavailable,
      LinkBufferComposer.applyIfRequested(object : ClassLoader(null) {}, LinkBufferComposer.AUTO),
    )
    assertEquals(
      LinkBufferComposer.Outcome.Unavailable,
      LinkBufferComposer.applyIfRequested(
        FlagsClassLoader("isSomethingElse"),
        LinkBufferComposer.AUTO,
      ),
    )
  }

  @Test
  fun describeIsSilentUnlessTheOptInIsOn() {
    val previous = System.getProperty(LinkBufferComposer.PROPERTY)
    try {
      System.clearProperty(LinkBufferComposer.PROPERTY)
      assertNull(LinkBufferComposer.applyAndDescribe(FlagsClassLoader()))

      System.setProperty(LinkBufferComposer.PROPERTY, "true")
      val notice = LinkBufferComposer.applyAndDescribe(FlagsClassLoader())
      assertTrue(notice!!.contains(LinkBufferComposer.FLAGS_CLASS))

      // A lane that renders many previews per JVM says it once, not once per capture.
      assertNull(LinkBufferComposer.applyAndDescribe(FlagsClassLoader()))
    } finally {
      if (previous == null) System.clearProperty(LinkBufferComposer.PROPERTY)
      else System.setProperty(LinkBufferComposer.PROPERTY, previous)
    }
  }

  /**
   * The degrade is announced, not swallowed. `auto` trades the build failure for a render on the
   * old composer — if it also traded away the notice, a module could drift below the Compose floor
   * and go on reporting renders that never exercised the composer the run asked for, which is the
   * one outcome this whole class is built to prevent.
   */
  @Test
  fun describeSaysSoWhenAutoFoundNoFlagToSet() {
    val previous = System.getProperty(LinkBufferComposer.PROPERTY)
    try {
      System.setProperty(LinkBufferComposer.PROPERTY, LinkBufferComposer.AUTO)
      val flagless = object : ClassLoader(null) {}

      val notice = LinkBufferComposer.applyAndDescribe(flagless)
      assertTrue(notice!!.contains(LinkBufferComposer.FLAG_FIELD))

      // Once per JVM, like the opt-in's own notice.
      assertNull(LinkBufferComposer.applyAndDescribe(flagless))
    } finally {
      if (previous == null) System.clearProperty(LinkBufferComposer.PROPERTY)
      else System.setProperty(LinkBufferComposer.PROPERTY, previous)
    }
  }
}

/**
 * Minimal class file for `public final class <internalName> { public static boolean <fieldName>;
 * }`. Hand-assembled rather than pulled from a bytecode library: this module's dependencies are the
 * render protocol and kotlinx-serialization, and one 32-entry constant pool is cheaper than adding
 * ASM to a published artifact's test classpath.
 */
private fun flagsClassBytes(internalName: String, fieldName: String): ByteArray {
  val out = java.io.ByteArrayOutputStream()
  val dos = java.io.DataOutputStream(out)
  dos.writeInt(0xCAFEBABE.toInt())
  dos.writeShort(0) // minor
  dos.writeShort(52) // major — Java 8, readable by every JDK this repo builds on
  dos.writeShort(8) // constant pool count (entries 1..7)
  // #1 Utf8 internalName, #2 Class -> #1, #3 Utf8 "java/lang/Object", #4 Class -> #3,
  // #5 Utf8 fieldName, #6 Utf8 "Z", #7 Utf8 "Code" (unused, keeps the pool honest)
  dos.writeByte(1)
  dos.writeUTF(internalName)
  dos.writeByte(7)
  dos.writeShort(1)
  dos.writeByte(1)
  dos.writeUTF("java/lang/Object")
  dos.writeByte(7)
  dos.writeShort(3)
  dos.writeByte(1)
  dos.writeUTF(fieldName)
  dos.writeByte(1)
  dos.writeUTF("Z")
  dos.writeByte(1)
  dos.writeUTF("Code")
  dos.writeShort(0x0031) // ACC_PUBLIC | ACC_FINAL | ACC_SUPER
  dos.writeShort(2) // this class
  dos.writeShort(4) // super class
  dos.writeShort(0) // interfaces
  dos.writeShort(1) // fields
  dos.writeShort(0x0009) // ACC_PUBLIC | ACC_STATIC
  dos.writeShort(5) // name
  dos.writeShort(6) // descriptor
  dos.writeShort(0) // attributes
  dos.writeShort(0) // methods
  dos.writeShort(0) // class attributes
  dos.flush()
  return out.toByteArray()
}
