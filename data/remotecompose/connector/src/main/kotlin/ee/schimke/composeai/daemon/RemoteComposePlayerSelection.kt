package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind

/**
 * Which Remote Compose player a render draws with when nothing more specific says.
 *
 * A Remote Compose preview is **played, not composed**: the capture produces a `RemoteDocument` and
 * a player replays it. The two players are genuinely different renderers of the same document, and
 * the choice is visible in two ways a preview author cares about:
 * * **pixels**, because they rasterise independently; and
 * * **accessibility**, because [RemoteComposePlayerKind.VIEW] plays into a single Android `View`
 *   that the a11y lane can only see as one unlabelled item, while
 *   [RemoteComposePlayerKind.EMBEDDED] — the vendored `RcPlayer` — interprets the document into
 *   Compose layout/draw nodes and labels itself from the document's own root content description.
 *
 * The second is why [DEFAULT] is `EMBEDDED` (issue #5259): under the view player every Remote
 * Compose preview reports the same `SpeakableTextPresentCheck` error against `RemoteComposePlayer`,
 * identical across previews whose content differs completely, and unfixable from the design — a
 * `contentDescription` an author writes lives *inside* the played document while the check looks at
 * the `View` hosting it.
 *
 * ## What outranks what
 *
 * This is the **build-wide default**, the weakest of the three ways a player gets chosen, so a
 * preview or a request that names one keeps it:
 * 1. a per-preview pin — `@PreviewWrapper(RemoteViewPreviewWrapper::class)` /
 *    `RemoteEmbeddedPreviewWrapper`, or an explicit `player =` argument to
 *    [RemoteOverridablePreview]. Named in the source, so it wins outright;
 * 2. a per-render request — `renderNow.overrides.remoteCompose.player`, which `serve`'s
 *    `?rcPlayer=` chips ride, read through [RemoteComposeController.player]. Honoured on the replay
 *    lane, where the request is what the caller is asking to see;
 * 3. this: [PROPERTY] on the render / daemon JVM, wired by the Gradle plugin from
 *    `-PcomposePreview.rcPlayer=view`, else [DEFAULT].
 *
 * Read once per JVM ([configured]) rather than per composition, so an unusable value is reported
 * once.
 */
object RemoteComposePlayerSelection {
  /**
   * System property naming the build-wide player.
   *
   * The Gradle plugin resolves `-PcomposePreview.rcPlayer` / `-Dcomposeai.render.rcPlayer` onto
   * every render and daemon JVM (see `composeAiRcPlayer`), because the property is read *here*, in
   * the JVM that composes, not on the Gradle one.
   *
   * `:wear-preview-runtime` reads the same property under its own copy of this name — it cannot
   * depend on this module — so the two spellings are pinned by a test on each side. Change one and
   * the other's test fails.
   */
  const val PROPERTY: String = "composeai.render.rcPlayer"

  /**
   * The player a render draws with when nothing selects one: the embedded Compose player, which is
   * also what a capture bakes through, what an unqualified bundle replay uses, and what `serve`'s
   * viewer opens on.
   */
  val DEFAULT: RemoteComposePlayerKind = RemoteComposePlayerKind.EMBEDDED

  /**
   * The player [raw] names, or null when it names none — blank, unset, or a value neither player
   * answers to.
   *
   * ## The names, and why there are several
   *
   * `cmp-android` is a **misleading historical name** and the reason this doc block exists. It does
   * not mean "the CMP player on Android": it names the vendored **AndroidX embedded** player
   * (`third-party-rc-embedded-player`, upstream's `player-compose-embedded`). The genuine CMP
   * player is a different codebase — `rc-player-compose`, with its own runtime — and the viewer
   * lane that actually runs it is `cmp-wasm`. So the `cmp-` prefix spans two unrelated
   * implementations, which is a trap for anyone reading a `?rcPlayer=` value and inferring what
   * drew the pixels.
   *
   * The canonical name for each player is therefore its **implementation**:
   * * `androidx-embedded` — the vendored AndroidX embedded player. Historical: `cmp`,
   *   `cmp-android`, `embedded`.
   * * `androidx-view` — the `AndroidView`-hosted `RemoteComposePlayer` from `remote-player-view`.
   *   Historical: `java`, `view`.
   *
   * Every historical spelling stays accepted, permanently and without deprecation: they are on the
   * wire in published `?rcPlayer=` links, in `capturePlayer` sidecars and in served HTML
   * attributes, so rejecting one would break a bookmark to prove a point. New names are what this
   * repository *writes* in prose and what the viewer shows; old names are what it *reads*. Case-
   * and whitespace-insensitive throughout.
   *
   * The viewer's other three lanes are deliberately **not** accepted here, because none is a
   * render-time player this property can select:
   * * `rcplayer-wasm` (historically `cmp-wasm`) — the rc-players CMP player, in the browser. This
   *   is the lane that genuinely runs `rc-player-compose`, which is why the `cmp-` prefix on its
   *   Android and JVM neighbours is so misleading.
   * * `camaelon-js` (historically `js`) — the vendored TypeScript player, also in the browser, from
   *   `camaelon/remotecompose-experiments`.
   * * `androidx-embedded-jvm` (historically `cmp-jvm`) — the same vendored AndroidX player as
   *   `androidx-embedded`, desktop-JVM cut, rendering in its own subprocess.
   *
   * A build asking for one of them should hear so rather than silently get the default.
   */
  fun fromWire(raw: String?): RemoteComposePlayerKind? =
    when (raw?.trim()?.lowercase()) {
      // Canonical first, historical after — the order is documentation, not behaviour.
      "androidx-embedded",
      "cmp",
      "cmp-android",
      "embedded" -> RemoteComposePlayerKind.EMBEDDED
      "androidx-view",
      "java",
      "view" -> RemoteComposePlayerKind.VIEW
      else -> null
    }

  /**
   * The player to draw with given [raw] — [DEFAULT] when it names none.
   *
   * An unrecognised value is reported on stderr rather than silently ignored: it is nearly always a
   * typo in a `-PcomposePreview.rcPlayer=` invocation, or one of the viewer-only lanes above, and a
   * silent fallback would draw the default while the author believes they pinned the other player.
   * It is not fatal — a preview render should not die over a player selection.
   */
  fun resolve(raw: String?): RemoteComposePlayerKind {
    val selected = fromWire(raw)
    if (selected == null && !raw.isNullOrBlank()) {
      System.err.println(
        "compose-preview: -D$PROPERTY=$raw names no render-time Remote Compose player; drawing " +
          "with the default (androidx-embedded). Valid values: androidx-embedded (aka cmp, " +
          "cmp-android, embedded), androidx-view (aka java, view)."
      )
    }
    return selected ?: DEFAULT
  }

  /** [PROPERTY] as resolved in this JVM. */
  val configured: RemoteComposePlayerKind by lazy { resolve(System.getProperty(PROPERTY)) }
}
