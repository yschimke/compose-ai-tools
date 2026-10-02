package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind

/**
 * A Remote Compose render backend the `compose-preview serve` viewer can offer as a per-preview
 * option — the live counterpart of the columns the offline `rc-compare` pipeline diffs.
 *
 * These are genuinely different renderers of the *same* captured `ir/<id>.rc` document, not skins
 * over one engine, so a preview can look different under each. Every [wire] id names the
 * **implementation** that draws, and `cmp-` means the CMP player (`rc-player-compose`) and nothing
 * else — the same table the rc-players README documents:
 *
 * * [ANDROIDX_VIEW] — the AndroidX `remote-player-view` `RemoteComposePlayer` (an Android `View`
 *   painting into a framework `Canvas`), drawn **server-side** by the daemon via
 *   [RemoteComposePlayerKind.VIEW]. What a preview pins itself to when the framework `Canvas` is
 *   the point.
 * * [ANDROIDX_EMBEDDED] — the vendored AndroidX embedded `RcPlayer`
 *   (`:third-party-rc-embedded-player`), which interprets the operation tree into Compose
 *   layout/draw nodes, drawn server-side via [RemoteComposePlayerKind.EMBEDDED]. The default: what
 *   a capture bakes through and what an unqualified replay uses.
 * * [CMP_ANDROID] — the CMP player on Android, drawn server-side by the daemon's replay-only
 *   `cmp-android` backend. It rides `RemoteComposeOverride.playerId` ([daemonPlayerId]), not the
 *   `player` enum, which names the two AndroidX players only.
 * * [CMP_JVM] — the CMP player over Skiko/Desktop, rendered server-side by [RcJvmServerRenderer] in
 *   an isolated `:rc-render-jvm` subprocess off the CLI install's `lib-rcjvm` +
 *   `lib-daemon-desktop` sidecars. It does not ride the daemon, so a host enables it (via
 *   [ServeHost.supportsCmpJvm]) whenever it carries the document, can size a render, and the
 *   sidecar is installed.
 * * [CMP_WASM] — the CMP player compiled to Wasm, painting through Skiko in a browser iframe.
 * * [CAMAELON_JS] — the vendored TypeScript player (`RC.RcdPlayer`, from
 *   `camaelon/remotecompose-experiments`), run **client-side** in the viewer's `<canvas>` from the
 *   `.rc` bytes alone.
 *
 * The viewer always renders every entry as a chip and enables the subset a host reports through
 * [ServeHost.enabledRcPlayersFor]; the rest are shown disabled. [wire] is the id used both in the
 * `rcPlayer=` render query param and the `/api/previews` capability list; [fromWire] still reads
 * the spellings these lanes had before they were named by implementation.
 */
