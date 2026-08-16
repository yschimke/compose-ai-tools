// Turning a browser keystroke into what the live stream needs.
//
// TWO things, from one key press, and conflating them is the bug this file records. A keycode names
// a PHYSICAL KEY; the text a keystroke inserts is a character. Sending only the keycode is why the
// caret moved and Backspace deleted while nothing could ever be typed into a focused field — the
// arrows and Backspace are physical keys with nothing to insert, so they worked, and every
// printable key silently did nothing.
//
// Both halves ride every press AND every release. A backend that suppresses the physical key event
// for a focused text field — so the character is not typed twice — has to suppress both, or the
// composition sees an unpaired key-up.

/** Android's `KEYCODE_A`. The letters run consecutively from here, as do the digits from `KEYCODE_0`. */
const KEYCODE_A = 29;
const KEYCODE_0 = 7;

/** The named keys the stream understands, by `KeyboardEvent.key`. */
const NAMED: Record<string, number> = {
    Enter: 66,
    Backspace: 67,
    Tab: 61,
    Escape: 111,
    Delete: 112,
    ArrowUp: 19,
    ArrowDown: 20,
    ArrowLeft: 21,
    ArrowRight: 22,
    " ": 62,
};

/**
 * The Android keycode for a key, or `null` for one the stream has no code for.
 *
 * `null` rather than a fallback: a wrong keycode presses a different key on the device, which is
 * worse than pressing none.
 */
export function androidKeycode(key: string): string | null {
    if (key.length === 1) {
        const c = key.toLowerCase();
        if (c >= "a" && c <= "z")
            return String(KEYCODE_A + (c.charCodeAt(0) - 97));
        if (c >= "0" && c <= "9")
            return String(KEYCODE_0 + (c.charCodeAt(0) - 48));
    }
    const named = NAMED[key];
    return named === undefined ? null : String(named);
}

/** The parts of a `KeyboardEvent` this needs — so the rules are testable without one. */
export interface Keystroke {
    key: string;
    ctrlKey?: boolean;
    metaKey?: boolean;
}

/**
 * The character a keystroke INSERTS, or `null` when it inserts nothing.
 *
 * A modified key is a shortcut, not typing. A `key` of more than one character is a named key
 * (`Shift`, `ArrowLeft`, `F3`), and a control character is not text — measured in code POINTS, so
 * an emoji or any other astral character counts as the single character it is rather than as the
 * two UTF-16 units it is stored in.
 */
export function typedText(event: Keystroke): string | null {
    if (event.ctrlKey || event.metaKey) return null;
    const key = event.key;
    if (typeof key !== "string" || Array.from(key).length !== 1) return null;
    const code = key.charCodeAt(0);
    return code < 0x20 || code === 0x7f ? null : key;
}

export interface KeyMessage {
    code: string | null;
    text: string | null;
}

/**
 * What to send for a keystroke, or `null` when there is nothing to send.
 *
 * Both halves together: a printable letter carries a keycode AND its text, `Enter` carries only a
 * code, and a bare `Shift` carries neither and is dropped.
 */
export function keyMessage(event: Keystroke): KeyMessage | null {
    const code = androidKeycode(event.key);
    const text = typedText(event);
    return code === null && text === null ? null : { code, text };
}
