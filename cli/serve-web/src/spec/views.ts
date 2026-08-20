// Which of the spec lane's four views is showing, and who got to decide.
//
// Three sources compete for that: the address bar (a shared link, or a Back into one), a chip that
// enters the lane asking for a particular entry view, and the visitor clicking a button. They are
// not equal, and the rule that orders them is the whole reason this is a module rather than three
// booleans in a closure:
//
//   * An explicit choice LATCHES and never clears. Once someone has said which view they want, a
//     later chip request must not silently move them off it.
//   * A named view in the URL *is* an explicit choice — it is either what the visitor picked before
//     sharing or reloading, or where Back is returning them to.
//   * The chip's request is therefore only ever a default, and it is spent the moment it is used.
//
// Get any of those backwards and the bug is a view that quietly changes under the reader, which is
// exactly the kind of thing a screenshot cannot show and a table can.

export const VIEWS = ["spec", "diff", "triptych", "slider"] as const;
export type SpecView = (typeof VIEWS)[number];

/**
 * The lane's original behaviour: the imported reference alone, on the stage viewer.js set up.
 *
 * Not the default any more, but still a view apart from the other three — it is the only one that
 * paints nothing of its own, so it is the one `SpecCompare.apply()` keeps the comparison surfaces
 * and the score away from. That is a fact about what `spec` DRAWS, not about which view the lane
 * opens on, and the two were the same constant until [DEFAULT_VIEW] moved.
 */
export const PLAIN_VIEW: SpecView = "spec";

/**
 * What the lane opens on, and therefore what `?specView=` may leave unsaid.
 *
 * Triptych rather than `spec` (#4376): entering the design-spec lane is someone asking how the
 * render and the reference compare, and the plain reference answers that only by asking the eye to
 * hold one frame while looking at the other. Spec / diff / render side by side answers it on
 * arrival, and the Spec button is one click away for anyone who wants the reference alone.
 */
export const DEFAULT_VIEW: SpecView = "triptych";

export interface ViewChoice {
    view: SpecView;
    /** Whether the visitor or a URL has spoken. Latches true; never clears. */
    chosen: boolean;
    /** A chip's requested entry view, pending until the next `open`. */
    preferred: SpecView | "";
}

export const INITIAL: ViewChoice = {
    view: DEFAULT_VIEW,
    chosen: false,
    preferred: "",
};

export function isView(value: string | null | undefined): value is SpecView {
    return VIEWS.includes(value as SpecView);
}

/** Anything unrecognised falls back rather than addressing a view that does not exist. */
export function normaliseView(value: string | null | undefined): SpecView {
    return isView(value) ? value : DEFAULT_VIEW;
}

/** The visitor pressed a view button. Explicit, so it latches. */
export function choose(state: ViewChoice, next: string): ViewChoice {
    return { ...state, view: normaliseView(next), chosen: true };
}

/**
 * Restore from the address bar — initial load, and Back/Forward.
 *
 * A NAMED view latches, for the reason above. An absent or unrecognised one does not: arriving at a
 * URL that says nothing about the view is not the visitor choosing the default, so a chip request
 * on that page is still free to open on its own view.
 */
export function hydrate(state: ViewChoice, next: string | null): ViewChoice {
    return isView(next)
        ? { ...state, view: next, chosen: true }
        : { ...state, view: DEFAULT_VIEW };
}

/**
 * A chip asks for an entry view. Ignored once anyone has chosen.
 *
 * No caller requests one today: the design-spec chip used to ask for `diff` and stopped when
 * [DEFAULT_VIEW] became Triptych (#4376), which is the same answer one rung further out. The
 * precedence it encodes is what makes adding the next such entry point safe, so it stays — the
 * ordering above is a rule about the lane, not about one chip.
 */
export function prefer(state: ViewChoice, next: string): ViewChoice {
    return state.chosen ? state : { ...state, preferred: normaliseView(next) };
}

/**
 * The lane has been entered: spend a pending chip preference, if it is still allowed to apply.
 *
 * Spent rather than kept, so a chip that asked once does not keep pulling later entries back to its
 * view after the visitor has moved on.
 */
export function onOpen(state: ViewChoice): ViewChoice {
    if (!state.preferred || state.chosen) return { ...state, preferred: "" };
    return { ...state, view: state.preferred, preferred: "" };
}

/** What `?specView=` should carry — empty for the default, which needs no parameter. */
export function viewParam(view: SpecView): string {
    return view === DEFAULT_VIEW ? "" : view;
}
