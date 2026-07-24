/**
 * Simple lexer for AGSL/SkSL shader source text.
 *
 * Produces a token stream that the transpiler rewrites into GLSL.
 * Whitespace and comments are preserved as tokens so the output
 * retains readable formatting.
 */

export const enum TokenType {
    Identifier,     // variable, type, or keyword name
    Number,         // int or float literal
    Operator,       // + - * / = < > ! & | ^ ~ % ? :
    Punctuation,    // ( ) { } [ ] ; ,
    Dot,            // . (separate so we can detect  X.eval)
    Whitespace,     // spaces, tabs, newlines
    LineComment,    // // ...
    BlockComment,   // /* ... */
    Preprocessor,   // #version, #define etc.
    EOF,
}

export interface Token {
    type: TokenType;
    value: string;
}

const IDENT_START = /[a-zA-Z_]/;
const IDENT_CONT  = /[a-zA-Z0-9_]/;
const DIGIT       = /[0-9]/;
const WS          = /[ \t\r\n]/;
const OP_CHARS    = new Set('+-*/%=<>!&|^~?:');
const PUNCT_CHARS = new Set('(){}[];,');

export function tokenize(source: string): Token[] {
    const tokens: Token[] = [];
    let i = 0;
    const n = source.length;

    while (i < n) {
        const ch = source[i];

        // Whitespace
        if (WS.test(ch)) {
            const start = i;
            while (i < n && WS.test(source[i])) i++;
            tokens.push({ type: TokenType.Whitespace, value: source.slice(start, i) });
            continue;
        }

        // Line comment
        if (ch === '/' && i + 1 < n && source[i + 1] === '/') {
            const start = i;
            while (i < n && source[i] !== '\n') i++;
            tokens.push({ type: TokenType.LineComment, value: source.slice(start, i) });
            continue;
        }

        // Block comment
        if (ch === '/' && i + 1 < n && source[i + 1] === '*') {
            const start = i;
            i += 2;
            while (i < n && !(source[i - 1] === '*' && source[i] === '/')) i++;
            if (i < n) i++; // consume closing /
            tokens.push({ type: TokenType.BlockComment, value: source.slice(start, i) });
            continue;
        }

        // Preprocessor directive
        if (ch === '#') {
            const start = i;
            while (i < n && source[i] !== '\n') i++;
            tokens.push({ type: TokenType.Preprocessor, value: source.slice(start, i) });
            continue;
        }

        // Number literal (int or float, including .5 form and 1e-3 form)
        if (DIGIT.test(ch) || (ch === '.' && i + 1 < n && DIGIT.test(source[i + 1]))) {
            const start = i;
            // Integer part
            while (i < n && DIGIT.test(source[i])) i++;
            // Decimal part
            if (i < n && source[i] === '.') {
                i++;
                while (i < n && DIGIT.test(source[i])) i++;
            }
            // Exponent
            if (i < n && (source[i] === 'e' || source[i] === 'E')) {
                i++;
                if (i < n && (source[i] === '+' || source[i] === '-')) i++;
                while (i < n && DIGIT.test(source[i])) i++;
            }
            tokens.push({ type: TokenType.Number, value: source.slice(start, i) });
            continue;
        }

        // Identifier or keyword
        if (IDENT_START.test(ch)) {
            const start = i;
            while (i < n && IDENT_CONT.test(source[i])) i++;
            tokens.push({ type: TokenType.Identifier, value: source.slice(start, i) });
            continue;
        }

        // Dot (separate token for detecting .eval, .rgb etc.)
        if (ch === '.') {
            tokens.push({ type: TokenType.Dot, value: '.' });
            i++;
            continue;
        }

        // Punctuation
        if (PUNCT_CHARS.has(ch)) {
            tokens.push({ type: TokenType.Punctuation, value: ch });
            i++;
            continue;
        }

        // Operators (may be multi-char: <=, >=, ==, !=, &&, ||, <<, >>)
        if (OP_CHARS.has(ch)) {
            const start = i;
            i++;
            if (i < n && OP_CHARS.has(source[i])) {
                // Two-char operators
                const two = source.slice(start, i + 1);
                if (['<=', '>=', '==', '!=', '&&', '||', '<<', '>>', '+=', '-=',
                     '*=', '/=', '%=', '&=', '|=', '^='].includes(two)) {
                    i++;
                }
            }
            tokens.push({ type: TokenType.Operator, value: source.slice(start, i) });
            continue;
        }

        // Anything else: emit as single-char identifier (shouldn't happen in valid AGSL)
        tokens.push({ type: TokenType.Identifier, value: ch });
        i++;
    }

    tokens.push({ type: TokenType.EOF, value: '' });
    return tokens;
}
