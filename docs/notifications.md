# Notification previews

Render Android `Notification` builders to PNG through the same Robolectric
pipeline `@Preview` composables use. One authoring step in your module, one
rendered shade-surface per environment variant under `renders/`.

For the broader pipeline picture (discovery, rendering, caching), see
[HOW_IT_WORKS.md](HOW_IT_WORKS.md). For the design discussion that produced
this feature, see [issue #1249](https://github.com/yschimke/compose-ai-tools/issues/1249).

## What this renders

The renderer reflects your factory's `Notification`, asks the platform for its
`bigContentView` (falling back to `contentView` for collapsed-only
notifications), and inflates the resulting `RemoteViews` into a PNG.

That is the **AOSP visual** — the shade surface a stock device would draw.
SystemUI chrome from a specific OEM (Pixel rounded-corner card, Samsung tint
ramp, etc.) is applied on-device by SystemUI and is **not reproducible** in
Robolectric; the rendered PNG will look correct as Android-stock and slightly
unfamiliar next to a Pixel screenshot.

## Two ways to author a previewable notification

Pick the one that matches your module's existing dep graph.

### `@NotificationPreview` — no Compose dependency required

Use this when the module containing the factory does not (or cannot) take a
dependency on Compose UI tooling — Bazel modules without Compose, headless
test fixtures, library modules that publish notification surfaces.

The annotation lives in `:preview-annotations` and is discovered by
fully-qualified name; no Compose reflection is involved in pickup. The
function takes a `Context` and returns an `android.app.Notification`.

```kotlin
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import ee.schimke.composeai.preview.NotificationPreview

@NotificationPreview
fun simpleNotificationPreview(context: Context): Notification =
    NotificationCompat.Builder(context, "sample")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("New message")
        .setContentText("Hello from compose-preview")
        .build()
```

Working example: [samples/android/src/main/kotlin/com/example/sampleandroid/NotificationPreviews.kt](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationPreviews.kt).

The renderer side that picks these up is
[`NotificationPreviewComposable`](../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/NotificationPreviewRenderer.kt)
in `:renderer-android`.

### `@Preview` + `NotificationContent` — Studio preview pane + multi-preview

Use this when the module already depends on Compose UI tooling and you want
the factory to show up in Android Studio's preview pane and pick up Compose's
existing multi-preview fan-out. `NotificationContent` is the composable
helper that inflates a `(Context) -> Notification` factory inside the
composition.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.NotificationCompat
import ee.schimke.composeai.notification.NotificationContent

@Preview(name = "Default")
@Composable
fun MyNotifPreview() = NotificationContent { ctx ->
    NotificationCompat.Builder(ctx, "channel")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Hi")
        .build()
}
```

`NotificationContent` ships in the `:notification-preview-runtime` artifact.
The reference implementation that artifact is promoted from lives at
[samples/android/src/main/kotlin/com/example/sampleandroid/NotificationContent.kt](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationContent.kt) —
the sample copy stays in-tree as a working end-to-end reference, but consumer
code should depend on the published artifact.

### Which one should I use?

| Concern | `@NotificationPreview` | `@Preview` + `NotificationContent` |
|---------|------------------------|------------------------------------|
| Compose UI dep required | no | yes |
| Visible in Studio preview pane | no | yes |
| Multi-preview fan-out (`uiMode`, `locale`, `fontScale`, …) | one annotation = one PNG | stack `@Preview` for fan-out |
| Structured-fields JSON sidecar | yes | yes (when `previewId` is wired through the runtime) |
| Function signature | `(Context) -> Notification` | `@Composable () -> Unit` wrapping `NotificationContent { … }` |

You can mix both in one module — they share the same renderer-side inflation
path, so the rendered PNGs are visually identical for the same notification.

## Variants matrix

Both authoring forms render one factory into one PNG by default. To fan one
factory across several environment configurations (theme, locale, font scale)
use Compose tooling's existing multi-preview mechanism: stacked `@Preview`
annotations grouped under a meta-annotation.

The sample defines [`@NotificationVariants`](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationVariants.kt):

```kotlin
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Arabic", locale = "ar")
@Preview(name = "German", locale = "de")
@Preview(name = "Japanese", locale = "ja")
@Preview(name = "Large font", fontScale = 1.5f)
annotation class NotificationVariants
```

Apply it to a `NotificationContent`-based composable and the discovery pass
emits one entry per `@Preview`, each rendered with the matching
`RuntimeEnvironment.setQualifiers` / `setFontScale` configuration. Six
annotations → six PNGs:

```kotlin
@NotificationVariants
@Composable
fun BigTextVariantsPreview() {
    NotificationContent { ctx ->
        NotificationCompat.Builder(ctx, "variants")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(ctx.getString(R.string.notif_variant_title))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(ctx.getString(R.string.notif_variant_big_text))
            )
            .build()
    }
}
```

Working example: [samples/android/src/main/kotlin/com/example/sampleandroid/NotificationVariantPreviews.kt](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationVariantPreviews.kt).

The variants axes are deliberately the ones `@Preview` already owns
(`uiMode`, `locale`, `widthDp`, `fontScale`). Style / content axes — BigText
vs Messaging, long-title vs no-icon — belong in separate factory functions,
the same way `ButtonDefault` and `ButtonDisabled` are separate `@Preview`s
in component galleries.

`@NotificationPreview` does not participate in this fan-out (it's
single-shot by design — that's what "no Compose dep" buys you). Use the
composable-helper path when you need a matrix.

## Supported styles

Each entry below points at a working `NotificationCompat` example.

| Style | When to use | Example |
|-------|-------------|---------|
| `BigTextStyle` | Long-form body that exceeds the collapsed two-line cap (release notes, articles, single long message). | [`bigTextNotificationPreview`](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationPreviews.kt) |
| `MessagingStyle` | Conversation surfaces — Signal, WhatsApp, Discord. Each `Message` renders with its `Person`'s display name; `setConversationTitle` is the header. | [`MessagingStylePreview`](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationStyleGallery.kt) |
| `InboxStyle` | "You have N unread" digests — Gmail, Outlook. Up to ~7 short rows under a single title. | [`InboxStylePreview`](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationStyleGallery.kt) |
| `BigPictureStyle` | The body is itself an image — camera, photo share, weather. Reserves a wide row for the bitmap above the title and text. | [`BigPictureStylePreview`](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationStyleGallery.kt) |
| Actions (no style) | Reply / dismiss / archive button rows beneath the body. Renders regardless of `setStyle`. | [`ActionsPreview`](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationStyleGallery.kt) |

The full gallery lives in [samples/android/src/main/kotlin/com/example/sampleandroid/NotificationStyleGallery.kt](../samples/android/src/main/kotlin/com/example/sampleandroid/NotificationStyleGallery.kt)
and runs end-to-end via `./gradlew :samples:android:composePreviewRenderAll`.

## Localisation

The `locale` axis of a multi-preview drives `RuntimeEnvironment.setQualifiers`,
which means resource resolution picks up `values-<lang>/` directories
identically to a running app. To get *translated content* (not just a
layout-direction flip on `ar`), put your notification strings in a localised
resource bundle:

```
samples/android/src/main/res/
  values/notification_strings.xml       # default
  values-ar/notification_strings.xml    # Arabic
  values-de/notification_strings.xml    # German
  values-ja/notification_strings.xml    # Japanese
