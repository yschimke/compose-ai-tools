/**
 * Transpile AGSL/SkSL fragment shader source to WebGL2 GLSL (ES 3.0).
 *
 * Handles the common patterns used in RemoteCompose shaders:
 *   - Type renaming:  half4->vec4, half3->vec3, half->float,
 *                     float2->vec2, float3->vec3, float4->vec4,
 *                     int2->ivec2, int3->ivec3, int4->ivec4
 *   - Entry point:    half4 main(float2 p) -> void main() with
 *                     fragColor output and Y-flipped gl_FragCoord
 *   - Builtins:       sk_FragCoord -> gl_FragCoord.xy
 *   - Child shaders:  uniform shader X -> uniform sampler2D X;
 *                     X.eval(coord) -> texture(X, coord / u_resolution)
 *   - return expr;    in main -> fragColor = expr; return;
 */

import { tokenize, Token, TokenType } from './AgslTokenizer';

export interface UniformInfo {
    name: string;
    glslType: string;   // 'float', 'vec2', 'vec3', 'vec4', 'int', etc.
}

export interface TranspileResult {
    glsl: string;
    uniforms: UniformInfo[];
    childShaders: string[];     // names of `uniform shader X` declarations
}

// AGSL type -> GLSL type
const TYPE_MAP: Record<string, string> = {
    'half4':  'vec4',
    'half3':  'vec3',
    'half2':  'vec2',
    'half':   'float',
    'float2': 'vec2',
    'float3': 'vec3',
    'float4': 'vec4',
    'int2':   'ivec2',
    'int3':   'ivec3',
    'int4':   'ivec4',
};

// Map AGSL types to their GLSL equivalents for uniform reporting
const UNIFORM_TYPE_MAP: Record<string, string> = {
    ...TYPE_MAP,
    'float': 'float',
    'int':   'int',
    'vec2':  'vec2',
    'vec3':  'vec3',
    'vec4':  'vec4',
    'ivec2': 'ivec2',
    'ivec3': 'ivec3',
    'ivec4': 'ivec4',
    'sampler2D': 'sampler2D',
};

function isSkip(t: Token): boolean {
    return t.type === TokenType.Whitespace ||
           t.type === TokenType.LineComment ||
           t.type === TokenType.BlockComment;
}

/** Advance past whitespace/comments, return index of next meaningful token. */
function skipWs(tokens: Token[], from: number): number {
    while (from < tokens.length && isSkip(tokens[from])) from++;
    return from;
}

