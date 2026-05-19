package ee.schimke.composeai.preview

/**
 * Marks a function `(android.content.Context) -> android.app.Notification` as a renderable preview
 * of an Android notification. Discovered by the compose-preview Gradle plugin by FQN — consumers
 * that want to use the annotation in their own code depend on
 * `ee.schimke.composeai:preview-annotations`.
 *
 * The render path inflates the notification's expanded `bigContentView` (or the standard
 * `contentView` when no `setStyle(...)` was applied) via [`Notification.Builder.recoverBuilder`]
 * and draws the resulting `RemoteViews` to a PNG under `renders/`. This is the AOSP visual, not the
 * Pixel / OEM-skinned visual — OEM chrome (rounded corners, brand tint) is drawn by SystemUI
 * on-device and isn't reproducible inside Robolectric. See issue #1249 for the design and the
 * variants matrix planned in follow-up PRs.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class NotificationPreview
