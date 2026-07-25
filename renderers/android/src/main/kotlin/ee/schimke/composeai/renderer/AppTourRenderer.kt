package ee.schimke.composeai.renderer

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController

/**
 * Renders `kind=ACTIVITY` and `kind=APP_TOUR` previews — the app-level surface of the pipeline.
 *
 * Unlike every [PreviewRenderStrategy], there is no composition to produce here: the *activity*
 * owns its content. The renderer launches the consumer's real Activity through Robolectric's
 * [ActivityController] (full lifecycle — `onCreate`'s own `setContent`, its theme, its window) and
 * captures the decor view through the same Roborazzi/hardware-capture path as every other preview.
 *
 * Navigation is real, with the back stack emulated the standard Robolectric way:
 * - A tour step's click fires the matched node's actual `OnClick` semantics action (Compose) or
 *   `View.performClick` (classic views), so whatever `startActivity` the app performs runs for
 *   real.
 * - Robolectric records started activities rather than launching them; after each step the session
 *   drains `ShadowApplication.getNextStartedActivity()` and launches each recorded intent's
 *   resolved activity itself — pausing/stopping the previous one — which is what moves the tour
 *   *between* activities.
 * - A `back` step destroys the top activity and restarts/resumes the previous one.
 *
 * Implicit intents (deep links) resolve through the real, manifest-backed `PackageManager`, so a
 * tour can only deep-link somewhere the manifest's intent-filters actually allow.
 *
 * A [createEmptyComposeRule] wraps the whole tour: it registers Compose roots created by *any*
 * activity launched inside it (the documented multi-activity pattern), gives the same
 * paused-`mainClock` determinism as [RobolectricRenderTestBase.renderDefault], and provides the
 * semantics tree for click targeting.
 */
internal object AppTourRenderer {

  /** Mirrors `RobolectricRenderTestBase.CAPTURE_ADVANCE_MS` — the paused-clock settle window. */
  private const val STEP_ADVANCE_MS = 64L

  /** Safety cap on chained `startActivity` follows per step (splash → redirect → …). */
  private const val MAX_ACTIVITY_FOLLOWS_PER_STEP = 10

  fun render(preview: RenderPreviewEntry, outputDir: File, roborazziOptions: RoborazziOptions) {
    val rule = createEmptyComposeRule()
    val description =
      Description.createTestDescription(AppTourRenderer::class.java, "appTour_${preview.id}")
    val statement =
      object : Statement() {
        override fun evaluate() {
          rule.mainClock.autoAdvance = false
          val session = TourSession(ApplicationProvider.getApplicationContext(), rule)
          try {
            session.launch(preview.params.launchIntent, preview.className.ifEmpty { null })
            // Captures arrive in authored order; sort defensively by step index (ACTIVITY
            // previews have a single step-less capture and sort trivially).
            for (capture in preview.captures.sortedBy { it.tourStep?.index ?: 0 }) {
              capture.tourStep?.let { step ->
                try {
                  session.perform(step)
                } catch (e: Throwable) {
                  throw IllegalStateException(
                    "Tour '${preview.id}' step ${step.index} ('${step.label}') failed: ${e.message}",
                    e,
                  )
                }
              }
              session.settle()
              val leafName =
                capture.renderOutput.substringAfterLast('/').ifEmpty { "${preview.id}.png" }
              session.capture(File(outputDir, leafName), roborazziOptions)
            }
          } finally {
            session.close()
          }
        }
      }
    rule.apply(statement, description).evaluate()
  }

