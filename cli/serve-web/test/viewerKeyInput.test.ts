// Turning a keystroke into what the live stream sends.
//
// The bug worth remembering: sending only the keycode made the arrows and Backspace work — they are
// physical keys with nothing to insert — while nothing could ever be TYPED. A partial
// implementation that happens to cover the keys you test by hand is the failure mode here.

import assert from "node:assert/strict";
import {
    androidKeycode,
    keyMessage,
    typedText,
} from "../src/viewer/keyInput.js";

describe("androidKeycode", () => {
    it("maps the letters off KEYCODE_A, whatever the shift state", () => {
        assert.equal(androidKeycode("a"), "29");
        assert.equal(androidKeycode("A"), "29", "a keycode is a physical key");
        assert.equal(androidKeycode("z"), "54");
    });

    it("maps the digits off KEYCODE_0", () => {
        assert.equal(androidKeycode("0"), "7");
        assert.equal(androidKeycode("9"), "16");
    });

    it("maps the named keys the stream understands", () => {
        assert.deepEqual(
            ["Enter", "Backspace", "Tab", "Escape", "Delete", " "].map(
                androidKeycode,
            ),
            ["66", "67", "61", "111", "112", "62"],
        );
        assert.deepEqual(
            ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].map(
                androidKeycode,
            ),
            ["19", "20", "21", "22"],
        );
    });

    it("answers null rather than guessing a code it does not have", () => {
        // A wrong keycode presses a DIFFERENT key on the device, which is worse than pressing none.
        for (const key of ["Shift", "F3", "Home", "£", "é", "Meta"]) {
            assert.equal(androidKeycode(key), null, key);
        }
    });
});

describe("typedText", () => {
    it("passes a printable character through", () => {
        assert.equal(typedText({ key: "a" }), "a");
        assert.equal(
            typedText({ key: "A" }),
            "A",
            "case is the TEXT's business",
        );
        assert.equal(typedText({ key: " " }), " ");
        assert.equal(typedText({ key: "£" }), "£");
    });

    it("refuses a named key, which inserts nothing", () => {
        for (const key of ["Enter", "ArrowLeft", "Shift", "Backspace"]) {
            assert.equal(typedText({ key }), null, key);
        }
    });

    it("refuses a modified key, which is a shortcut rather than typing", () => {
        assert.equal(typedText({ key: "a", ctrlKey: true }), null);
        assert.equal(typedText({ key: "a", metaKey: true }), null);
    });

    it("refuses a control character", () => {
        // Written as escapes: these used to be literal control characters in the source, which is
        // unreadable and one careless edit away from silently becoming a space.
        assert.equal(typedText({ key: "\u0000" }), null, "NUL");
        assert.equal(typedText({ key: "\u001b" }), null, "ESC");
        assert.equal(typedText({ key: "\u007f" }), null, "DEL");
        // …but SPACE is printable, and is the boundary these sit just below.
        assert.equal(typedText({ key: "\u0020" }), " ");
    });

    it("counts an astral character as ONE, not as its two UTF-16 units", () => {
        // Measured in code points. A length check would see 2 and drop the emoji, so a device
        // keyboard could never send one.
        assert.equal(typedText({ key: "😀" }), "😀");
    });
});

describe("keyMessage", () => {
    it("carries BOTH halves for a printable letter", () => {
        // The whole point. A keycode names the physical key; the text is what gets inserted, and
        // sending only the first is why typing never worked.
        assert.deepEqual(keyMessage({ key: "a" }), { code: "29", text: "a" });
    });

    it("carries only a code for a key that inserts nothing", () => {
        assert.deepEqual(keyMessage({ key: "Enter" }), {
            code: "66",
            text: null,
        });
        assert.deepEqual(keyMessage({ key: "ArrowLeft" }), {
            code: "21",
            text: null,
        });
    });

    it("carries only text for a printable key with no Android code", () => {
        assert.deepEqual(keyMessage({ key: "£" }), { code: null, text: "£" });
    });

    it("sends nothing at all when there is neither", () => {
        assert.equal(keyMessage({ key: "Shift" }), null);
        assert.equal(keyMessage({ key: "F3" }), null);
        assert.equal(
            keyMessage({ key: "a", ctrlKey: true })?.text,
            null,
            "a shortcut still presses the physical key, but types nothing",
        );
    });
});
