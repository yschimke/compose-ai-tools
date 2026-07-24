// StringUtils: float-to-string formatting utilities.
// Port of Java StringUtils.java.

export const GROUPING_NONE = 0;   // e.g. 1234567890.12
export const GROUPING_BY3 = 1;    // e.g. 1,234,567,890.12
export const GROUPING_BY4 = 2;    // e.g. 12,3456,7890.12
export const GROUPING_BY32 = 3;   // e.g. 1,23,45,67,890.12

export const SEPARATOR_COMMA_PERIOD = 0; // e.g. 123,456.12
export const SEPARATOR_PERIOD_COMMA = 1; // e.g. 123.456,12
export const SEPARATOR_SPACE_COMMA = 2;  // e.g. 123 456,12
export const SEPARATOR_UNDER_PERIOD = 3; // e.g. 123_456.12

export const NO_OPTIONS = 0;
export const NEGATIVE_PARENTHESES = 1;
export const ROUNDING = 2;
export const POINT_ZERO = 4;

export const PAD_NONE = 0;
export const PAD_ZERO = 0x30; // '0'
export const PAD_SPACE = 0x20; // ' '

function tochars(
    value: number,
    beforeDecimalPoint: number,
    afterDecimalPoint: number,
    rounding: boolean
): string {
    let isNegative = false;
    if (value < 0) {
        isNegative = true;
        value = -value;
    }

    // Calculate power of 10 to scale the fractional part
    let powerOf10 = 1;
    for (let i = 0; i < afterDecimalPoint; i++) {
        powerOf10 *= 10;
    }

    // Apply rounding
    if (rounding) {
        let roundingFactor = 0.5;
        for (let i = 0; i < afterDecimalPoint; i++) {
            roundingFactor /= 10.0;
        }
        value += roundingFactor;
    }

    // Separate integer and fractional parts
    const integerPart = Math.trunc(value);
    const fractionalPart = value - integerPart;

    // Convert the integer part to characters
    let intLength = 1;
    if (integerPart > 0) {
        let tempInt = integerPart;
        intLength = 0;
        while (tempInt > 0) {
            tempInt = Math.trunc(tempInt / 10);
            intLength++;
        }
    }
    const actualBefore = Math.min(beforeDecimalPoint, intLength);

    const integerChars: string[] = new Array(actualBefore);
    let tempInt = integerPart;
    for (let i = actualBefore - 1; i >= 0; i--) {
        integerChars[i] = String.fromCharCode(0x30 + (tempInt % 10));
        tempInt = Math.trunc(tempInt / 10);
    }

    // Convert the fractional part to characters, after scaling
    let tempFrac = Math.trunc(fractionalPart * powerOf10);

    let fracLength = 0;
    if (afterDecimalPoint > 0) {
        let temp = tempFrac;
        if (temp === 0) {
            fracLength = 1;
        } else {
            while (temp > 0 && temp % 10 === 0) {
                temp = Math.trunc(temp / 10);
            }
            if (temp > 0) {
                let t = temp;
                while (t > 0) {
                    t = Math.trunc(t / 10);
                    fracLength++;
                }
            } else {
                fracLength = 0;
            }
        }
    }

    const actualAfter = Math.min(afterDecimalPoint, fracLength);

    const fractionalChars: string[] = new Array(actualAfter);
    tempFrac = Math.trunc(fractionalPart * powerOf10);
    for (let i = actualAfter - 1; i >= 0; i--) {
        fractionalChars[i] = String.fromCharCode(0x30 + (tempFrac % 10));
        tempFrac = Math.trunc(tempFrac / 10);
    }

    // Combine parts
    let result = '';
    if (isNegative) result += '-';
    result += integerChars.join('');
    result += '.';
    result += fractionalChars.join('');
    return result;
}

/**
 * Legacy float-to-string: padding before/after decimal, no grouping/separator.
 */
export function floatToStringLegacy(
    value: number,
    beforeDecimalPoint: number,
    afterDecimalPoint: number,
    pre: number,  // char code, 0 = no pad
    post: number  // char code, 0 = no pad
): string {
    let isNeg = value < 0;
    if (isNeg) value = -value;

    const integerPart = Math.trunc(value);
    let fractionalPart = value % 1;

    // Convert integer part to string and pad
    let integerPartString = String(integerPart);
    const iLen = integerPartString.length;
    if (iLen < beforeDecimalPoint) {
        if (pre !== 0) {
            const pad = String.fromCharCode(pre).repeat(beforeDecimalPoint - iLen);
            integerPartString = pad + integerPartString;
        }
    } else if (iLen > beforeDecimalPoint) {
        integerPartString = integerPartString.substring(iLen - beforeDecimalPoint);
    }

    if (afterDecimalPoint === 0) {
        return (isNeg ? '-' : '') + integerPartString;
    }

    // Convert fractional part
    for (let i = 0; i < afterDecimalPoint; i++) {
        fractionalPart *= 10;
    }
    fractionalPart = Math.round(fractionalPart);
    for (let i = 0; i < afterDecimalPoint; i++) {
        fractionalPart *= 0.1;
    }

    // Match Java: Float.toString(fractionalPart) then substring from index 2
    let fact = fractionalPart.toString();
    // Java Float.toString for values like 0.12 gives "0.12" — we skip "0."
    const dotIdx = fact.indexOf('.');
    if (dotIdx >= 0) {
        fact = fact.substring(dotIdx + 1, Math.min(fact.length, dotIdx + 1 + afterDecimalPoint));
    } else {
        fact = '0';
    }

    // Trim trailing zeros
    let trim = fact.length;
    for (let i = fact.length - 1; i >= 0; i--) {
        if (fact[i] !== '0') break;
        trim--;
    }
    if (trim !== fact.length) {
        fact = fact.substring(0, trim);
    }

    // Pad after with post character
    if (post !== 0 && fact.length < afterDecimalPoint) {
        fact = fact + String.fromCharCode(post).repeat(afterDecimalPoint - fact.length);
    }

    return (isNeg ? '-' : '') + integerPartString + '.' + fact;
}

