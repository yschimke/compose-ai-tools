/**
 * Standalone test for AGSL → GLSL transpilation.
 *
 * Run:  npx esbuild src/core/shader/AgslTranspiler.test.ts --bundle --platform=node | node
 * Or:   npx tsx src/core/shader/AgslTranspiler.test.ts
 */

import { transpileAgslToGlsl } from './AgslTranspiler';

let passed = 0;
let failed = 0;

function assert(cond: boolean, msg: string): void {
    if (!cond) {
        console.error(`  FAIL: ${msg}`);
        failed++;
    } else {
        passed++;
    }
}

function assertContains(haystack: string, needle: string, ctx: string): void {
    assert(haystack.includes(needle), `${ctx}: expected to contain "${needle}"`);
}

function assertNotContains(haystack: string, needle: string, ctx: string): void {
    assert(!haystack.includes(needle), `${ctx}: should NOT contain "${needle}"`);
}

// ─── Test 1: Simple gradient (no uniforms, no textures) ─────────────
console.log('Test 1: Simple gradient');
{
    const agsl = `half4 main(float2 p) {
  float2 uv = p / 400.0;
  return half4(uv.x, uv.y, 1.0 - uv.x, 1.0);
}`;
    const r = transpileAgslToGlsl(agsl);

    assertContains(r.glsl, '#version 300 es', 'header');
    assertContains(r.glsl, 'precision highp float', 'precision');
    assertContains(r.glsl, 'out vec4 fragColor', 'fragColor decl');
    assertContains(r.glsl, 'void main()', 'main signature');
    assertContains(r.glsl, 'u_resolution.y - gl_FragCoord.y', 'Y-flip');
    assertNotContains(r.glsl, 'half4', 'no half4 remaining');
    assertNotContains(r.glsl, 'float2', 'no float2 remaining');
    assertContains(r.glsl, 'fragColor =', 'return rewritten');
    assertNotContains(r.glsl, 'return half4', 'original return gone');
    assert(r.uniforms.length === 0, 'no uniforms');
    assert(r.childShaders.length === 0, 'no child shaders');

    console.log('  GLSL output:');
    console.log(r.glsl.split('\n').map(l => '    ' + l).join('\n'));
}

// ─── Test 2: Plasma with iTime and iResolution ──────────────────────
console.log('\nTest 2: Plasma (uniforms)');
{
    const agsl = `uniform float iTime;
uniform float2 iResolution;
half4 main(float2 p) {
  float2 uv = p / iResolution * 6.28318;
  float t = iTime * 0.8;
  float v1 = sin(uv.x + t);
  float v = v1 * 0.25;
  float r = sin(v * 3.14159) * 0.5 + 0.5;
  return half4(r, r, r, 1.0);
}`;
    const r = transpileAgslToGlsl(agsl);

    assertContains(r.glsl, 'uniform float iTime', 'iTime uniform');
    assertContains(r.glsl, 'uniform vec2 iResolution', 'iResolution remapped');
    assertNotContains(r.glsl, 'uniform float2', 'float2 remapped in uniform');
    assertNotContains(r.glsl, 'half4', 'no half4');
    assertContains(r.glsl, 'void main()', 'main');
    assertContains(r.glsl, 'fragColor =', 'return rewritten');
    assert(r.uniforms.length === 2, `2 uniforms (got ${r.uniforms.length})`);
    assert(r.uniforms[0].name === 'iTime', 'uniform 0 name');
    assert(r.uniforms[1].name === 'iResolution', 'uniform 1 name');
    assert(r.uniforms[1].glslType === 'vec2', 'iResolution type remapped');
}

