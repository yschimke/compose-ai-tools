// Which lane the viewer is on, what it is called, and what each chip reports about it.
//
// The viewer has one primary chip that does two jobs — it NAMES the renderer on the stage ("Java",
// "JS", "Figma spec", "Live") and its status dot says whether that render is interactive — plus a
// spec chip and a source chip that report their own lanes. Every route out of a lane has to
// un-press every chip: the Live chip, a combo pick, an SVG swap, the spec chip, Back/Forward. Miss
// one route and a chip stays lit over a lane that is no longer showing, which is a control claiming
// something untrue rather than a control that fails to work.
//
// DOM-free: `viewer.js` reads the toggles and passes booleans.

/** Which lanes are currently painting, in the order they take precedence. */
export interface LaneFlags {
    rcWasm: boolean;
    rc: boolean;
    wasm: boolean;
    spec: boolean;
    /** The daemon stream. */
    live: boolean;
}

/**
 * Every lane that paints a RUNNING composition rather than a finished image.
 *
 * The daemon stream, the in-browser Wasm app, and both Remote Compose player lanes, which replay
 * the document client-side. This is what the status dot reports, so picking "JS" from the combo
 * lights the same indicator clicking into Live does — they are the same claim.
 */
export function anyInteractive(lanes: LaneFlags): boolean {
    return lanes.live || lanes.wasm || lanes.rc || lanes.rcWasm;
}

/** Whether there is any live lane to enter at all. */
export function liveTransportAvailable(options: {
    daemon: boolean;
    wasm: boolean;
}): boolean {
    return options.daemon || options.wasm;
}

/**
 * The live lane the primary chip enters: the daemon stream when this session offers it, else the
 * in-browser Wasm app, else nothing.
 */
export function bestLiveMode(options: {
    daemon: boolean;
    wasm: boolean;
}): "live" | "wasm" | null {
    if (options.daemon) return "live";
    return options.wasm ? "wasm" : null;
}

export interface LanePick {
    /** The Remote Compose backend the server would use with no pick. */
    defaultBackend: string;
    /** The backend the visitor picked, if they have. */
    pickedBackend: string;
    picked: boolean;
}

/** Whether a finished server-side RC lane must name its backend on `/render`. */
export function backendRequiresRenderParam(backend: string): boolean {
    // The server's absent-player default is the Java view player. Browser players do not use the
    // snapshot route, while both embedded players need an explicit wire id.
    return backend === "cmp-android" || backend === "cmp-jvm";
}

/** The server-side player parameter represented by a pick, or nothing for a browser lane. */
export function serverPlayerParam(
    backend: string,
    picked: boolean,
): string | null {
    if (!picked) return null;
    return backend === "java" ||
        backend === "cmp-android" ||
        backend === "cmp-jvm"
        ? backend
        : null;
}

/** Restore the server-rendered default when returning from a browser-only player lane. */
export function restoreStaticPlayer(pick: LanePick): LanePick {
    if (pick.picked) return pick;
    const retained = serverPlayerParam(pick.pickedBackend, true);
    if (retained) {
        return {
            defaultBackend: pick.defaultBackend,
            pickedBackend: retained,
            // Java only needs an explicit parameter when it overrides a non-Java default. Embedded
            // defaults always need one because the server's absent-player fallback is Java.
            picked:
                retained !== pick.defaultBackend ||
                backendRequiresRenderParam(pick.defaultBackend),
        };
    }
    return {
        defaultBackend: pick.defaultBackend,
        pickedBackend: pick.defaultBackend,
        picked: backendRequiresRenderParam(pick.defaultBackend),
    };
}

/**
 * The lane the picker is — or would be — sitting on, in the combo's own value space.
 *
 * A daemon stream is NOT one of the offered renderers; it is the live form of whichever one is
 * picked. So it deliberately falls through to the static player lane the toggle will return to,
 * which is what makes the chip's label survive entering and leaving Live.
 */
export function currentLaneValue(lanes: LaneFlags, pick: LanePick): string {
    if (lanes.rcWasm) return "rc:cmp-wasm";
    if (lanes.rc) return "rc:js";
    if (lanes.wasm) return "wasm";
    if (lanes.spec) return "spec";
    if (pick.defaultBackend)
        return `rc:${pick.picked ? pick.pickedBackend : pick.defaultBackend}`;
    return "png";
}

/**
 * What the primary chip calls the current lane.
 *
 * "Live" while the daemon stream is up — that lane IS the live form of whichever renderer is
 * picked, and the picked one is a click away again. Otherwise the matching combo option's own
 * label, so the chip and the combo can never name a lane two different things.
 *
 * On the spec lane there is no matching option, and that is deliberate: the spec chip beside this
 * one is lit and already names it, and two adjacent chips both reading "Figma" would be two
 * controls arguing about the same fact. So this one keeps naming the render lane, which is exactly
 * where clicking it goes back to.
 */
export function laneLabelText(options: {
    live: boolean;
    /** The combo's options as value→label, or `null` on a preview with no combo. */
    laneOptions: ReadonlyMap<string, string> | null;
    wanted: string;
    defaultLabel: string;
}): string {
    if (options.live) return "Live";
    if (!options.laneOptions) return options.defaultLabel;
    return options.laneOptions.get(options.wanted) || options.defaultLabel;
}

export interface ChipState {
    pressed: boolean;
    disabled: boolean;
}

/**
 * A lane chip's state.
 *
 * Enabled when there is a lane to enter — OR when its lane is already on the stage, which is the
 * only way back OUT of it. A chip that disabled itself on entry would strand the visitor there.
 */
export function laneChip(options: {
    onLane: boolean;
    available: boolean;
}): ChipState {
    return {
        pressed: options.onLane,
        disabled: !options.available && !options.onLane,
    };
}