/**
 * Full float-to-string with grouping, separators, and options.
 */
export function floatToStringFull(
    value: number,
    beforeDecimalPoint: number,
    afterDecimalPoint: number,
    pre: number,   // char code, 0 = no pad
    post: number,  // char code, 0 = no pad
    separator: number,
    grouping: number,
    options: number
): string {
    let groupSep = ',';
    let decSep = '.';
    switch (separator) {
        case SEPARATOR_PERIOD_COMMA:
            groupSep = '.'; decSep = ','; break;
        case SEPARATOR_SPACE_COMMA:
            groupSep = ' '; decSep = ','; break;
        case SEPARATOR_UNDER_PERIOD:
            groupSep = '_'; decSep = '.'; break;
        // default is SEPARATOR_COMMA_PERIOD: groupSep=',', decSep='.'
    }

    const useParenthesesForNeg = (options & NEGATIVE_PARENTHESES) !== 0;
    const rounding = (options & ROUNDING) !== 0;
    const isNeg = value < 0;
    if (isNeg) value = -value;

    const chars = tochars(value, beforeDecimalPoint, afterDecimalPoint, rounding);
    let fractionalPart = value % 1;

    // Extract integer part string from tochars result
    const dotPos = chars.indexOf('.');
    let integerPartString = chars.substring(chars.startsWith('-') ? 1 : 0, dotPos);

    // Apply grouping
    if (grouping !== GROUPING_NONE) {
        const len = integerPartString.length;
        switch (grouping) {
            case GROUPING_BY3:
                for (let i = len - 3; i > 0; i -= 3) {
                    integerPartString = integerPartString.substring(0, i)
                        + groupSep + integerPartString.substring(i);
                }
                break;
            case GROUPING_BY4:
                for (let i = len - 4; i > 0; i -= 4) {
                    integerPartString = integerPartString.substring(0, i)
                        + groupSep + integerPartString.substring(i);
                }
                break;
            case GROUPING_BY32:
                for (let i = len - 3; i > 0; i -= 2) {
                    integerPartString = integerPartString.substring(0, i)
                        + groupSep + integerPartString.substring(i);
                }
                break;
        }
    }

    // Pad integer part
    const iLen = integerPartString.length;
    if (iLen < beforeDecimalPoint) {
        if (pre !== 0) {
            const pad = String.fromCharCode(pre).repeat(beforeDecimalPoint - iLen);
            integerPartString = pad + integerPartString;
        }
    } else if (iLen > beforeDecimalPoint) {
        integerPartString = integerPartString.substring(iLen - beforeDecimalPoint);
    }

    if (afterDecimalPoint === 0) {
        if (!isNeg) return integerPartString;
        if (useParenthesesForNeg) return '(' + integerPartString + ')';
        return '-' + integerPartString;
    }

    // Convert fractional part
    for (let i = 0; i < afterDecimalPoint; i++) {
        fractionalPart *= 10;
    }
    fractionalPart = Math.round(fractionalPart);
    for (let i = 0; i < afterDecimalPoint; i++) {
        fractionalPart *= 0.1;
    }

    let fact = fractionalPart.toString();
    const factDotIdx = fact.indexOf('.');
    if (factDotIdx >= 0) {
        fact = fact.substring(factDotIdx + 1, Math.min(fact.length, factDotIdx + 1 + afterDecimalPoint));
    } else {
        fact = '0';
    }

    // Trim trailing zeros (note: Java uses i > 0, not i >= 0)
    let trim = fact.length;
    for (let i = fact.length - 1; i > 0; i--) {
        if (fact[i] !== '0') break;
        trim--;
    }
    if (trim !== fact.length) {
        fact = fact.substring(0, trim);
    }

    // Pad after
    if (post !== 0 && fact.length < afterDecimalPoint) {
        fact = fact + String.fromCharCode(post).repeat(afterDecimalPoint - fact.length);
    }

    if (!isNeg) return integerPartString + decSep + fact;
    if (useParenthesesForNeg) return '(' + integerPartString + decSep + fact + ')';
    return '-' + integerPartString + decSep + fact;
}
