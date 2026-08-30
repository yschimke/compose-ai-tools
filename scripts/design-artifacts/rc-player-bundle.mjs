/**
 * Where the vendored TypeScript Remote Compose player's browser bundle lives.
 *
 * It used to be `third_party/remote-compose-player/dist/bundle.js` in this checkout. The players are
 * published by yschimke/rc-players now, so the bundle is unpacked out of its published zip by the
 * root build's `stageVendoredRcPlayerJs` task and read from there.
 *
 * One resolver rather than the path repeated in six test files: that repetition is exactly what made
 * the move a six-file edit, and the next move should be a one-file edit.
 *
 * `RC_PLAYER_JS_BUNDLE` overrides it — point that at any `bundle.js` to test a player build the
 * staged one is not.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "../..");

export const RC_PLAYER_JS_BUNDLE =
  process.env.RC_PLAYER_JS_BUNDLE ||
  path.join(REPO_ROOT, "build/vendored-rc-player-js/bundle.js");

/**
 * The reason the bundle cannot be used, or `null` when it can.
 *
 * Returned rather than thrown so each suite keeps its own skip-or-fail policy — these tests already
 * self-skip on a missing playwright or an unlaunchable chromium, and this is the same kind of
 * missing prerequisite.
 *
 * `RC_PLAYER_JS_BUNDLE_REQUIRE=1` turns that skip into a hard failure, the same lever
 * `RC_CMP_WASM_REQUIRE` is for the Wasm guards. CI sets it **only when staging actually produced a
 * bundle**, which is what keeps this from rotting: while `remote-compose-player-js-dist` is
 * unpublished the tests skip and say so, and the moment it resolves a skip becomes a failure again
 * rather than staying quietly green.
 */
export function rcPlayerBundleIssue() {
  if (fs.existsSync(RC_PLAYER_JS_BUNDLE)) return null;
  const reason =
    `no player bundle at ${RC_PLAYER_JS_BUNDLE} — run \`./gradlew stageVendoredRcPlayerJs\` ` +
    `to unpack the published one, or set RC_PLAYER_JS_BUNDLE`;
  if (process.env.RC_PLAYER_JS_BUNDLE_REQUIRE === "1") {
    throw new Error(`RC_PLAYER_JS_BUNDLE_REQUIRE is set but ${reason}`);
  }
  return reason;
}
