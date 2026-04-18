# Material 3 Expressive Design for Wear OS

Material 3 Expressive (M3) is the latest evolution of Google's design language for Wear OS, designed to meet user demand for modern, relevant, and distinct experiences. It moves beyond "clean and boring" designs to create interfaces that connect with users on an emotional level.

## Core Design Principles
Material 3 Expressive is built on four pillars:
- **Modern**: Reflecting current design trends and user expectations.
- **Relevant**: Tailored to individual user preferences and context.
- **Distinct**: Providing a unique and recognizable identity.
- **Expressive**: Utilizing motion, color, and shapes to evoke specific emotions.

## Development Setup: Modules & Packages

To build for Wear OS using Material 3, use the following **androidx.wear.compose** libraries. **Important**: Always use the `material3` variants and avoid the legacy `material` (M2) modules.

### Recommended Dependencies (v1.6.0+)
| Feature | Artifact / Module |
| :--- | :--- |
| **Material 3** | `androidx.wear.compose:compose-material3` |
| **Foundation** | `androidx.wear.compose:compose-foundation` |
| **Navigation** | `androidx.wear.compose:compose-navigation` |
| **Tooling** | `androidx.wear.compose:compose-ui-tooling` |

### Primary Packages
- **Design System**: `androidx.wear.compose.material3` (e.g., `Text`, `Button`, `MaterialTheme`)
- **Layout & Lists**: `androidx.wear.compose.foundation` and `androidx.wear.compose.foundation.lazy`
- **Navigation**: `androidx.wear.compose.navigation` (e.g., `SwipeDismissableNavHost`)
- **Previews**: `androidx.wear.compose.ui.tooling.preview`

> [!IMPORTANT]
> Do **not** include a dependency on `androidx.compose.material:material` or `androidx.wear.compose:compose-material`. Material 3 for Wear OS (`compose-material3`) is designed as a standalone replacement.

> Use https://github.com/androidx/androidx for searching AndroidX code. 

### App-Specific UX Principles
- **Focused**: Help users complete critical tasks within seconds.
- **Shallow and Linear**: Avoid hierarchies deeper than two levels; display content and navigation inline when possible.
- **Vertical-First**: Optimize for vertical layouts to allow single-direction traversal.

## App Surfaces & Best Practices
An app is a primary surface on Wear OS, offering richer interactivity than tiles or complications.

### Key Guidelines
- **Time Display**: Always show the time (overlay) at the top for consistency.
- **Primary Actions**: Elevate the most important actions to the top of the interface.
- **Inline Entry Points**: Settings and preferences should be accessible inline with clear iconography.
- **Responsive Margins**: Use **percentage margins** so the layout adapts to the display's curve.
- **Breakpoints**: Consider a breakpoint at **225dp** to introduce more content or improve glanceability on larger screens.
- **Scroll Indicators**: Only display scrollbars on screens that actually scroll to maintain correct user expectations.

---

## Technical Implementation & Code Choices

### 1. Screen Architecture
Use a hierarchical scaffolding approach to manage system UI (TimeText, Scroll Indicator) and screen-specific content:
- **`AppScaffold`**: The root container for the entire application.
- **`ScreenScaffold`**: Used for individual screens. Manages the `scrollState` and provides a slot for `EdgeButton`.
- **`SwipeDismissableNavHost`**: Essential for the "back" gesture (swipe-to-dismiss).

ScreenScaffold has a contentPadding lambda parameter that *MUST* be used in the content.

### 2. Scrolling & Lists
The preferred list component in M3 Expressive is **`TransformingLazyColumn`** (which succeeds `ScalingLazyColumn`).
- **State Management**: Use `rememberTransformingLazyColumnState()`.
- **Visual Effects**: Apply `rememberTransformationSpec()` and `SurfaceTransformation` to items to enable expressive shape-morphing and scaling as they scroll.
- **Dynamic Height**: Use `Modifier.transformedHeight` within items to ensure they scale correctly.
- **Vertical Content Spacing**: Avoid using Horologist for list padding. Instead, use **`Modifier.minimumVerticalContentPadding()`** on individual items within a `TransformingLazyColumn`.
    - **Package**: `androidx.wear.compose.foundation.lazy`
    - **Scope**: Only available within `TransformingLazyColumnItemScope`. No explicit import is needed if called inside the scope.
    - **Usage**: Pair this with specific defaults if needed, like `CardDefaults.minimumVerticalListContentPadding` or `ListHeaderDefaults.minimumTopListContentPadding`.

