// Whether a typography field is worth marking, and why.
//
// Every field on both sides of the comparison table asks the same two questions — is this different
// from the OTHER SIDE, and is it different from this token's own DEFAULT — and the answers mean
// different things. Cross-side difference is a fidelity finding: the render does not match the
// design. Cross-default difference is a local override: this usage was deliberately changed from the
// style it names, on both sides equally, and is not a defect at all.
//
// In the DOM version these two questions were restated three or four times each, per field, with
// slightly different operands. That is the shape a bug hides in: right on three fields and wrong on
// the fourth, on a page where "wrong" means a highlight that is simply absent.

import {
    typographyComparableValue,
    type Field,
    type TypographyGroup,
} from "./typography.js";

export interface FieldState {
    /** Differs from the other side — a fidelity finding. */
    changed: boolean;
    /** Differs from this token's most-used group — a deliberate local override. */
    override: boolean;
    /** The tooltip an override carries, or `""`. */
    title: string;
}

/**
 * Whether two groups differ in one field.
 *
 * `size` also consults `lineHeight`, because the two are printed as one `16sp/24sp` cell. Comparing
 * only the size would leave a changed line height unmarked inside a cell that visibly shows it.
 */
function differs(
    group: TypographyGroup,
    other: TypographyGroup | undefined,
    field: Field,
): boolean {
    if (!other) return false;
    if (
        typographyComparableValue(group.spec, field) !==
        typographyComparableValue(other.spec, field)
    )
        return true;
    return (
        field === "size" &&
        typographyComparableValue(group.spec, "lineHeight") !==
            typographyComparableValue(other.spec, "lineHeight")
    );
}

/**
 * The two answers for one field.
 *
 * `baseline === group` is not an override: the most-used group for a token IS the default, and
 * marking it as changed from itself would put an override badge on the very thing every other usage
 * is being measured against.
 */
export function fieldState(
    group: TypographyGroup,
    other: TypographyGroup | undefined,
    baseline: TypographyGroup | undefined,
    field: Field,
): FieldState {
    const changed = differs(group, other, field);
    const override = Boolean(
        baseline && baseline !== group && differs(group, baseline, field),
    );
    return {
        changed,
        override,
        title: override ? `Changed from ${group.spec.token} default` : "",
    };
}

/**
 * Whether an optional field appears at all.
 *
 * Tracking, style and axes are shown when they carry a non-default value — or when they have
 * CHANGED, however ordinary the value is. A field that reverted to its default is exactly the case
 * worth seeing, and a rule that only showed non-default values would hide it.
 */
export function showsOptional(
    value: string | undefined,
    defaultValue: string,
    state: FieldState,
): boolean {
    if (!value) return false;
    return value !== defaultValue || state.changed || state.override;
}