// ─── Test 3: Helper functions + main (flaming text pattern) ─────────
console.log('\nTest 3: Helper functions + main');
{
    const agsl = `uniform float iTime;
uniform float2 iResolution;

float hash(float2 p) {
  return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

half4 main(float2 fc) {
  float2 uv = fc / iResolution;
  float h = hash(fc);
  vec3 col = vec3(h, h, h);
  return half4(col, 1.0);
}`;
    const r = transpileAgslToGlsl(agsl);

    // Helper function should keep float return type and have vec2 param
    assertContains(r.glsl, 'float hash(vec2 p)', 'helper signature');
    assertContains(r.glsl, 'void main()', 'main rewritten');
    assertContains(r.glsl, 'vec2 fc = vec2(gl_FragCoord.x', 'param injection');
    // The return in hash() should NOT be rewritten to fragColor
    assertContains(r.glsl, 'return fract(', 'helper return untouched');
    // The return in main() SHOULD be rewritten
    assertContains(r.glsl, 'fragColor =', 'main return rewritten');
    assertNotContains(r.glsl, 'half4', 'no half4');
    assertNotContains(r.glsl, 'float2', 'no float2');
}

// ─── Test 4: Child shader / texture sampling ────────────────────────
console.log('\nTest 4: Child shader (uniform shader + .eval)');
{
    const agsl = `uniform float iTime;
uniform float2 iResolution;
uniform shader buf;

half4 main(float2 fc) {
  half4 prev = buf.eval(fc);
  vec3 trail = prev.rgb * 0.97;
  float2 center = iResolution * 0.5;
  float2 pos = center + float2(cos(iTime * 2.0), sin(iTime * 2.0)) * 120.0;
  float d = length(fc - pos);
  float dot = smoothstep(12.0, 6.0, d);
  vec3 col = max(trail, vec3(1.0, 0.6, 0.0) * dot);
  return half4(col, 1.0);
}`;
    const r = transpileAgslToGlsl(agsl);

    assertContains(r.glsl, 'uniform sampler2D buf', 'shader -> sampler2D');
    assertNotContains(r.glsl, 'uniform shader', 'no uniform shader');
    assertContains(r.glsl, 'texture(buf,', '.eval -> texture()');
    assertContains(r.glsl, '/ u_resolution', 'coord normalization');
    assert(r.childShaders.length === 1, `1 child shader (got ${r.childShaders.length})`);
    assert(r.childShaders[0] === 'buf', 'child shader name');
    assertNotContains(r.glsl, 'half4', 'no half4');
    assertNotContains(r.glsl, 'float2', 'no float2');

    console.log('  GLSL output:');
    console.log(r.glsl.split('\n').map(l => '    ' + l).join('\n'));
}

// ─── Test 5: sk_FragCoord ───────────────────────────────────────────
console.log('\nTest 5: sk_FragCoord rewrite');
{
    const agsl = `half4 main(float2 p) {
  float2 uv = sk_FragCoord / 400.0;
  return half4(uv.x, uv.y, 0.5, 1.0);
}`;
    const r = transpileAgslToGlsl(agsl);

    assertNotContains(r.glsl, 'sk_FragCoord', 'sk_FragCoord replaced');
    assertContains(r.glsl, 'gl_FragCoord.xy', 'replaced with gl_FragCoord.xy');
}

// ─── Test 6: Already-GLSL types pass through ────────────────────────
console.log('\nTest 6: vec3/vec4 pass through');
{
    const agsl = `half4 main(float2 p) {
  vec3 a = vec3(1.0, 0.0, 0.0);
  vec4 b = vec4(a, 1.0);
  return b;
}`;
    const r = transpileAgslToGlsl(agsl);

    // vec3 and vec4 should remain untouched
    assertContains(r.glsl, 'vec3 a = vec3(', 'vec3 preserved');
    assertContains(r.glsl, 'vec4 b = vec4(', 'vec4 preserved');
}

// ─── Test 7: for loops with int ─────────────────────────────────────
console.log('\nTest 7: for loop');
{
    const agsl = `uniform float iTime;
uniform float2 iResolution;
half4 main(float2 fc) {
  float heat = 0.0;
  for (int i = 0; i < 30; i++) {
    float fi = float(i);
    heat += fi * 0.01;
  }
  return half4(heat, heat, heat, 1.0);
}`;
    const r = transpileAgslToGlsl(agsl);

    assertContains(r.glsl, 'for (int i = 0;', 'for loop preserved');
    assertContains(r.glsl, 'float(i)', 'float cast preserved');
    assertNotContains(r.glsl, 'half4', 'no half4');
}

// ─── Summary ────────────────────────────────────────────────────────
console.log(`\n${'='.repeat(50)}`);
console.log(`Results: ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
