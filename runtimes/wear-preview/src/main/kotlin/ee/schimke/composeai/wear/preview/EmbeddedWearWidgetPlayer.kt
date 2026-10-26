package ee.schimke.composeai.wear.preview

import java.lang.reflect.Modifier

/**
 * The lane [CapturingWearWidgetPreview] draws through in this JVM. A property of the render (the
 * plugin forwards `-PcomposePreview.rcPlayer=…` as [WearWidgetPreviewPlayer.PROPERTY]), read lazily
 * so a programmatic setting is honoured, and once so a bad value is reported once.
 */
internal val wearWidgetPreviewPlayer: WearWidgetPreviewPlayer by lazy {
  WearWidgetPreviewPlayer.resolve(System.getProperty(WearWidgetPreviewPlayer.PROPERTY))
}

/**
 * Whether a callable embedded player is on the runtime classpath (resolved once). It's
 * `compileOnly`, so without it (or with a drifted entry point) the lane falls back to upstream
 * `WearWidgetPreview` rather than failing to link. Same gate as `isEmbeddedPlayerAvailable` in
 * `:data-remotecompose-connector`.
 */
internal val embeddedWearWidgetPlayerAvailable: Boolean by lazy {
  embeddedPlayerEntryPointPresent(WearWidgetPreviewPlayer::class.java.classLoader)
}

internal const val EMBEDDED_PLAYER_FACADE =
  "ee.schimke.composeai.rcembedded.player.ExperimentalRemoteDocumentPlayerKt"

internal const val EMBEDDED_PLAYER_ENTRY_POINT = "ExperimentalRemoteDocumentPlayer"

/**
 * Parameter types of the [EMBEDDED_PLAYER_ENTRY_POINT] overload this module's call site links
 * against, in order; the `Composer, int, int` tail is Compose's ABI. Strings rather than `Class`
 * literals so a missing type answers `false` instead of throwing. `EmbeddedWearWidgetPlayerTest`
 * checks this against the real facade.
 */
internal val EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS: List<String> =
  listOf(
    "androidx.compose.remote.player.core.RemoteDocument",
    "androidx.compose.ui.Modifier",
    "ee.schimke.composeai.rcembedded.player.RcImageLoader",
    "kotlin.jvm.functions.Function1",
    "kotlin.jvm.functions.Function2",
    "kotlin.jvm.functions.Function3",
    "int",
    "ee.schimke.composeai.rcembedded.player.CustomPluginRegistry",
    "androidx.compose.runtime.Composer",
    "int",
    "int",
  )

/**
 * Whether [EMBEDDED_PLAYER_FACADE] on [classLoader] declares the exact entry point this module was
 * compiled against. Checks the method, not just the class: a same-named class from another
 * publisher once resolved but failed to link.
 */
internal fun embeddedPlayerEntryPointPresent(classLoader: ClassLoader?): Boolean = runCatching {
  declaresEntryPoint(
    Class.forName(EMBEDDED_PLAYER_FACADE, false, classLoader),
    EMBEDDED_PLAYER_ENTRY_POINT_PARAMETERS,
  )
}
  .getOrDefault(false)

/**
 * Whether [facade] declares [name] as `public static void` taking exactly [parameters] — the
 * compiled `invokestatic …V` call fails to link otherwise.
 */
internal fun declaresEntryPoint(
  facade: Class<*>,
  parameters: List<String>,
  name: String = EMBEDDED_PLAYER_ENTRY_POINT,
): Boolean =
  facade.declaredMethods.any { method ->
    method.name == name &&
      method.parameterTypes.map { it.name } == parameters &&
      method.returnType == Void.TYPE &&
      Modifier.isStatic(method.modifiers) &&
      Modifier.isPublic(method.modifiers)
  }

/**
 * The state-taking `RcPlayer` overload `SeededCmpWearWidgetPlayer` calls to draw a document with
 * seeded named values (#401). Gated separately from [EMBEDDED_PLAYER_ENTRY_POINT]: a player that
 * lacks it still draws unseeded widgets, and a seed then goes unreported rather than failing to
 * link.
 */
internal const val SEEDED_PLAYER_FACADE = "ee.schimke.composeai.rcembedded.player.RcPlayerKt"

internal const val SEEDED_PLAYER_ENTRY_POINT = "RcPlayer"

/** Parameter types of that overload as this module's call site links them; see the test. */
internal val SEEDED_PLAYER_ENTRY_POINT_PARAMETERS: List<String> =
  listOf(
    "ee.schimke.composeai.rcembedded.player.RcPlayerState",
    "androidx.compose.ui.Modifier",
    "ee.schimke.composeai.rcembedded.player.RcImageLoader",
    "kotlin.jvm.functions.Function1",
    "kotlin.jvm.functions.Function2",
    "kotlin.jvm.functions.Function3",
    "ee.schimke.composeai.rcembedded.player.CustomPluginRegistry",
    "androidx.collection.IntObjectMap",
    "androidx.collection.IntObjectMap",
    "androidx.compose.remote.player.core.platform.TypefaceResolver",
    "int",
    "androidx.compose.runtime.Composer",
    "int",
    "int",
    "int",
  )

/** Whether [classLoader] carries the [SEEDED_PLAYER_ENTRY_POINT] overload this module links. */
internal fun seededPlayerEntryPointPresent(classLoader: ClassLoader?): Boolean = runCatching {
  declaresEntryPoint(
    Class.forName(SEEDED_PLAYER_FACADE, false, classLoader),
    SEEDED_PLAYER_ENTRY_POINT_PARAMETERS,
    SEEDED_PLAYER_ENTRY_POINT,
  )
}
  .getOrDefault(false)

/** [seededPlayerEntryPointPresent] for this runtime's classloader, resolved once. */
internal val seededEmbeddedPlayerAvailable: Boolean by lazy {
  seededPlayerEntryPointPresent(WearWidgetPreviewPlayer::class.java.classLoader)
}