- **Auto-scaling Text**: For labels that may overflow in fixed containers (like `EdgeButton`), use **`BasicText`** with the `autoSize` parameter.
    - **Package**: `androidx.compose.foundation.text.BasicText`
    - **Auto-size Package**: `androidx.compose.foundation.text.TextAutoSize`
    - **Implementation**: Uses `TextAutoSize.StepBased()` to automatically adjust font size to fit constraints.

### 3. Key Components
- **`EdgeButton`**: A hallmark M3 pattern for round devices. It hugs the bottom edge of the screen and is the ideal place for the primary CTA.
- **`ButtonGroup`**: For side-by-side actions (e.g., Two iconic `FilledIconButton`s).
- **`TitleCard` / `AppCard`**: Use these for content groupings. Custom colors can be applied via `AppCardDefaults`.
- **`ListHeader`**: Standardized header for list sections.
- **`AlertDialog`**: Use the Material 3 version with `AlertDialogDefaults.ConfirmButton` and `DismissButton`.

### 4. Styles & Tokens
- **Color**: Target `MaterialTheme.colorScheme` (e.g., `primary`, `secondary`, `surfaceContainerLow`).
- **Shapes**: Use `IconButtonDefaults.animatedShapes()` for expressive, morphing interaction feedback.
- **Typography**: Utilize variable font axes (Roboto Flex) for dynamic weight/width changes during motion.

### 5. System UI & Time Display
- **`TimeText`**: Most screens (excluding dialogs) should display the system time. This is typically handled by placing `TimeText` within the **`AppScaffold`**.
- **`AppScaffold` Consistency**: Because `AppScaffold` is often defined at the root of the app, individual screens inherit the system UI.
- **Preview Recommendation**: When creating `@Preview` functions for individual screens, wrap the content in an **`AppScaffold`** (or a custom theme wrapper that includes it) to ensure the time text is visible and correctly positioned in the generated preview image.

### 6. Round-face clipping in rendered PNGs

Any preview whose `device` resolves as round — `id:wearos_small_round`,
`id:wearos_large_round`, custom `spec:…isRound=true` or `spec:…shape=Round`
— is auto-clipped to a transparent inscribed circle before the PNG is
written. Corners outside the circle are alpha-zero, so the preview matches
what a real round watch face shows.

Implications when designing previews:

- Don't waste effort styling the corners of a round preview — they'll be
  clipped away.
- Place primary content within the inscribed circle. The corners of the
  preview canvas are *not* a usable design area.
- Stitched `@ScrollingPreview(mode = LONG)` round captures use a capsule
  mask (top half-circle + rectangle + bottom half-circle), not per-slice
  circles, so vertical content in the middle remains visible.
- Square Wear devices (`id:wearos_square`) are rendered without the clip.

### 7. Stable previews (avoid noisy diffs)

Wear previews fan out over many devices, font scales, and (for scrolling
content) slice indices. A single source of nondeterminism multiplies into
hundreds of pixel-different PNGs. Pin these things:

- **Pin the clock for `TimeText`.** The default `TimeSource` returns the
  wall clock, which changes every minute. Provide a fixed source in
  previews:

  ```kotlin
  private object FixedTimeSource : TimeSource {
      @Composable override fun currentTime(): String = "10:10"
  }

  AppScaffold(timeText = { TimeText(timeSource = FixedTimeSource) }) { … }
  ```