```

Reference each string via `ctx.getString(R.string.notif_variant_title)` from
your factory and the active `locale` qualifier picks the matching bundle.
Sample bundles:
[values/notification_strings.xml](../samples/android/src/main/res/values/notification_strings.xml),
[values-ar/notification_strings.xml](../samples/android/src/main/res/values-ar/notification_strings.xml),
[values-de/notification_strings.xml](../samples/android/src/main/res/values-de/notification_strings.xml),
[values-ja/notification_strings.xml](../samples/android/src/main/res/values-ja/notification_strings.xml).

The manifest's `android:label` is itself a string reference, so it localises
through the same mechanism — see
[samples/android/src/main/AndroidManifest.xml](../samples/android/src/main/AndroidManifest.xml)
pointing at `@string/app_name`, which is overridden in each `values-<lang>/`
bundle.

`ar` additionally flips layout direction to RTL; the inflated `RemoteViews`
tree honours `View.LAYOUT_DIRECTION_LOCALE` the same way it would on-device,
so titles, body, and action rows all mirror correctly.

## Structured-fields JSON sidecar

Alongside each rendered PNG, the renderer writes a per-preview JSON sidecar
capturing the notification's *fields*. Lets agents / CI / tests assert on
channel, style, actions, EXTRA_* without pixel-diffing the rendered image.

### Where it lands

For a render output dir `<outputDir>/renders/<id>.png`, the sidecar goes to:

```
<outputDir>/data/notifications/<id>.notification.json
```

The directory layout mirrors the project's data-product convention: PNGs
under `renders/`, structured data under `data/<kind>/`.

### Schema

`compose-preview-notification/v1`. Hand-rolled JSON, shallow and stable.
Captured fields:

- `previewId` — the manifest's preview id, matched to the PNG filename stem.
- `channelId` — from `Notification.channelId` (API 26+).
- `category` — `Notification.category` (`CATEGORY_MESSAGE`, `CATEGORY_ALARM`, …).
- `group` — `Notification.group`.
- `ongoing` — `FLAG_ONGOING_EVENT` set.
- `autoCancel` — `FLAG_AUTO_CANCEL` set.
- `color` — accent color int (omitted when zero).
- `smallIcon` — `{ "resourceId": …, "resourceName": "package:drawable/name" }`.
- `extras.title` — `EXTRA_TITLE`.
- `extras.text` — `EXTRA_TEXT`.
- `extras.bigText` — `EXTRA_BIG_TEXT` (BigTextStyle).
- `extras.subText` — `EXTRA_SUB_TEXT`.
- `extras.template` — `EXTRA_TEMPLATE` (the style class name as a string).
- `actions[]` — `{ "title": …, "iconResId": … }` per `Notification.Action`.
- `messages[]` — `{ "text": …, "sender": …, "timestamp": … }` per
  MessagingStyle message (sender resolved from `sender_person` or the legacy
  `sender` extras key).

Implementation: [renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/NotificationSidecar.kt](../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/NotificationSidecar.kt).

### Coverage

Emitted today for every `@NotificationPreview`-discovered entry. The
composable-helper path (`@Preview` + `NotificationContent`) emits one too
when the runtime passes the preview id through to `NotificationPreviewComposable`
— shipped as part of the `:notification-preview-runtime` artifact.

Sidecar writes are best-effort. A failure here prints to stderr but does not
fail the PNG render — the goal is to keep structured-field capture from
derailing the visual pipeline on a per-preview edge case.

## Baseline branch

Each push to `main` renders the notification previews and pushes their PNGs
to a long-lived branch:

```
compose-preview/notifications/main
```

Browse it on GitHub like any other branch, or compare a PR's notification
render against it directly. The branch is the sibling of the other rendered-
artifact baselines:

- `compose-preview/main` — UI `@Preview` PNGs.
- `compose-preview/a11y/main` — accessibility findings overlays.
- `compose-preview/resources/main` — Android XML resource captures
  (vector / adaptive-icon).
- `compose-preview/notifications/main` — this one.

PR runs check out the head commit's notification renders and diff against the
matching baseline in `compose-preview/notifications/main`; visual deltas show
up as a PR comment the same way UI preview diffs do.

## Known limitations

- **Single frame.** Notifications that animate over time (`setProgress(...)`
  ticked from a background thread, `setUsesChronometer(true)` counting up)
  are captured at a single instant — the static structure renders fine, but
  there's no animation timeline.
- **AOSP only.** OEM SystemUI chrome (Pixel rounded-corner card,
  Samsung One UI tinting, EMUI accent overlay) is drawn on-device by
  SystemUI, not by the inflated `RemoteViews` tree. The rendered PNG is
  Android-stock; consumers comparing against a Pixel screenshot should
  expect the card chrome to differ.
- **No Wear-specific extender rendering.** `NotificationCompat.WearableExtender`
  pages, custom background bitmap, and "open on phone" action layout are
  Wear-only surfaces drawn by Wear's own notification stream UI, not by the
  phone shade `RemoteViews`. The base notification still renders; the
  Wear-specific bits are dropped.
- **Custom-view-only notifications without a fallback.** If your builder
  calls `setCustomBigContentView(RemoteViews(...))` and provides no standard
  title / text, the standard inflate path used here has nothing to fall back
  to and the render fails. Provide a standard-style fallback or render the
  custom view via the regular `@Preview` path.

## Related

- [Issue #1249](https://github.com/yschimke/compose-ai-tools/issues/1249) — design discussion and rollout plan.
- [`preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt`](../preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt) — annotation source.
- [`renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/NotificationPreviewRenderer.kt`](../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/NotificationPreviewRenderer.kt) — renderer entry point.
- [`renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/NotificationSidecar.kt`](../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/NotificationSidecar.kt) — sidecar schema and writer.
- [`samples/android/src/main/kotlin/com/example/sampleandroid/`](../samples/android/src/main/kotlin/com/example/sampleandroid/) — end-to-end working samples.
