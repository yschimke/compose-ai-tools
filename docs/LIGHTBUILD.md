# Lightbuild

[Lightbuild](https://developer.android.com/tools/agents/lightbuild) is Android's
experimental declarative build: a `project.lightbuild.yaml` at the root and a
`lightbuild.yaml` per module, with no Gradle files in the project. It does not
apply third-party Gradle plugins, so the `ee.schimke.composeai.preview` plugin
can't be declared there. The CLI doesn't need it to be.

```sh
lightbuild build app        # once, so the Gradle build below exists
compose-preview render      # from the project root or any module directory
```

## How it works

Lightbuild translates the YAML into an ordinary Gradle build under
`.lightbuild/gradle/` (its own `settings.gradle`, `gradlew` and one build file
per module, whose `projectDir` points back at the module directory) and runs
that. When the walk up from the working directory reaches a
`project.lightbuild.yaml`, the CLI drives that generated build instead, and
auto-injects the plugin into it with the same init script it uses everywhere
else. Renders land where Gradle puts them: `<module>/build/compose-previews/`.

A Lightbuild root always ends the walk. Before the first `lightbuild build` the
CLI stops there and says to run it, rather than driving an enclosing Gradle
build. Lightbuild regenerates `.lightbuild/gradle/` on every build, so don't
edit it; there is nothing to edit.

## Verified against

Lightbuild 0.0.30's `empty-activity-lightbuild` template (`android create
empty-activity-lightbuild`): Gradle 9.5.0, AGP 9.2.0, Kotlin 2.3.20. Both
`@Preview`s in its `app-core` library render.

Previews in a library module work; `MainActivity` in the template lives in that
library while its theme is declared in the `app` module's manifest, so the
activity-tour render of it does not carry the app's theme.