public enum class RcPlayerBackend(
  /**
   * Canonical wire id — the `rcPlayer=` query value and the `/api/previews` capability spelling.
   */
  public val wire: String,
  /** Short human label for the selector chip: the implementation that draws. */
  public val label: String,
  /**
   * The daemon player kind a backend renders through via `remoteCompose.player`, or null for every
   * lane that does not: the client-side lanes, [CMP_JVM] (its own subprocess, see
   * [RcJvmServerRenderer]) and [CMP_ANDROID] (which rides [daemonPlayerId] instead).
   */
  public val playerKind: RemoteComposePlayerKind?,
  /**
   * The id a backend asks the daemon for through `remoteCompose.playerId`, or null when it does not
   * ride that field. Only [CMP_ANDROID]: the daemon's `player` enum names the two AndroidX players
   * and nothing else, so its third built-in player is reached by id.
   */
  public val daemonPlayerId: String?,
  /** True when the browser plays the `.rc` document itself; false for a PNG lane. */
  public val clientSide: Boolean,
  /**
   * This backend's column id in the catalog's published `rc-compare` staging
   * ([RcCompareManifest.lanes]), or null when the offline pipeline has no column for it. Lets a
   * bare `?rcPlayer=<wire>` browse be answered from published bytes instead of a render.
   *
   * These column ids are deliberately **not** the wire ids and were not renamed with them: they key
   * staged assets in already-published catalogs (`rc-compare-summary.json`, the `rc-*` render
   * directories). Note `embedded` is the vendored [ANDROIDX_EMBEDDED] column, while the column
   * named `androidx-embedded` is the androidx.dev build of the same player, which no backend maps
   * to.
   *
   * [ANDROIDX_VIEW] maps to nothing: the catalog's `baked` column is an embedded capture (that is
   * `RemoteOverridablePreview`'s default), so serving it for `?rcPlayer=androidx-view` would hand
   * back the wrong player's pixels under a confident `200`; the daemon draws that lane on request.
   * [CMP_ANDROID] has no offline column yet.
   */
  public val rcCompareLane: String?,
) {
  CAMAELON_JS(
    "camaelon-js",
    "Camaelon JS",
    playerKind = null,
    daemonPlayerId = null,
    clientSide = true,
    rcCompareLane = "js",
  ),
  CMP_WASM(
    "cmp-wasm",
    "CMP Wasm",
    playerKind = null,
    daemonPlayerId = null,
    clientSide = true,
    rcCompareLane = "cmp-wasm",
  ),
  ANDROIDX_VIEW(
    "androidx-view",
    "AndroidX View",
    playerKind = RemoteComposePlayerKind.VIEW,
    daemonPlayerId = null,
    clientSide = false,
    rcCompareLane = null,
  ),
  ANDROIDX_EMBEDDED(
    "androidx-embedded",
    "AndroidX Embedded",
    playerKind = RemoteComposePlayerKind.EMBEDDED,
    daemonPlayerId = null,
    clientSide = false,
    rcCompareLane = "embedded",
  ),
  CMP_ANDROID(
    "cmp-android",
    "CMP Android",
    playerKind = null,
    daemonPlayerId = "cmp-android",
    clientSide = false,
    rcCompareLane = null,
  ),
  CMP_JVM(
    "cmp-jvm",
    "CMP JVM",
    playerKind = null,
    daemonPlayerId = null,
    clientSide = false,
    rcCompareLane = "cmp-jvm",
  );

  /** True when the daemon draws this lane, through either [playerKind] or [daemonPlayerId]. */
  public val ridesDaemon: Boolean
    get() = playerKind != null || daemonPlayerId != null

  public companion object {
    /** The fixed universe the viewer renders as chips, in display order. */
    public val UNIVERSE: List<RcPlayerBackend> = entries.toList()

    /**
     * Spellings these lanes had before they were named by implementation, still accepted on input
     * because they are in published `?rcPlayer=` links. `cmp-android` is not here: it is a
     * canonical id with a new meaning (the CMP player on Android), and the bare `cmp` is retired.
     */
    private val LEGACY_WIRE: Map<String, RcPlayerBackend> =
      mapOf(
        "java" to ANDROIDX_VIEW,
        "view" to ANDROIDX_VIEW,
        "embedded" to ANDROIDX_EMBEDDED,
        "rcplayer-jvm" to CMP_JVM,
        "rcplayer-wasm" to CMP_WASM,
        "js" to CAMAELON_JS,
      )

    /**
     * The backend [wire] names — a canonical id or a legacy spelling — or null when it names none.
     * Case- and whitespace-insensitive.
     */
    public fun fromWire(wire: String?): RcPlayerBackend? {
      val v = wire?.trim()?.lowercase() ?: return null
      return entries.firstOrNull { it.wire == v } ?: LEGACY_WIRE[v]
    }

    /**
     * The backend a **capture player** names — a `capturePlayer` sidecar / `.remotecompose.json`
     * field, or a `data-rc-baked-player` attribute — or null when it names none.
     *
     * Differs from [fromWire] in exactly the place that matters: daemons before the players were
     * named by implementation wrote `cmp-android` for the AndroidX embedded player and `java` for
     * the AndroidX View player, so here `cmp-android` reads as [ANDROIDX_EMBEDDED]. A capture is
     * always drawn by one of the two AndroidX players, so nothing else is accepted. Never use this
     * for a `?rcPlayer=` request, where `cmp-android` means [CMP_ANDROID].
     */
    public fun fromCapturePlayer(raw: String?): RcPlayerBackend? =
      when (val v = raw?.trim()?.lowercase()) {
        "cmp-android" -> ANDROIDX_EMBEDDED
        else -> fromWire(v)?.takeIf { it.playerKind != null }
      }

    /**
     * The backend a `rcPlayer=` render param selects **through the daemon**, or null otherwise:
     * [ANDROIDX_VIEW], [ANDROIDX_EMBEDDED] or [CMP_ANDROID], by canonical id or legacy spelling.
     * The client-side lanes and [CMP_JVM] (its own subprocess lane) yield null.
     */
    public fun serverSideFromParam(raw: String): RcPlayerBackend? =
      fromWire(raw)?.takeIf { it.ridesDaemon }

    /**
     * True when [raw] names one of the lanes that never ride the daemon — the in-browser
     * [CAMAELON_JS] / [CMP_WASM] players and the [CMP_JVM] subprocess — by canonical id or legacy
     * spelling. Such a value is neither a built-in daemon player nor a registered one, so
     * `rcPlayer=` must not forward it as a `playerId`: the daemon would only refuse it, after a
     * render round trip.
     */
    public fun isNonDaemonLane(raw: String): Boolean = fromWire(raw)?.ridesDaemon == false

    /**
     * Whether [raw] is spelled like a player id a daemon could have registered: lower-case letters,
     * digits, `.`, `_` and `-`, starting with a letter or digit, at most 64 characters. Shape only
     * — whether a player answers to it is the daemon's to say.
     */
    public fun isPlayerIdShaped(raw: String): Boolean = PLAYER_ID.matches(raw.trim().lowercase())

    private val PLAYER_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
  }
}
