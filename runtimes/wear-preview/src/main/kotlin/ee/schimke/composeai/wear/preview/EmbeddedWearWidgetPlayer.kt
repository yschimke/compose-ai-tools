package ee.schimke.composeai.wear.preview

import java.lang.reflect.Modifier

/**
 * The lane [CapturingWearWidgetPreview] draws through in this JVM, resolved once.
 *
 * Read from the system property rather than passed in, because the choice is a property of the
 * *render*, not of any one preview: the Gradle plugin forwards `-PcomposePreview.rcPlayer=…` onto
 * the render / daemon JVM as [WearWidgetPreviewPlayer.PROPERTY], and every widget preview in that
 * JVM then draws through the same player. Resolved lazily (not at class-init) so a host that sets
 * the property programmatically before the first render is still honoured, and once so a bad value
 * is reported once rather than once per composition.
 */
internal val wearWidgetPreviewPlayer: WearWidgetPreviewPlayer by lazy {
  WearWidgetPreviewPlayer.resolve(System.getProperty(WearWidgetPreviewPlayer.PROPERTY))
}

/**
 * Whether an embedded player this module can actually call is on the runtime classpath. Resolved
 * once per JVM.
 *
 * `:wear-preview-runtime` takes the embedded player as `compileOnly` — a consumer that doesn't ship
 * it still loads this helper — so the CMP lane has to ask before it calls. A consumer without the
 * player (or with one whose entry point has drifted) draws through upstream `WearWidgetPreview`
 * instead of dying with `NoClassDefFoundError` / `NoSuchMethodError`. Same gate, and the same
 * reasoning, as `isEmbeddedPlayerAvailable` in `:data-remotecompose-connector`.
 */
internal val embeddedWearWidgetPlayerAvailable: Boolean by lazy {
  embeddedPlayerEntryPointPresent(WearWidgetPreviewPlayer::class.java.classLoader)
}

internal const val EMBEDDED_PLAYER_FACADE =
  "ee.schimke.composeai.rcembedded.player.ExperimentalRemoteDocumentPlayerKt"

internal const val EMBEDDED_PLAYER_ENTRY_POINT = "ExperimentalRemoteDocumentPlayer"

/**
 * The parameter types of the [EMBEDDED_PLAYER_ENTRY_POINT] overload this module's call site
 * compiles down to, in declaration order.
 *
 * The tail — `Composer, int, int` — is Compose's own ABI (composer, changed mask, defaults mask); a
 * Kotlin call site that omits defaults still invokes this full method rather than a `$default`
 * bridge, which is why a signature change upstream is a *link* error at render time and not
 * something the compiler can see here.
 *
 * Pinned as strings rather than `Class` literals on purpose: the point is to answer "is the method
 * this code was compiled against on the runtime classpath" without loading a single one of those
 * types, so a classpath missing them answers `false` instead of throwing. Kept honest by
 * `EmbeddedWearWidgetPlayerTest`, which resolves the real facade off the test classpath and asserts
 * this list still describes it.
 */
internal val EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS: List<String> =
  listOf(
    "androidx.compose.remote.player.core.RemoteDocument",
    "androidx.compose.ui.Modifier",
    "int",
    "androidx.collection.ObjectIntMap",
    "ee.schimke.composeai.rcembedded.player.RcImageLoader",
    "kotlin.jvm.functions.Function1",
    "kotlin.jvm.functions.Function2",
    "kotlin.jvm.functions.Function3",
    "androidx.compose.runtime.Composer",
    "int",
    "int",
  )

/**
 * Whether [EMBEDDED_PLAYER_FACADE] on [classLoader] declares the exact entry point this module was
 * compiled against.
 *
 * It resolves the *method* rather than the class because the two are not the same question, and the
 * gap between them once cost a production render lane: while the vendored player still lived in
 * upstream's package, an androidx-main build began publishing an embedded player of its own under
 * the same names, `Class.forName` happily returned it, and the call failed to link. The player has
 * since moved to a package nobody else publishes into; this stays as the seatbelt for a re-vendor
 * whose entry point drifts.
 */
internal fun embeddedPlayerEntryPointPresent(classLoader: ClassLoader?): Boolean = runCatching {
  declaresEntryPoint(
    Class.forName(EMBEDDED_PLAYER_FACADE, false, classLoader),
    EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS,
  )
}
  .getOrDefault(false)

/**
 * Whether [facade] declares [EMBEDDED_PLAYER_ENTRY_POINT] in the exact shape the call site links
 * against: `public static void` taking exactly [parameters].
 *
 * The modifiers and return type are checked alongside the signature because they are separately
 * load-bearing — the compiled call is an `invokestatic …(…)V`, so a same-named method that is
 * non-static, non-public or returns something else fails to link just as hard.
 */
internal fun declaresEntryPoint(facade: Class<*>, parameters: List<String>): Boolean =
  facade.declaredMethods.any { method ->
    method.name == EMBEDDED_PLAYER_ENTRY_POINT &&
      method.parameterTypes.map { it.name } == parameters &&
      method.returnType == Void.TYPE &&
      Modifier.isStatic(method.modifiers) &&
      Modifier.isPublic(method.modifiers)
  }
