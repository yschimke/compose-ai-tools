package ee.schimke.composeai.preview

/**
 * Declares the `PreviewWrapperProvider` to wrap a preview with, named by fully-qualified class
 * name.
 *
 * A project-side companion to androidx's `@PreviewWrapper(SomeWrapper::class)`. The upstream
 * annotation is `@Target(AnnotationTarget.FUNCTION)` only, so it can't be hoisted onto a
 * multi-preview meta-annotation — you'd have to repeat `@PreviewWrapper(...)` on every `@Preview`
 * function. This annotation additionally targets [AnnotationTarget.ANNOTATION_CLASS], so a
 * multi-preview annotation can carry the wrapper **once** and every function tagged with it
 * inherits the wrap (each `@Preview` expansion gets `wrapperClassName` set). On a plain function it
 * behaves exactly like `@PreviewWrapper`.
 *
 * Discovery reads this by FQN (mirroring [ScrollingPreview] / [AnimatedPreview]) and threads
 * [wrapperClassName] onto every produced preview's `wrapperClassName`; the renderer loads the class
 * reflectively and invokes its `Wrap(content)`, the same path a direct `@PreviewWrapper` takes. A
 * `@PreviewWrapper` declared directly on the function wins over one inherited from a multi-preview
 * annotation.
 *
 * The wrapper is named by string FQN rather than `KClass` so this annotation stays in the
 * androidx-free `preview-annotations` artifact (it has no compile dependency on
 * `ui-tooling-preview` and its `PreviewWrapperProvider`). Consumers that want to use the annotation
 * depend on `ee.schimke.composeai:preview-annotations`.
 *
 * A wrapper is also the supported way to install **app-owned** preview environment. The renderer
 * provides standard Compose locals, but it cannot discover arbitrary locals declared by an app. For
 * example, an app whose screens require its own `LocalSharedTransitionScope` and
 * `LocalAnimatedVisibilityScope` can create real AndroidX scopes compositionally and map them to
 * those locals once:
 * ```
 * class SharedTransitionPreviewWrapper : PreviewWrapperProvider {
 *   @Composable
 *   override fun Wrap(content: @Composable () -> Unit) {
 *     SharedTransitionLayout {
 *       AnimatedVisibility(visible = true) {
 *         CompositionLocalProvider(
 *           LocalSharedTransitionScope provides this@SharedTransitionLayout,
 *           LocalAnimatedVisibilityScope provides this,
 *           content = content,
 *         )
 *       }
 *     }
 *   }
 * }
 *
 * @Preview
 * @PreviewWrapperClass("com.example.app.SharedTransitionPreviewWrapper")
 * @Composable
 * fun HomePreview() = HomeScreen()
 * ```
 *
 * Keep a required app local non-null with a descriptive throwing default; the app host and preview
 * wrapper should both provide a valid value. Make it nullable only when the composable deliberately
 * supports running without that environment—for example, by omitting shared-element modifiers.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@MustBeDocumented
annotation class PreviewWrapperClass(
  /**
   * Fully-qualified name of the `PreviewWrapperProvider` implementation to wrap the preview with,
   * e.g. `"com.example.app.FontPreviewWrapper"`. The class must have a public no-arg constructor
   * and be on the render classpath; an unresolvable name degrades to rendering without a wrapper.
   */
  val wrapperClassName: String
)
