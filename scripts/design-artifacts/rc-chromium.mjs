/**
 * rc-chromium.mjs — the headless Chromium launch arguments for the lanes that play a Remote Compose
 * document in a browser (`rc-compare.mjs`, and the tests that measure the same thing).
 *
 * They live in one place because a flag that only some of them carry is a flag that silently
 * changes what a measurement means. In particular `--disable-frame-rate-limit`, which is not a speed knob:
 * without it Chromium paces `requestAnimationFrame` at roughly **1 fps** for a page whose CSS
 * viewport is only a few dozen pixels tall, and the CMP/Wasm player waits three frames before it
 * reports readiness. Measured on the `remote-m3` catalog (Gradient widget document, one context,
 * warm player, `deviceScaleFactor` 2.625):
 *
 *   viewport   without the flag   with the flag
 *   216×76     6155 ms            1976 ms
 *   216×120    2044 ms            1986 ms
 *
 * The 76 dp-tall widget previews are the whole gap: their frames landed ~950 ms apart instead of
 * ~70 ms, which is what pushed a *warm* render past the 5,000 ms warm first-frame budget in
 * https://github.com/yschimke/compose-ai-tools/issues/3445. The flag lifts the compositor's frame
 * cap; it changes scheduling only, not what is rasterized, and every lane still screenshots after
 * the player's own readiness marker.
 */
export const CHROMIUM_LAUNCH_ARGS = Object.freeze([
  // Skia's WebGL path in headless containers has no GPU; SwiftShader is the software fallback.
  "--enable-unsafe-swiftshader",
  "--no-sandbox",
  // See above: without this a short viewport is paced at ~1 fps and inflates first-frame timings.
  "--disable-frame-rate-limit",
]);