export function transpileAgslToGlsl(agslSource: string): TranspileResult {
    const tokens = tokenize(agslSource);
    const uniforms: UniformInfo[] = [];
    const childShaders: string[] = [];

    // --- Pass 1: Gather info and transform tokens ---
    // We'll build output token by token, applying rewrites.

    const out: string[] = [];
    let i = 0;
    const n = tokens.length;

    // Track whether we're inside the main function body and brace depth.
    let inMain = false;
    let mainBraceDepth = 0;
    let mainParamName = 'p';   // the coordinate parameter name
    let hasIResolution = false;
    let needsResolutionUniform = false;

    // Pre-scan for iResolution uniform
    for (let j = 0; j < n; j++) {
        if (tokens[j].type === TokenType.Identifier && tokens[j].value === 'iResolution') {
            // Check if it's a uniform declaration
            let k = j - 1;
            while (k >= 0 && isSkip(tokens[k])) k--;
            if (k >= 0) {
                let k2 = k - 1;
                while (k2 >= 0 && isSkip(tokens[k2])) k2--;
                if (k2 >= 0 && tokens[k2].value === 'uniform') {
                    hasIResolution = true;
                }
            }
        }
    }

    while (i < n) {
        const tok = tokens[i];

        // --- uniform shader X → uniform sampler2D X ---
        if (tok.type === TokenType.Identifier && tok.value === 'uniform') {
            const j1 = skipWs(tokens, i + 1);
            if (j1 < n && tokens[j1].type === TokenType.Identifier && tokens[j1].value === 'shader') {
                const j2 = skipWs(tokens, j1 + 1);
                if (j2 < n && tokens[j2].type === TokenType.Identifier) {
                    const shaderName = tokens[j2].value;
                    childShaders.push(shaderName);
                    needsResolutionUniform = true;
                    // Emit: uniform sampler2D <name>
                    out.push('uniform sampler2D');
                    // Preserve whitespace between shader and name
                    for (let k = j1 + 1; k <= j2; k++) {
                        if (tokens[k].type === TokenType.Whitespace) out.push(tokens[k].value);
                    }
                    out.push(shaderName);
                    i = j2 + 1;
                    continue;
                }
            }
        }

        // --- uniform <type> <name> — record for metadata ---
        if (tok.type === TokenType.Identifier && tok.value === 'uniform') {
            const j1 = skipWs(tokens, i + 1);
            if (j1 < n && tokens[j1].type === TokenType.Identifier) {
                const typeName = tokens[j1].value;
                if (typeName !== 'shader') {
                    const j2 = skipWs(tokens, j1 + 1);
                    if (j2 < n && tokens[j2].type === TokenType.Identifier) {
                        const glslType = UNIFORM_TYPE_MAP[typeName] || TYPE_MAP[typeName] || typeName;
                        uniforms.push({ name: tokens[j2].value, glslType });
                    }
                }
            }
            // Fall through to normal output (type will be remapped below)
        }

        // --- main function signature ---
        // Detect: <retType> main ( <paramType> <paramName> )
        if (tok.type === TokenType.Identifier && tok.value === 'main' && !inMain) {
            // Look back for return type (skip ws)
            let retIdx = i - 1;
            while (retIdx >= 0 && isSkip(tokens[retIdx])) retIdx--;
            // Look forward for (
            const parenIdx = skipWs(tokens, i + 1);
            if (parenIdx < n && tokens[parenIdx].type === TokenType.Punctuation && tokens[parenIdx].value === '(') {
                // Get param type and name
                const paramTypeIdx = skipWs(tokens, parenIdx + 1);
                const paramNameIdx = skipWs(tokens, paramTypeIdx + 1);
                const closeIdx = skipWs(tokens, paramNameIdx + 1);

                if (paramNameIdx < n && tokens[paramNameIdx].type === TokenType.Identifier) {
                    mainParamName = tokens[paramNameIdx].value;
                }

                // Find the opening brace
                let braceIdx = closeIdx;
                if (braceIdx < n && tokens[braceIdx].value === ')') {
                    braceIdx = skipWs(tokens, braceIdx + 1);
                }

                // Remove the return type that was already emitted
                // We need to go back and remove it from out[]
                // Find and remove the last non-ws token in out (the return type)
                let removeFrom = out.length - 1;
                while (removeFrom >= 0 && (out[removeFrom].trim() === '')) removeFrom--;
                if (removeFrom >= 0) out.splice(removeFrom, 1);

                // Emit the new signature
                out.push('void main()');

                // Skip to opening brace
                i = braceIdx;
                if (i < n && tokens[i].type === TokenType.Punctuation && tokens[i].value === '{') {
                    out.push(' {\n');
                    // Scale from render pixels to document coordinates.
                    // gl_FragCoord is in [0, u_resolution], we need [0, u_doc_size].
                    out.push(`  vec2 ${mainParamName} = vec2(gl_FragCoord.x, u_resolution.y - gl_FragCoord.y) * u_doc_size / u_resolution;\n`);
                    inMain = true;
                    mainBraceDepth = 1;
                    i++;
                }
                continue;
            }
        }

        // --- Track brace depth inside main ---
        if (inMain && tok.type === TokenType.Punctuation) {
            if (tok.value === '{') mainBraceDepth++;
            if (tok.value === '}') {
                mainBraceDepth--;
                if (mainBraceDepth === 0) inMain = false;
            }
        }

        // --- return expr; in main → fragColor = expr; return; ---
        if (inMain && tok.type === TokenType.Identifier && tok.value === 'return') {
            // Collect everything until the semicolon
            const exprParts: string[] = [];
            i++;
            let depth = 0;
            while (i < n) {
                const t = tokens[i];
                if (t.type === TokenType.Punctuation && t.value === ';' && depth === 0) {
                    break;
                }
                if (t.type === TokenType.Punctuation && (t.value === '(' || t.value === '[')) depth++;
                if (t.type === TokenType.Punctuation && (t.value === ')' || t.value === ']')) depth--;

                // Apply type mapping and other rewrites to the expression tokens
                exprParts.push(rewriteToken(tokens, i, childShaders));
                i++;
            }
            const expr = exprParts.join('');
            out.push(`fragColor = ${expr}; return`);
            // The ; will be emitted by normal flow
            continue;
        }

        // --- X.eval(coord) → texture(X, (coord) / u_resolution) ---
        if (tok.type === TokenType.Identifier && childShaders.includes(tok.value)) {
            const dotIdx = skipWs(tokens, i + 1);
            if (dotIdx < n && tokens[dotIdx].type === TokenType.Dot) {
                const evalIdx = skipWs(tokens, dotIdx + 1);
                if (evalIdx < n && tokens[evalIdx].type === TokenType.Identifier && tokens[evalIdx].value === 'eval') {
                    const parenIdx = skipWs(tokens, evalIdx + 1);
                    if (parenIdx < n && tokens[parenIdx].value === '(') {
                        // Collect the argument (may have nested parens)
                        let depth = 1;
                        let argStart = parenIdx + 1;
                        let j = argStart;
                        while (j < n && depth > 0) {
                            if (tokens[j].value === '(') depth++;
                            if (tokens[j].value === ')') depth--;
                            if (depth > 0) j++;
                        }
                        // tokens[argStart..j-1] is the argument
                        const argParts: string[] = [];
                        for (let k = argStart; k < j; k++) {
                            argParts.push(rewriteToken(tokens, k, childShaders));
                        }
                        const arg = argParts.join('');
                        out.push(`texture(${tok.value}, (${arg}) / u_resolution)`);
                        i = j + 1; // skip past closing )
                        continue;
                    }
                }
            }
        }

        // --- Default: rewrite token ---
        out.push(rewriteToken(tokens, i, childShaders));
        i++;
    }

    // --- Build final GLSL ---
    let glsl = '#version 300 es\nprecision highp float;\n';
    glsl += 'uniform vec2 u_resolution;\n';   // actual WebGL render size (pixels)
    glsl += 'uniform vec2 u_doc_size;\n';     // document rect size (for coord scaling)
    glsl += 'out vec4 fragColor;\n';
    glsl += out.join('');

    return { glsl, uniforms, childShaders };
}

