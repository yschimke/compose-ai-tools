# Isolated Projects vs. the auto-inject workflow

Why the compose-preview CLI / MCP / VS Code workflow can't run under Gradle's
Isolated Projects (IP), what we ship instead, and the upstream Gradle change
that would actually fix it.

## TL;DR

- IP is **not** enabled in this repo's `gradle.properties`, and the plugin +
  init scripts **warn** when IP is detected. See the rationale in
  [gradle.properties](../gradle.properties) and the "Isolated Projects"
  constraint in [AGENTS.md](AGENTS.md).
- The auto-inject init script applies the plugin to every project via
  `allprojects { buildscript { … } }`. That is a cross-project configuration
  IP rejects outright, and the Tooling API daemon honours whatever a project's
  `gradle.properties` sets — so enabling IP (here **or** in a consumer's build)
  breaks every tooling-driven run, not just CI.
- We attempted an IP-safe redesign. The blocker is fundamental: there is no
  Gradle mechanism that is simultaneously **(a)** IP-clean, **(b)** injects a
  plugin without editing build files, and **(c)** puts that plugin on the same
  classloader as AGP. Mechanisms that satisfy (a)+(b) land the plugin on the
  *init* classloader, where AGP types aren't visible (`NoClassDefFoundError`).

## The constraint, precisely

The plugin must, without the consumer editing build files:

1. apply to every project that applies `com.android.application` /
   `com.android.library` / `org.jetbrains.compose`, and
2. see AGP types at configuration time — `AndroidComponentsExtension`,
   `CommonExtension`, `Variant`, `SingleArtifact`, … — which live on each
   project's **buildscript** classpath (AGP is on `buildscript.dependencies`).

(2) means the plugin class itself has to be loaded by the **project buildscript
classloader** (or a child of it), because that's the only classloader from
which AGP's classes are visible.

## What we tested (Gradle 9.5.1, `org.gradle.unsafe.isolated-projects=true`)

Each row is a standalone repro: a 2-module synthetic build with IP on, a file
Maven repo hosting a trivial plugin, and an init script applying it.

### 1. `allprojects { buildscript { … } }` — the current approach

```kotlin
allprojects {
    buildscript {
        repositories { … }
        dependencies { add("classpath", "ee.schimke.composeai.preview:…:<v>") }
    }
    pluginManager.withPlugin("com.android.application") { … }
}
```

> FAILURE: `Project ':' cannot access 'Project.buildscript' functionality on
> subprojects via 'allprojects'`

This works in the non-IP / legacy model because Gradle hoists the
`buildscript {}` inside `allprojects` into each project's buildscript **before**
that project's own script is evaluated. That hoist is cross-project
configuration, which is exactly what IP forbids — and there is no per-project
(`beforeProject`) equivalent that runs early enough.

### 2. `beforeProject` + late buildscript-classpath injection

`gradle.lifecycle.beforeProject {}` is the IP-blessed per-project hook (each
project configures in isolation — touching the project's **own** `buildscript`
from here is IP-clean, unlike `allprojects`). But:

```kotlin
gradle.lifecycle.beforeProject {
    project.buildscript.repositories.maven { … }
    project.buildscript.dependencies.add("classpath", "test.marker:…:1.0")
    project.pluginManager.apply("test.marker")
}
```

> FAILURE: `Plugin with id 'test.marker' not found.`

The project's buildscript classpath is already resolved/locked by the time
`beforeProject` runs. You can mutate the repository/dependency objects, but the
classpath used to resolve plugins-by-id has already been computed, so the late
addition is ignored. **You cannot grow a project's buildscript classpath from
an init script under IP.**

### 3. `initscript { classpath }` + `beforeProject { apply(PluginClass) }`

```kotlin
initscript {
    repositories { … }
    dependencies { classpath("test.agpref:agpref:1.0") }
}
gradle.lifecycle.beforeProject {
    project.pluginManager.apply(test.AgpRefPlugin::class.java) // by CLASS, not id
}
```

> SUCCESS — applied to `:`, `:app`, `:lib`; configuration cache entry stored.

This is IP-clean and actually applies the plugin. Two caveats:

- It must be applied **by class**, not by id: `pluginManager.apply("id")`
  resolves against the project's plugin-resolution classpath (buildscript +
  `pluginManagement`), not the init classpath, so the id isn't found.
- The plugin now runs on the **init classloader**, which is a parent/sibling of
  the project buildscript classloader — it cannot see classes that live on the
  project buildscript classpath. With a plugin that references a
  project-buildscript class (here `fakeagp.AgpExtension`, standing in for
  `AndroidComponentsExtension`):

  > FAILURE: `java.lang.NoClassDefFoundError: fakeagp/AgpExtension`
  > Caused by: `java.lang.ClassNotFoundException: fakeagp.AgpExtension`

This is the same failure mode PR #1483 hit and reverted (see the note in
[AutoInject.kt](../cli/src/main/kotlin/ee/schimke/composeai/cli/AutoInject.kt)).

## Conclusion

| Requirement | `allprojects` | `beforeProject` + late classpath | init-classpath + `apply(Class)` |
| --- | --- | --- | --- |
| IP-clean | ❌ | ✅ | ✅ |
| Injects without editing build files | ✅ | ✅ | ✅ |
| Plugin can see AGP types | ✅ | ✅ (if it applied) | ❌ NoClassDefFoundError |

No mechanism satisfies all three. "Be on the project buildscript classloader
(for AGP visibility)" and "be injected from outside the build under IP" are
mutually exclusive in current Gradle.

## Why nobody else has fixed this

Auto-inject + AGP-aware is a rare combination:

- Most plugins are applied through the `plugins {}` DSL in build scripts
  (resolved via `pluginManagement` in settings) — that path is IP-compatible.
  They are never *injected* from outside, so they never hit this.
- The tools that *do* inject from an init script (Develocity / build-scan, IDE
  sync) are pure settings/Gradle plugins that never reference AGP types, so the
  init-classpath route (mechanism 3) works fine for them.

compose-preview is the unusual case that needs **both** init-script injection
**and** AGP class visibility, so it falls in the gap.

## The upstream Gradle change that would fix it

An IP-safe way to contribute to a *project's buildscript* classpath early
enough that an injected plugin lands on the **same** classloader as the
consumer's own buildscript plugins (AGP). Concretely, one of:

- a `beforeProject` / settings hook that can add a `buildscript` classpath
  dependency to a project **before** that project's buildscript classpath is
  resolved (mechanism 2, but made early enough to take effect), or
- a supported way to apply an init-classpath plugin into the **project
  buildscript classloader's** child scope so it can see AGP, rather than running
  on the init classloader (mechanism 3, but classloader-parented correctly).

Until then, IP and the auto-inject workflow are incompatible by construction.

## A partial opening (not yet shipped)

Mechanism 3 *does* work end-to-end for consumers whose targeted modules never
touch AGP — pure Compose Multiplatform / JVM-desktop projects. `AndroidPreviewSupport`
(the only AGP-referencing code) runs solely under
`withPlugin("com.android.application" / "com.android.library")`, so on a
desktop/CMP module those classes are never loaded and the init-classpath route
would stay IP-clean. A future dual-strategy auto-inject (init-classpath for
non-Android targets, manual-apply guidance for Android) could give CMP-desktop
users IP compatibility — tracked as a possible follow-up, not a current
deliverable.