  /**
   * The live state of one tour run: the emulated activity back stack plus the drivers that mutate
   * it. All methods run on the test thread, which under Robolectric is also the main thread.
   */
  private class TourSession(
    private val application: Application,
    private val rule: ComposeTestRule,
  ) {
    private val stack = ArrayDeque<ActivityController<out Activity>>()

    private val topActivity: Activity
      get() =
        checkNotNull(stack.lastOrNull()?.get()) { "Tour has no live activity — launch failed?" }

    /** Launch the tour's start activity. [fallbackClassName] is used when [spec] is null/empty. */
    fun launch(spec: TourIntentSpec?, fallbackClassName: String?) {
      val intent =
        buildIntent(spec)
          ?: fallbackClassName?.let { Intent(Intent.ACTION_MAIN).setClassName(application, it) }
          ?: error("No launch intent and no start activity class recorded on the preview")
      launchIntent(intent)
      followStartedActivities()
    }

    fun perform(step: TourStepCapture) {
      when {
        step.click != null -> performClick(step.click)
        step.intent != null ->
          launchIntent(checkNotNull(buildIntent(step.intent)) { "Empty intent spec" })
        step.back -> performBack()
      }
      followStartedActivities()
    }

    /** Let composition, layout, and pending lifecycle work run under the paused clock. */
    fun settle() {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      rule.mainClock.advanceTimeBy(STEP_ADVANCE_MS)
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    fun capture(file: File, roborazziOptions: RoborazziOptions) {
      topActivity.window.decorView.captureRoboImage(file, roborazziOptions = roborazziOptions)
    }

    fun close() {
      while (stack.isNotEmpty()) {
        val controller = stack.removeLast()
        runCatching {
          controller.pause().stop().destroy()
          Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
      }
    }

    // -- intent plumbing ----------------------------------------------------

    private fun buildIntent(spec: TourIntentSpec?): Intent? {
      if (spec == null) return null
      if (
        spec.activityClassName == null &&
          spec.action == null &&
          spec.data == null &&
          spec.categories.isEmpty()
      ) {
        return null
      }
      val intent = Intent(spec.action ?: Intent.ACTION_MAIN)
      spec.data?.let { intent.data = Uri.parse(it) }
      spec.categories.forEach(intent::addCategory)
      spec.activityClassName?.let {
        intent.component = ComponentName(application.packageName, it)
      }
      spec.extras.forEach { (key, value) -> intent.putExtra(key, value) }
      return intent
    }

    private fun resolveActivityClass(intent: Intent): Class<out Activity> {
      val className =
        intent.component?.className
          ?: application.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.name
          ?: error(
            "No activity resolves $intent — check the manifest's intent-filters " +
              "or use an explicit activityClassName"
          )
      return Class.forName(className).asSubclass(Activity::class.java)
    }

    private fun launchIntent(intent: Intent) {
      val activityClass = resolveActivityClass(intent)
      stack.lastOrNull()?.let { runCatching { it.pause().stop() } }
      val controller = Robolectric.buildActivity(activityClass, intent).setup()
      stack.addLast(controller)
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Robolectric records `startActivity` calls instead of launching them; drain the recorded
     * intents and launch each in order so app-driven navigation (a clicked button's
     * `startActivity`, a splash redirect chain) actually moves the tour forward.
     */
    private fun followStartedActivities() {
      val shadowApplication = Shadows.shadowOf(application)
      var follows = 0
      while (true) {
        val next = shadowApplication.nextStartedActivity ?: break
        check(++follows <= MAX_ACTIVITY_FOLLOWS_PER_STEP) {
          "Gave up following startActivity chains after $MAX_ACTIVITY_FOLLOWS_PER_STEP hops"
        }
        launchIntent(next)
        // Give the new activity a settle window before checking whether it redirected again.
        rule.mainClock.advanceTimeBy(STEP_ADVANCE_MS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
      }
    }

    // -- step actions -------------------------------------------------------

    private fun performBack() {
      check(stack.size >= 2) { "Tour pressed back past the root activity" }
      val top = stack.removeLast()
      top.pause().stop().destroy()
      stack.last().restart().resume()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun performClick(click: TourClickSpec) {
      // Compose surfaces first: match through the semantics tree and fire the real OnClick
      // action — the same dispatch the daemon's uiautomator actions use, deterministic under the
      // paused clock (no synthesized motion-event pipeline to time out).
      val matcher = composeMatcherFor(click)
      if (matcher != null && clickComposeNode(matcher)) return
      // Classic-View fallback: resource id, then visible text.
      if (clickView(click)) return
      error(
        "No clickable target matched $click — for Compose use text/contentDescription/tag " +
          "(merged semantics), for classic views use viewId/text"
      )
    }

    private fun composeMatcherFor(click: TourClickSpec): SemanticsMatcher? =
      when {
        click.tag != null -> hasTestTag(click.tag)
        click.text != null -> hasText(click.text)
        click.contentDescription != null -> hasContentDescription(click.contentDescription)
        else -> null
      }

    private fun clickComposeNode(matcher: SemanticsMatcher): Boolean {
      val node =
        runCatching { rule.onNode(matcher, useUnmergedTree = false).fetchSemanticsNode() }
          .getOrNull() ?: return false
      val onClick = node.config.getOrNull(SemanticsActions.OnClick) ?: return false
      onClick.action?.invoke() ?: return false
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      return true
    }

    private fun clickView(click: TourClickSpec): Boolean {
      val root = topActivity.window.decorView
      val target =
        click.viewId?.let { id ->
          val resId =
            topActivity.resources.getIdentifier(id, "id", topActivity.packageName)
          if (resId != 0) root.findViewById<View>(resId) else null
        } ?: click.text?.let { findViewWithText(root, it) }
      if (target == null || !target.isClickable) return false
      target.performClick()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      return true
    }

    private fun findViewWithText(root: View, text: String): View? {
      if (root is TextView && root.text?.toString() == text) {
        // Click targets are usually the TextView's clickable ancestor (button row, list item).
        var candidate: View? = root
        while (candidate != null && !candidate.isClickable) {
          candidate = candidate.parent as? View
        }
        return candidate ?: root
      }
      if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
          findViewWithText(root.getChildAt(i), text)?.let {
            return it
          }
        }
      }
      return null
    }
  }
}
