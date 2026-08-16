// The vendored Remote Compose player bundle, as the page sees it.
//
// Script-injected on demand from `/rc-player/bundle.js` — never bundled here — so this file is
// types only and emits nothing. Two surfaces drive the same player and both need the shape: the
// viewer's Remote Compose canvas lane and the compare wall's player lanes. It lives here rather
// than beside either of them because it belongs to neither.
//
// The optional members are optional on purpose. The bundle is vendored and its version moves
// independently of this repo, so the viewer feature-tests before calling — `if (player.repaint)
// player.repaint()`. Declaring those required would let the compiler bless a call the running
// bundle may not answer.

/** The player's own context, through which named values reach a loaded document. */
export interface RemoteContext {
    setNamedColorOverride?(name: string, argb: number): void;
    setNamedStringOverride?(name: string, value: string): void;
    setNamedFloatOverride?(name: string, value: number): void;
    setNamedIntegerOverride?(name: string, value: number): void;
}

export interface RcPlayer {
    setTheme(theme: string): void;
    loadFromArrayBuffer(buffer: ArrayBuffer): Promise<unknown>;
    fontsReady(): Promise<unknown>;
    repaint?(): void;
    getRemoteContext?(): RemoteContext | null;
}

declare global {
    interface Window {
        /** The Remote Compose player bundle, script-injected on demand. */
        RC?: {
            RcdPlayer: new (canvas: HTMLCanvasElement) => RcPlayer;
        };
        cpRcFonts?: { ready(): Promise<unknown> };
    }
}