// Tokens that indicate the following number should stay as int
const INT_CONTEXT_KEYWORDS = new Set([
    'int', 'ivec2', 'ivec3', 'ivec4',
]);

/**
 * Check if an integer literal at `idx` should remain an int.
 * Returns true for: array indices [N], for-loop int vars, int constructor args.
 */
function isIntContext(tokens: Token[], idx: number): boolean {
    // Look backward past whitespace for context clues
    let j = idx - 1;
    while (j >= 0 && isSkip(tokens[j])) j--;
    if (j >= 0) {
        // After '[' → array index, keep as int
        if (tokens[j].value === '[') return true;
        // After 'int'/'ivec' type keyword → keep as int
        if (tokens[j].type === TokenType.Identifier && INT_CONTEXT_KEYWORDS.has(tokens[j].value)) return true;
        // After '=' preceded by 'int varname' → int assignment
        if (tokens[j].value === '=') {
            let k = j - 1;
            while (k >= 0 && isSkip(tokens[k])) k--;  // skip to varname
            if (k >= 0) {
                let k2 = k - 1;
                while (k2 >= 0 && isSkip(tokens[k2])) k2--;  // skip to type
                if (k2 >= 0 && INT_CONTEXT_KEYWORDS.has(tokens[k2].value)) return true;
            }
        }
        // After '<' or '>' or '<=' etc. in for-loop condition with int var
        // Heuristic: if we're comparing against an int variable, keep int
        if (tokens[j].value === '<' || tokens[j].value === '>' ||
            tokens[j].value === '<=' || tokens[j].value === '>=' ||
            tokens[j].value === '==' || tokens[j].value === '!=') {
            let k = j - 1;
            while (k >= 0 && isSkip(tokens[k])) k--;
            // If left side is 'i', 'j', etc. (loop var), keep int
            // Simple heuristic: if it's a single-char identifier, likely a loop var
            if (k >= 0 && tokens[k].type === TokenType.Identifier && tokens[k].value.length <= 2) return true;
        }
        // After '++' or '--' keep context
        if (tokens[j].value === '++' || tokens[j].value === '--') return true;
        // After '(' preceded by int type constructor
        if (tokens[j].value === '(' || tokens[j].value === ',') {
            // Walk back to find function/constructor name
            let k = j - 1;
            while (k >= 0 && isSkip(tokens[k])) k--;
            if (k >= 0 && tokens[k].type === TokenType.Identifier && INT_CONTEXT_KEYWORDS.has(tokens[k].value)) return true;
        }
    }

    // Look forward: if followed by ')' preceded by 'int(' cast, stay int
    // Not critical — the above handles most cases

    return false;
}

/** Rewrite a single token (type names, builtins, int→float promotion). */
function rewriteToken(tokens: Token[], idx: number, _childShaders: string[]): string {
    const tok = tokens[idx];

    if (tok.type === TokenType.Identifier) {
        // Type mapping
        if (TYPE_MAP[tok.value]) return TYPE_MAP[tok.value];
        // sk_FragCoord
        if (tok.value === 'sk_FragCoord') return 'gl_FragCoord.xy';
    }

    // GLSL ES 3.0 has no implicit int→float conversion.  Promote bare
    // integer literals to float (append .0) unless they're in an int context.
    if (tok.type === TokenType.Number) {
        const v = tok.value;
        const isInt = /^\d+$/.test(v);  // pure digits, no '.', no 'e'
        if (isInt && !isIntContext(tokens, idx)) {
            return v + '.0';
        }
    }

    return tok.value;
}
