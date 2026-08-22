package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind

/**
 * A Remote Compose render backend the `compose-preview serve` viewer can offer as a per-preview
 * option — the live counterpart of the columns the offline `rc-compare` pipeline diffs.
 *
 * The four are genuinely different renderers of the *same* captured `ir/<id>.rc` document, not
 * skins over one engine, so a preview can look different under each:
 *
 * * [JS] — the vendored TypeScript player (`RC.RcdPlayer`, `third_party/remote-compose-player`),
 *   run **client-side** in the viewer's `<canvas>`. Needs only the `.rc` bytes (served over `GET
 *   /render/<id>.rc`); no daemon, no server render. This is the long-standing in-browser lane.
 * * [CMP_WASM] — this repository's non-JVM Compose Multiplatform player, compiled to Wasm and
 *   painting through Skiko in a browser iframe. It consumes the same captured bytes as [JS], but
 *   its codecs and behavior are implemented against AndroidX remote-core rather than the vendored
 *   TypeScript player.
 * * [JAVA] — the AOSP `remote-player-view` `RemoteComposePlayer` (an Android `View` painting into a
 *   framework `Canvas`), driven **server-side** by the daemon via [RemoteComposePlayerKind.VIEW].
 *   Was the default snapshot player for a Remote Compose preview on an Android backend; that is now
 *   [CMP_ANDROID], and this lane is what a preview pins itself to (or a `?rcPlayer=java` asks for)
 *   when the framework `Canvas` is the point.
 * * [CMP_ANDROID] — the vendored AndroidX embedded `RcPlayer` (`:third-party-rc-embedded-player`),
 *   which interprets the document's operation tree into Compose layout/draw nodes directly, driven
 *   server-side via [RemoteComposePlayerKind.EMBEDDED]. The default: it is what a capture bakes
 *   through, what an unqualified replay uses, and what the viewer opens on.
 * * [CMP_JVM] — the same embedded player over Skiko/Desktop
 *   (`:third-party-rc-embedded-player-jvm`), rendered **server-side** by [RcJvmServerRenderer]: it
 *   spawns the module's `RcJvmRenderMain` as a one-shot subprocess off the CLI install's
 *   `lib-rcjvm`
 *     + `lib-daemon-desktop` sidecars (Compose Desktop + Skiko kept out of the CLI's own
 *       classpath). Unlike [JAVA] / [CMP_ANDROID] it does **not** ride the daemon
 *       `remoteCompose.player` override — [playerKind] stays null and [ServeHttpServer] renders it
 *       directly from the captured `.rc` — so a host enables it (via [ServeHost.supportsCmpJvm])
 *       whenever it carries the document, can size a render for it, and the sidecar is installed.
 *       Where the sidecar is absent (a headless host, or a build that didn't stage it) the chip
 *       stays disabled, exactly as before this lane existed.
 *
 * The viewer always renders every entry as a chip and enables the subset a host reports through
 * [ServeHost.enabledRcPlayersFor]; the rest are shown disabled. [wire] is the stable id used both
 * in the `rcPlayer=` render query param and the `/api/previews` capability list.
 */
enum class RcPlayerBackend(
  /** Stable wire id — the `rcPlayer=` query value and the `/api/previews` capability spelling. */
  val wire: String,
  /** Short human label for the selector chip. */
  val label: String,
  /**
   * The daemon player kind a **server-side** backend renders through, or null for the lanes that
   * don't ride the daemon `remoteCompose.player` override: the client-side [JS] lane and the
   * [CMP_JVM] lane (which renders in its own isolated subprocess, see [RcJvmServerRenderer]).
   * Drives [ServeOverrides]'s mapping of the `rcPlayer=` param onto
   * [ee.schimke.composeai.daemon.protocol.RemoteComposeOverride.player].
   */
  val playerKind: RemoteComposePlayerKind?,
  /**
   * True when the browser plays the `.rc` document itself (the [JS] lane); false for a PNG lane.
   */
  val clientSide: Boolean,
  /**
   * This backend's column id in the catalog's published `rc-compare` staging
   * ([RcCompareManifest.lanes]), or null when the offline pipeline has no column for it.
   *
   * The offline parity run already draws every `ir/<id>.rc` document with every player, so this is
   * what lets a bare `?rcPlayer=<wire>` browse be answered from published bytes instead of a daemon
   * render.
   *
   * [JAVA] maps to **nothing**. It used to own the `baked` column, because the catalog's baked PNG
   * was a view-backed capture and therefore the reference the other lanes were scored against. It
   * isn't any more: `RemoteOverridablePreview` defaults to [RemoteComposePlayerKind.EMBEDDED], so
   * `baked` is an embedded capture and serving it for `?rcPlayer=java` would hand back the wrong
   * player's pixels under a confident `200`. The java lane therefore routes to the daemon, which
   * can still draw it on request.
   */
  val rcCompareLane: String?,
) {
  JS("js", "JS", playerKind = null, clientSide = true, rcCompareLane = "js"),
  CMP_WASM(
    "cmp-wasm",
    "CMP Wasm",
    playerKind = null,
    clientSide = true,
    rcCompareLane = "cmp-wasm",
  ),
  JAVA(
    "java",
    "Java",
    playerKind = RemoteComposePlayerKind.VIEW,
    clientSide = false,
    rcCompareLane = null,
  ),
  CMP_ANDROID(
    "cmp-android",
    "CMP Android",
    playerKind = RemoteComposePlayerKind.EMBEDDED,
    clientSide = false,
    rcCompareLane = "embedded",
  ),
  CMP_JVM("cmp-jvm", "CMP JVM", playerKind = null, clientSide = false, rcCompareLane = "cmp-jvm");

  companion object {
    /** The fixed universe the viewer renders as chips, in display order. */
    val UNIVERSE: List<RcPlayerBackend> = entries.toList()

    /** The backend for [wire], or null when it names none. Case-insensitive. */
    fun fromWire(wire: String?): RcPlayerBackend? =
      wire?.lowercase()?.let { v -> entries.firstOrNull { it.wire == v } }

    /**
     * The server-side backend a `rcPlayer=` render param selects **through the daemon**, or null
     * otherwise. Accepts the backend [wire] ids (`java`, `cmp-android`) and the daemon-native
     * player-kind spellings (`view`, `embedded`) as aliases, so a link can be written either way.
     * `js` (client-side) and `cmp-jvm` yield null: `js` replays in-browser, and `cmp-jvm` renders
     * in its own subprocess lane ([ServeHttpServer] handles `rcPlayer=cmp-jvm` directly), so
     * neither rides the daemon override this maps.
     */
    fun serverSideFromParam(raw: String): RcPlayerBackend? =
      when (raw.lowercase()) {
        "java",
        "view" -> JAVA
        "cmp-android",
        "embedded" -> CMP_ANDROID
        else -> null
      }
  }
}