- **Suppress the scroll indicator during stitched captures via
  `LocalScrollCaptureInProgress`.** `ScreenScaffold` draws a transient scroll
  indicator that fades in/out as the list scrolls; on stitched
  `@ScrollingPreview(mode = LONG)` captures it lands at arbitrary opacities
  per slice and dominates the diff. The renderer mirrors Compose's
  long-screenshot signal — it provides
  `androidx.compose.ui.platform.LocalScrollCaptureInProgress = true` only
  for `LONG`-mode previews, `false` everywhere else (including
  `@ScrollingPreview(mode = END)`, regular `@Preview`, and the running app).
  END mode is a single frame at the scroll-to-end position, so showing the
  indicator there matches what a real app renders. Read the local inside
  the `scrollIndicator` slot so production behaviour is unchanged but
  stitched captures stay clean:

  ```kotlin
  ScreenScaffold(
      scrollState = listState,
      scrollIndicator = {
          if (!LocalScrollCaptureInProgress.current) {
              ScrollIndicator(listState)
          }
      },
      edgeButton = { … },
  ) { contentPadding -> … }
  ```

  Requires compose-ui ≥ 1.7 on the consumer's classpath (when
  `LocalScrollCaptureInProgress` shipped). The same screen composable runs
  unchanged in production — the local is always `false` outside the
  renderer's `LONG`-capture path.

- **Compose previews from the screen, not the app root.** When a preview
  needs different content (or a different `TimeSource`) than the production
  root, don't call `WearApp()` from the preview — that locks you into the
  production `AppScaffold`. Instead, wrap the screen-level composable in
  the preview itself, so the preview owns the scaffolds and can swap in a
  `FixedTimeSource`:

  ```kotlin
  @WearPreviewLargeRound
  @ScrollingPreview(mode = ScrollMode.LONG)
  @Composable
  fun MyScreenLongPreview() {
      MaterialTheme {
          AppScaffold(timeText = { TimeText(timeSource = FixedTimeSource) }) {
              MyScreen()  // the screen owns its ScreenScaffold
          }
      }
  }
  ```

  Production `WearApp` does the same wrapping with the real `TimeSource`.
  Splitting screen-from-scaffold this way also lets the `LocalScroll-
  CaptureInProgress` read above stay where it belongs (inside the screen's
  `ScreenScaffold`).

- **Reduce motion for `TransformingLazyColumn` scroll captures.**
  `@ScrollingPreview(mode = LONG, reduceMotion = true)` (default) wraps the
  body in `LocalReduceMotion provides ReduceMotion(true)` so item
  shape-morphing and scaling don't vary slice-to-slice. Without it, each
  stitched slice picks up a different transform state and the resulting
  tall PNG looks ragged.

- **`EdgeButton` revealed only at end-of-list.** `ScreenScaffold` reveals
  the `EdgeButton` only when the list is pinned to its bottom — so for a
  `@ScrollingPreview(mode = LONG)` the button shows up in the final slice
  only. That's intended behaviour, not a regression; the top-state
  `@Preview` of the same composable will not include it.

### 8. Accessibility on Wear

Round faces hide content behind the bezel curve, so a11y findings differ
from a phone's. Specifically watch for:

- **Tap-target size** — `androidx.wear.compose.material3.Button` defaults
  meet the spec, but custom `Box`/`IconButton` content can fall below the
  48dp ATF threshold. Enable `composePreview { accessibilityChecks {
  enabled = true } }` and run `compose-preview a11y` to surface these.
- **Content descriptions** — Wear UI relies more heavily on icons; an
  unlabelled `Button { Icon(...) }` triggers ATF errors.
- **Edge-clipped touch targets** — round-face renders crop content at the
  capsule mask; an apparently-large element may have a much smaller
  effective hit region after clipping. Read both the regular PNG and the
  `a11yAnnotatedPath` overlay to spot this.

The annotated overlay for round Wear devices uses a stacked legend layout
(screenshot on top, legend below) so the badges remain readable on small
displays.

---

## Tiers of Expression
Designers can gauge their app's implementation across three levels:
1. **Foundational**: Core Material 3 elements applied consistently.
2. **Excellent**: Enhanced use of color, typography, and motion for a premium feel.
3. **Transformational**: Fully immersive and unique experiences that push the boundaries of the design system.

## Research-Backed Benefits
Material 3 Expressive is Google's most researched design update:
- **Aesthetics**: Users perceived expressive designs as up to 170% more aesthetically pleasing.
- **UX Preference**: Expressive variants saw an approximately 100% increase in user preference over baseline designs.
- **Accessibility**: Employs "accessible-by-default" styles, often exceeding minimum standards for tap target size and color contrast.
- **Emotional Connection**: Fine-tuned to evoke positive vibes—"playful," "energetic," and "friendly."
