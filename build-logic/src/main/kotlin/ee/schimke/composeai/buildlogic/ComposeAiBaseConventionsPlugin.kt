package ee.schimke.composeai.buildlogic

import com.ncorti.ktfmt.gradle.KtfmtExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * Conventions that previously lived in the root build's `allprojects {}` block. Isolated Projects
 * forbids a project configuring its siblings, so they're pushed down to each project: this plugin
 * is applied to every project from `settings.gradle.kts` via the IP-safe
 * `gradle.lifecycle.beforeProject` hook.
 *
 * Living in `build-logic` lets the ktfmt extension be configured with its real type
 * (`extensions.configure<KtfmtExtension>`) rather than reflectively — the settings script can't
 * import the type, but this convention plugin's classpath already carries both ktfmt and the Kotlin
 * Gradle plugin it links against.
 */
class ComposeAiBaseConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("com.ncorti.ktfmt.gradle")
    project.extensions.configure<KtfmtExtension>("ktfmt") { googleStyle() }

    // History feature gate (`HistoryFeature.ENABLED`, post-1.0). Test JVMs run with the gate
    // flipped on so the history implementation stays green and unbroken for the 1.1 re-enable —
    // production daemons leave the property unset and the const-default `false` keeps the wire-up
    // dead. Re-evaluate (and drop) when the 1.1 cut flips the default to `true`.
    project.tasks.withType<Test>().configureEach {
      systemProperty("composeai.history.enabled", "true")
    }
  }
}
