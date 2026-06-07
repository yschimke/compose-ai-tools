// Subset-matching against the recorded `postMessage` log, extracted from
// the old `contract.mjs` so the spec can stay focused on the Playwright
// drive + `expect` assertions.

/**
 * Subset-equality: every key in [expected] must match the corresponding
 * key in [actual]. Extra keys on [actual] are allowed so fixtures don't
 * have to know about every field the webview attaches (e.g. `previewId`
 * defaults). Recurses into nested objects, compares arrays via
 * `JSON.stringify`, and supports a `{ $includes: x }` marker that matches
 * when the corresponding actual value is an array containing `x` — used by
 * `setDataExtensionEnabled` assertions to peer inside the batched `kinds`
 * array without forcing fixtures to spell out every co-subscribed kind.
 */
export function matchesSubset(expected, actual) {
    if (typeof expected !== "object" || expected === null) {
        return expected === actual;
    }
    if (Array.isArray(expected)) {
        return JSON.stringify(expected) === JSON.stringify(actual);
    }
    if (Object.prototype.hasOwnProperty.call(expected, "$includes")) {
        return Array.isArray(actual) && actual.includes(expected.$includes);
    }
    if (typeof actual !== "object" || actual === null) return false;
    for (const k of Object.keys(expected)) {
        if (!matchesSubset(expected[k], actual[k])) return false;
    }
    return true;
}

export function findMatchingPost(log, expected) {
    return log.find((post) => {
        if (matchesSubset(expected, post)) return true;
        // `setDataExtensionEnabled` switched from one message per kind
        // (`kind: "x"`) to a batched form (`kinds: ["x", "y"]`) so chip
        // activation lands as a single `data/subscribe` sequence rather
        // than racing the daemon's mode-lock-on-first-subscribe. Fixtures
        // still assert one kind at a time; treat `kind: "x"` as a match
        // for any logged post whose `kinds` array contains "x".
        if (
            post?.command === "setDataExtensionEnabled" &&
            Array.isArray(post.kinds) &&
            typeof expected?.kind === "string"
        ) {
            return post.kinds.some((k) => {
                const synthetic = { ...post, kind: k };
                delete synthetic.kinds;
                return matchesSubset(expected, synthetic);
            });
        }
        return false;
    });
}
