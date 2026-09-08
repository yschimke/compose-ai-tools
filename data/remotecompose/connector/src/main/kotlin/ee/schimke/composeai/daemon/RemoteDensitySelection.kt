package ee.schimke.composeai.daemon

/**
 * Which [androidx.compose.remote.creation.compose.capture.RemoteDensity] a Remote Compose capture
 * records against — whether the document carries density and font scale as **numbers baked at
 * capture time** or as **references to the player's own system variables**.
 *
 * This is the difference between a document that answers `?fontScale=2` and one that cannot. The
 * creation library offers exactly two:
 * * [RemoteCaptureDensity.FIXED] — `RemoteDensity.from(displayInfo)`, which folds
 *   `displayInfo.density.density` and `.fontScale` into literal `RemoteFloat` constants. Every
 *   sp→px and dp→px the capture performs is resolved before a byte is written, so no player can
 *   move them afterwards. This is what every `remote-m3` sticker baked through until this setting
 *   existed, and it is why a `?fontScale=` request on one came back byte-identical.
 * * [RemoteCaptureDensity.HOST] — `RemoteDensity.Host`, which binds density to
 *   `RemoteContext.FLOAT_DENSITY` and font scale to `Rc.System.FONT_SIZE / 14 / density`, the
 *   inverse of the `14 × density × fontScale` convention every player writes `ID_FONT_SIZE` with.
 *   The conversions become expressions over those variables, resolved at paint time, so the same
 *   document renders at whatever density and font scale the player is set to.
 *
 * ## Why this is a setting and not just the better value
 *
 * [RemoteCaptureDensity.HOST] defers **density as well as font scale** — it is not a font-only
 * switch. Under it every `RemoteDp.toPx()` in the capture becomes an expression over
 * `FLOAT_DENSITY` rather than a constant, which means a replay whose player density differs from
 * the capture's silently rescales the whole document instead of drawing it at the size the baked
 * PNG was measured at. That is a desirable property (it is what makes one document serve many
 * densities) and a hazard (it makes the player's density a correctness input where it used to be
 * ignored), and which one it is depends on the consumer:
 * * `serve`'s replay lanes size a render to the baked PNG and pick the density from
 *   `renderDensityFor(previewId)`, so they agree with the capture and gain the font-scale axis;
 * * a consumer that replays a document at an arbitrary density gets geometry that follows, which is
 *   the point — but it is a change in what a captured `.rc` file *means*.
 *
 * So the value is per build, and [DEFAULT] stays [RemoteCaptureDensity.FIXED]: an existing catalog
 * keeps the bytes it has until its owner opts in and re-bakes. `wear-m3-catalog` opts in through
 * `composePreview.rcDensity=host` in its own `gradle.properties`.
 *
 * ## What outranks what
 *
 * Unlike [RemoteComposePlayerSelection] there is no per-preview or per-request tier: the choice is
 * made when the document is *written*, and nothing downstream can revisit it. A render request
 * cannot ask a constant-folded document to scale. That is the whole asymmetry — the player
 * selection picks who draws an existing document, this picks what gets written down.
 *
 * Read once per JVM ([configured]) rather than per capture, so an unusable value is reported once.
 */
object RemoteDensitySelection {
  /**
   * System property naming the build-wide capture density.
   *
   * The Gradle plugin resolves `-PcomposePreview.rcDensity` / `-Dcomposeai.render.rcDensity` onto
   * every render and daemon JVM (see `composeAiRcDensity`), because the property is read *here*, in
   * the JVM that captures, not on the Gradle one.
   */
  const val PROPERTY: String = "composeai.render.rcDensity"

  /**
   * What a capture records when nothing selects otherwise: [RemoteCaptureDensity.FIXED], the
   * creation library's own `RemoteDensity.from(...)` behaviour and what every document baked before
   * this setting existed carries.
   *
   * Defaulting to the *worse* value for font scaling is deliberate. Flipping it changes the bytes
   * of every captured document in every consuming build, and a document's geometry starts tracking
   * the player's density — a change a catalog should make when it re-bakes and looks at the pixels,
   * not one it should discover because this repo shipped a release.
   */
  val DEFAULT: RemoteCaptureDensity = RemoteCaptureDensity.FIXED

  /**
   * The capture density [raw] names, or null when it names none — blank, unset, or unrecognised.
   *
   * Case- and whitespace-insensitive, and accepts the spellings a reader is likely to reach for:
   * `host` / `deferred` / `variable` for the host-variable lane, `fixed` / `capture` / `literal` /
   * `baked` for the constant-folded one.
   */
  fun fromWire(raw: String?): RemoteCaptureDensity? =
    when (raw?.trim()?.lowercase()) {
      "host",
      "deferred",
      "variable" -> RemoteCaptureDensity.HOST
      "fixed",
      "capture",
      "literal",
      "baked" -> RemoteCaptureDensity.FIXED
      else -> null
    }

  /**
   * The capture density to record with given [raw] — [DEFAULT] when it names none.
   *
   * An unrecognised value is reported on stderr rather than silently ignored: it is nearly always a
   * typo in a `-PcomposePreview.rcDensity=` invocation, and a silent fallback would bake the
   * constant-folded document while the author believes they opted into host scaling — a difference
   * they would next see as a `?fontScale=` request that does nothing. Not fatal; a preview render
   * should not die over a capture setting.
   */
  fun resolve(raw: String?): RemoteCaptureDensity {
    val selected = fromWire(raw)
    if (selected == null && !raw.isNullOrBlank()) {
      System.err.println(
        "compose-preview: -D$PROPERTY=$raw names no Remote Compose capture density; capturing " +
          "with the default (fixed). Valid values: host (aka deferred, variable), fixed (aka " +
          "capture, literal, baked)."
      )
    }
    return selected ?: DEFAULT
  }

  /** [PROPERTY] as resolved in this JVM. */
  val configured: RemoteCaptureDensity by lazy { resolve(System.getProperty(PROPERTY)) }
}

/**
 * How a capture records the density and font scale it converts dp and sp with. See
 * [RemoteDensitySelection] for what each costs and why the default is [FIXED].
 */
enum class RemoteCaptureDensity {
  /** Constants folded in at capture time — `RemoteDensity.from(displayInfo)`. */
  FIXED,
  /** References to the player's `FLOAT_DENSITY` / `FONT_SIZE` variables — `RemoteDensity.Host`. */
  HOST,
}
