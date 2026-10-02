package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How a motion capture's output is named.
 *
 * `@AnimatedPreview(format = Apng)` used to get a hard-coded `.gif` name, so the desktop renderer —
 * which honours the format — wrote APNG bytes into a `.gif`. The extension now follows the capture
 * format, and every naming decision that keeps outputs apart is made on the capture *kind* rather
 * than on its extension. The cases below pin both halves, plus the names that must not move: a GIF
 * `@AnimatedPreview`, an `@InteractionPreview`, and the Lottie animated companion.
 */
class PreviewDiscoveryMotionOutputTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val id = "com.example.PreviewsKt.Spinner"

  private fun animation(format: MotionFormat) =
    AnimationCapture(durationMs = 400, frameIntervalMs = 33, format = format)

  private fun interaction(format: MotionFormat = MotionFormat.APNG) =
    InteractionCapture(
      gesture = InteractionGesture.TAP,
      targets = listOf(0),
      holdMs = 600,
      gapMs = 700,
      leadInMs = 250,
      frameIntervalMs = 16,
      format = format,
    )

  private val focusGif = FocusGifCapture(steps = listOf(FocusCapture(tabIndex = 0)))

  private fun outputs(
    animation: AnimationCapture? = null,
    interaction: InteractionCapture? = null,
    focusGif: FocusGifCapture? = null,
    settle: SettleCapture? = null,
    timings: List<Long> = emptyList(),
  ): List<String> =
    PreviewDiscovery.buildOutputPlan(
        kind = PreviewKind.COMPOSE,
        previewId = id,
        scrolls = emptyList(),
        animation = animation,
        interaction = interaction,
        focuses = emptyList(),
        focusGif = focusGif,
        ambient = null,
        glimmerEnvironments = emptyList(),
        settle = settle,
        gestureHint = null,
        permissions = null,
        launcherWidget = null,
        launcherWidgetResize = null,
        timings = timings,
      )
      .captures
      .map { it.renderOutput }

  @Test
  fun `a GIF animated preview keeps its gif name`() {
    assertThat(outputs(animation = animation(MotionFormat.GIF))).containsExactly("renders/$id.gif")
  }

  @Test
  fun `an APNG animated preview is named apng`() {
    assertThat(outputs(animation = animation(MotionFormat.APNG)))
      .containsExactly("renders/$id.apng")
  }

  @Test
  fun `an APNG animated preview beside a settled still takes the plain stem with its own extension`() {
    assertThat(
        outputs(animation = animation(MotionFormat.APNG), settle = SettleCapture(afterMs = 400))
      )
      .containsExactly("renders/$id.png", "renders/$id.apng")
  }

  @Test
  fun `the anim suffix follows sharing, not the extension`() {
    // A peer `@FocusedPreview(gif = true)` suffixes both outputs whatever the animation's format,
    // so switching the format only ever changes an extension, never the stem.
    assertThat(outputs(animation = animation(MotionFormat.GIF), focusGif = focusGif))
      .containsExactly("renders/${id}_anim.gif", "renders/${id}_focus_gif.gif")
    assertThat(outputs(animation = animation(MotionFormat.APNG), focusGif = focusGif))
      .containsExactly("renders/${id}_anim.apng", "renders/${id}_focus_gif.gif")
    assertThat(outputs(animation = animation(MotionFormat.APNG), timings = listOf(100L)))
      .containsExactly("renders/${id}_TIME_100ms.png", "renders/${id}_anim.apng")
  }

  @Test
  fun `interaction naming is unchanged`() {
    // Alone, it keeps the plain stem beside the static still the plan emits for it.
    assertThat(outputs(interaction = interaction()))
      .containsExactly("renders/$id.png", "renders/$id.apng")
    assertThat(outputs(interaction = interaction(MotionFormat.GIF)))
      .containsExactly("renders/$id.png", "renders/$id.gif")
    // Beside an animation it takes `_interaction`, which keeps an APNG animation and the default
    // APNG interaction apart now that both can be `.apng`.
    assertThat(outputs(animation = animation(MotionFormat.APNG), interaction = interaction()))
      .containsExactly("renders/$id.apng", "renders/${id}_interaction.apng")
    assertThat(outputs(animation = animation(MotionFormat.GIF), interaction = interaction()))
      .containsExactly("renders/$id.gif", "renders/${id}_interaction.apng")
  }

  @Test
  fun `motion capture kind is read off the capture, not its extension`() {
    assertThat(
        PreviewDiscovery.motionKindOf(
          Capture(renderOutput = "renders/a.png", animation = animation(MotionFormat.APNG))
        )
      )
      .isEqualTo(PreviewDiscovery.MotionKind.ANIMATION)
    assertThat(
        PreviewDiscovery.motionKindOf(
          Capture(renderOutput = "renders/a.png", interaction = interaction())
        )
      )
      .isEqualTo(PreviewDiscovery.MotionKind.INTERACTION)
    assertThat(
        PreviewDiscovery.motionKindOf(Capture(renderOutput = "renders/a.gif", focusGif = focusGif))
      )
      .isEqualTo(PreviewDiscovery.MotionKind.FOCUS_GIF)
    // An extension alone says nothing: a `.gif`-named still is a still, and so is the Lottie
    // companion's `.png` at the capture level (it is an asset sidecar, not a motion capture kind).
    assertThat(PreviewDiscovery.motionKindOf(Capture(renderOutput = "renders/a.gif"))).isNull()
    assertThat(PreviewDiscovery.motionKindOf(Capture(renderOutput = "renders/a_animated.png")))
      .isNull()
  }

  @Test
  fun `a motion capture sharing a still's path is suffixed by kind`() {
    // A motion format written with a still's extension would otherwise overwrite the still.
    val still = Capture(renderOutput = "renders/Foo.png")
    val motion = Capture(renderOutput = "renders/Foo.png", animation = animation(MotionFormat.APNG))
    val interactionMotion = Capture(renderOutput = "renders/FOO.PNG", interaction = interaction())

    assertThat(
        PreviewDiscovery.separateMotionOutputs(listOf(still, motion, interactionMotion)).map {
          it.renderOutput
        }
      )
      .containsExactly("renders/Foo.png", "renders/Foo_anim.png", "renders/FOO_interaction.PNG")
      .inOrder()
  }

  @Test
  fun `outputs that collide with nothing are left alone`() {
    val captures =
      listOf(
        Capture(renderOutput = "renders/Foo.png"),
        Capture(renderOutput = "renders/Foo.apng", animation = animation(MotionFormat.APNG)),
        Capture(renderOutput = "renders/Foo_interaction.apng", interaction = interaction()),
      )
    assertThat(PreviewDiscovery.separateMotionOutputs(captures)).isEqualTo(captures)
  }

  @Test
  fun `an APNG request is kept where the backend supports it`() {
    val warnings = mutableListOf<String>()
    val resolved =
      PreviewDiscovery.resolveAnimationFormat(
        animation(MotionFormat.APNG),
        apngSupported = true,
        owner = id,
        warnings = warnings,
      )
    assertThat(resolved.format).isEqualTo(MotionFormat.APNG)
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `an APNG request on a GIF-only backend is recorded as GIF and says so`() {
    // An Android renderer older than compose-preview-daemon 3.13.0 encodes `@AnimatedPreview` as
    // GIF whatever the annotation asks, so naming the output `.apng` would put GIF bytes behind an
    // APNG name. The Gradle plugin no longer declares any backend GIF-only; a non-Gradle caller
    // can.
    val warnings = mutableListOf<String>()
    val resolved =
      PreviewDiscovery.resolveAnimationFormat(
        animation(MotionFormat.APNG),
        apngSupported = false,
        owner = id,
        warnings = warnings,
      )
    assertThat(resolved.format).isEqualTo(MotionFormat.GIF)
    assertThat(warnings).hasSize(1)
    assertThat(warnings.single()).contains(id)
    assertThat(warnings.single()).contains("format = Apng")
  }

  @Test
  fun `a GIF request is never warned about`() {
    val warnings = mutableListOf<String>()
    val gif = animation(MotionFormat.GIF)
    assertThat(PreviewDiscovery.resolveAnimationFormat(gif, false, id, warnings)).isEqualTo(gif)
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `the Lottie animated companion keeps its png name on either backend`() {
    // The companion is an APNG written as `<stem>_animated.png` (so it is served as `image/png`);
    // nothing about the `@AnimatedPreview` format rules may reach it.
    for (apngSupported in listOf(true, false)) {
      val res = tempDir.newFolder("resources-$apngSupported")
      res
        .resolve("loading.json")
        .writeText("""{"v":"5.7.0","fr":30,"ip":0,"op":60,"w":240,"h":120,"layers":[]}""")
      val outcome =
        PreviewDiscovery.discover(
          PreviewDiscovery.Input(
            classDirs = emptyList(),
            dependencyJars = emptyList(),
            sourceFiles = emptyList(),
            moduleName = ":app",
            variantName = "desktop",
            projectDirectory = res,
            failOnEmpty = false,
            resourceDirs = listOf(res),
            animatedPreviewApngSupported = apngSupported,
          )
        ) as PreviewDiscovery.Outcome.Success
      val outputs = outcome.manifest.previews.single().captures.map { it.renderOutput }
      assertThat(outputs).hasSize(2)
      val stem = outputs.first().removePrefix("renders/").removeSuffix(".png")
      assertThat(outputs)
        .containsExactly("renders/$stem.png", "renders/${stem}_animated.png")
        .inOrder()
    }
  }
}
