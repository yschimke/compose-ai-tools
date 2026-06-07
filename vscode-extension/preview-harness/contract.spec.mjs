// Wire-side contract for the bundle UI flow, one Playwright test per
// fixture that declares `expectedPosts` / `forbiddenPosts`. Same drive as
// the snapshot spec (boot the fixture, replay its `actions` through
// locators), but instead of writing a PNG it reads the `postedMessageLog`
// the harness's `vscode-api.js` shim captures and asserts the
// `vscode.postMessage` calls those actions produced.
//
// Closes the regression-prevention strategy started with #1119 (typed
// event bus) + #1122 (smoke tests): a regression where (e.g.) activating
// the Accessibility chip stops posting `setDataExtensionEnabled` for
// `a11y/hierarchy` fails the build — something a visual diff alone
// wouldn't surface.

import { test, expect } from "@playwright/test";
import { listFixtures, loadFixture } from "./_fixtures.mjs";
import { gotoFixture, replayActions, wireDiagnostics } from "./_drive.mjs";
import { findMatchingPost } from "./_contract.mjs";

for (const fixtureName of listFixtures()) {
    const data = loadFixture(fixtureName);
    const expected = data.expectedPosts ?? [];
    const forbidden = data.forbiddenPosts ?? [];

    test(`contract · ${fixtureName}`, async ({ page }) => {
        test.skip(
            expected.length === 0 && forbidden.length === 0,
            "no expectedPosts / forbiddenPosts",
        );
        wireDiagnostics(page, fixtureName);

        await gotoFixture(page, fixtureName, "dark");
        await replayActions(page, data.actions);

        const log = await page.evaluate(
            () => window.__composePreviewHarness.postedMessageLog,
        );

        // Surface the full log on failure — the old script dumped it to
        // stderr; here it rides along as a soft assertion annotation so
        // it shows up in the report next to whichever rule failed.
        for (const exp of expected) {
            expect(
                findMatchingPost(log, exp),
                `missing expected post ${JSON.stringify(exp)}\n` +
                    `recorded log: ${JSON.stringify(log, null, 2)}`,
            ).toBeTruthy();
        }
        for (const forb of forbidden) {
            expect(
                findMatchingPost(log, forb),
                `forbidden post observed for rule ${JSON.stringify(forb)}\n` +
                    `recorded log: ${JSON.stringify(log, null, 2)}`,
            ).toBeFalsy();
        }
    });
}
