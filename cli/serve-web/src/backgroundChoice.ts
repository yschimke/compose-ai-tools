// The page-wide "solid stage vs transparent checkerboard" choice, owned in one
// place so every `<cp-bg-toggle>` on the page agrees.
//
// The state lives on `<html>` as `cp-bg-transparent`, not in a module variable,
// because the inline pre-paint script in `ServeWeb.document` has already set
// that class from `?bg=` / `localStorage` before this bundle parses — that is
// what stops the checkerboard flashing on load. Reading the class rather than
// re-deriving from storage keeps this module a *view* of what the page already
// decided, so the two can't disagree.

import { urlState } from "./urlState.js";

export type BackgroundChoice = "on" | "off";

const CLASS = "cp-bg-transparent";
const STORAGE_KEY = "cp-bg";

const listeners = new Set<() => void>();

// What Back falls back to when a history entry carries no `bg`: what THIS load
// resolved to. Re-reading localStorage on pop would return the value a later
// click wrote, so backing out of Transparent would stay transparent.
//
// Snapshotted on the first `wirePopstate()` — i.e. when the first toggle
// connects, which is before any click can have changed the class — rather than
// at module scope, so the value is observable in a test that sets the class
// first. In the browser the two are the same instant.
let initial: BackgroundChoice | null = null;
let popWired = false;

/** `true` when the page is showing the transparency checkerboard. */
export function isTransparent(): boolean {
    return document.documentElement.classList.contains(CLASS);
}

function paint(choice: BackgroundChoice): void {
    document.documentElement.classList.toggle(CLASS, choice === "off");
    for (const notify of listeners) notify();
}

/** Flip the page between the solid stage and the checkerboard. */
export function toggle(): void {
    const choice: BackgroundChoice = isTransparent() ? "on" : "off";
    paint(choice);
    try {
        localStorage.setItem(STORAGE_KEY, choice);
    } catch {
        // Private-mode / blocked storage: the choice just doesn't outlive the page.
    }
    // A discrete choice, so it earns its own history entry — the URL describes
    // the page on screen and the checkerboard view is shareable.
    urlState()?.push({ bg: choice });
}

/** Subscribe to changes; returns an unsubscribe. */
export function subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => void listeners.delete(listener);
}

/**
 * Repaint from the URL on Back/Forward, and snapshot the load's resting choice.
 * Idempotent, and called by the first `<cp-bg-toggle>` to connect rather than at
 * module scope — a page with no toggle has nothing to repaint.
 */
export function wirePopstate(): void {
    if (popWired) return;
    initial ??= isTransparent() ? "off" : "on";
    const state = urlState();
    if (!state) return;
    popWired = true;
    state.onPop(() => {
        const value = state.get("bg");
        paint(value === "on" || value === "off" ? value : (initial ?? "on"));
    });
}

/**
 * Drop every module-scoped subscription and the load snapshot. Tests only — a
 * page never unloads this module, and the singleton is what makes two toggles on
 * one page agree.
 */
export function resetForTest(): void {
    listeners.clear();
    initial = null;
    popWired = false;
}
