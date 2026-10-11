package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind

/**
 * A Remote Compose render backend the serve viewer offers per preview — the live counterpart of the
 * offline `rc-compare` columns. Each is a genuinely different renderer of the same `ir/<id>.rc`;
 * each [wire] id names the implementation, and `cmp-` always means the CMP player
 * (`rc-player-compose`):
 *
 * * [ANDROIDX_VIEW] — AndroidX `remote-player-view` (a `View` on a framework `Canvas`), drawn by
 *   the
 *   daemon via [RemoteComposePlayerKind.VIEW].
 * * [ANDROIDX_EMBEDDED] — the vendored AndroidX embedded `RcPlayer`, drawn by the daemon via
 *   [RemoteComposePlayerKind.EMBEDDED]. The default, and what captures bake through.
 * * [CMP_ANDROID] — the CMP player on Android, via the daemon's `cmp-android` backend through
 *   `playerId` ([daemonPlayerId]).
 * * [CMP_JVM] — the CMP player over Skiko/Desktop in [RcJvmServerRenderer]'s subprocess (not the
 *   daemon); enabled via [ServeHost.supportsCmpJvm].
 * * [CMP_WASM] — the CMP player compiled to Wasm, in a browser iframe.
 * * [CAMAELON_JS] — the vendored TypeScript player (`RC.RcdPlayer`), client-side in a `<canvas>`.
 *
 * The viewer shows every entry and enables those [ServeHost.enabledRcPlayersFor] reports. [wire] is
 * used in `rcPlayer=` and `/api/previews`; [fromWire] also reads the old spellings.
 */
public enum class RcPlayerBackend(
  /** Canonical wire id: the `rcPlayer=` value and the `/api/previews` capability spelling. */
  public val wire: String,
  /** Short human label for the selector chip: the implementation that draws. */
  public val label: String,
  /**
   * The daemon player kind via `remoteCompose.player`, or null for lanes that don't use it
   * (client-side lanes, [CMP_JVM], and [CMP_ANDROID], which uses [daemonPlayerId]).
   */
  public val playerKind: RemoteComposePlayerKind?,
  /**
   * The id requested via `remoteCompose.playerId`; only [CMP_ANDROID], since the `player` enum
   * names just the two AndroidX players.
   */
  public val daemonPlayerId: String?,
  /** True when the browser plays the `.rc` document itself; false for a PNG lane. */
  public val clientSide: Boolean,
  /**
   * This backend's column in the catalog's published `rc-compare` staging
   * ([RcCompareManifest.lanes]), so a bare `?rcPlayer=<wire>` can be served from published bytes;
   * null when there is no column.
   *
   * Column ids are frozen (they key published assets) and differ from wire ids: `embedded` is
   * [ANDROIDX_EMBEDDED]; the `androidx-embedded` column is an androidx.dev build no backend maps
   * to. [ANDROIDX_VIEW] maps to nothing (the `baked` column is an embedded capture, so serving it
   * would be the wrong player's pixels). [CMP_ANDROID] has no column yet.
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
     * Old spellings still accepted on input because they appear in published links. `cmp-android`
     * is absent: it is now a canonical id with a new meaning; bare `cmp` is retired.
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
     * The backend [wire] names (canonical or legacy), or null; case- and whitespace-insensitive.
     */
    public fun fromWire(wire: String?): RcPlayerBackend? {
      val v = wire?.trim()?.lowercase() ?: return null
      return entries.firstOrNull { it.wire == v } ?: LEGACY_WIRE[v]
    }

    /**
     * The backend a capture player names (`capturePlayer` field or `data-rc-baked-player`
     * attribute), or null. Unlike [fromWire], older daemons wrote `cmp-android` for the embedded
     * player and `java` for the View player, and captures only ever use the AndroidX players. Never
     * use for `?rcPlayer=`.
     */
    public fun fromCapturePlayer(raw: String?): RcPlayerBackend? =
      when (val v = raw?.trim()?.lowercase()) {
        "cmp-android" -> ANDROIDX_EMBEDDED
        else -> fromWire(v)?.takeIf { it.playerKind != null }
      }

    /**
     * The backend a `rcPlayer=` param selects through the daemon ([ANDROIDX_VIEW],
     * [ANDROIDX_EMBEDDED], [CMP_ANDROID], canonical or legacy), else null.
     */
    public fun serverSideFromParam(raw: String): RcPlayerBackend? =
      fromWire(raw)?.takeIf { it.ridesDaemon }

    /**
     * True when [raw] names a lane that never rides the daemon ([CAMAELON_JS], [CMP_WASM],
     * [CMP_JVM]), so `rcPlayer=` must not forward it as a `playerId` the daemon would only refuse.
     */
    public fun isNonDaemonLane(raw: String): Boolean = fromWire(raw)?.ridesDaemon == false

    /**
     * Whether [raw] is shaped like a registrable player id: lower-case letters, digits, `.`, `_`,
     * `-`, starting with a letter or digit, at most 64 chars. Shape only.
     */
    public fun isPlayerIdShaped(raw: String): Boolean = PLAYER_ID.matches(raw.trim().lowercase())

    private val PLAYER_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
  }
}
