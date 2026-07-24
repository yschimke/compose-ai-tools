/**
 * Stress test: transpile real shader sources from the talk deck.
 *
 * Run:  npx esbuild src/core/shader/AgslTranspiler.stress.ts --bundle --platform=node --format=cjs | node
 */
import { transpileAgslToGlsl } from './AgslTranspiler';

const SHADERS: Record<string, string> = {
    'gradient': `half4 main(float2 p) {\n  float2 uv = p / 400.0;\n  return half4(uv.x, uv.y, 1.0 - uv.x, 1.0);\n}`,

    'plasma': `uniform float iTime;\nuniform float2 iResolution;\nhalf4 main(float2 p) {\n  float2 uv = p / iResolution * 6.28318;\n  float t = iTime * 0.8;\n  float v1 = sin(uv.x + t);\n  float v2 = sin(uv.y + t * 0.7);\n  float v3 = sin(uv.x + uv.y + t * 0.5);\n  float v4 = sin(sqrt(uv.x * uv.x + uv.y * uv.y) + t);\n  float v = (v1 + v2 + v3 + v4) * 0.25;\n  float r = sin(v * 3.14159) * 0.5 + 0.5;\n  float g = sin(v * 3.14159 + 2.094) * 0.5 + 0.5;\n  float b = sin(v * 3.14159 + 4.189) * 0.5 + 0.5;\n  return half4(r, g, b, 1.0);\n}`,

    'feedback_comet': `uniform float iTime;\nuniform float2 iResolution;\nuniform shader buf;\n\nhalf4 main(float2 fc) {\n  half4 prev = buf.eval(fc);\n  vec3 trail = prev.rgb * 0.97;\n  float2 center = iResolution * 0.5;\n  float2 pos = center + float2(cos(iTime * 2.0), sin(iTime * 2.0)) * 120.0;\n  float d = length(fc - pos);\n  float dot = smoothstep(12.0, 6.0, d);\n  vec3 col = max(trail, vec3(1.0, 0.6, 0.0) * dot);\n  return half4(col, 1.0);\n}`,

    'fire': `uniform float iTime;\nuniform float2 iResolution;\nhalf4 main(float2 p) {\n  float2 uv = p / iResolution;\n  uv.y = 1.0 - uv.y;\n  float t = iTime * 0.6;\n  float nx = sin(uv.x * 12.0 + t * 3.0) * 0.02;\n  float base = smoothstep(0.9, 0.3, uv.y + nx);\n  float flicker = sin(uv.x * 8.0 + t * 5.0) * sin(uv.y * 6.0 - t * 4.0) * 0.5 + 0.5;\n  float fire = base * flicker;\n  float r = smoothstep(0.0, 0.6, fire);\n  float g = smoothstep(0.2, 0.9, fire) * 0.7;\n  float b = smoothstep(0.5, 1.0, fire) * 0.3;\n  return half4(r, g, b, 1.0);\n}`,

    'mandelbrot': `uniform float2 iResolution;\nhalf4 main(float2 p) {\n  float2 uv = (p - iResolution * 0.5) / min(iResolution.x, iResolution.y) * 3.0;\n  float2 c = uv + float2(-0.5, 0.0);\n  float2 z = float2(0.0, 0.0);\n  int iter = 0;\n  for (int i = 0; i < 100; i++) {\n    float x = z.x * z.x - z.y * z.y + c.x;\n    float y = 2.0 * z.x * z.y + c.y;\n    z = float2(x, y);\n    if (dot(z, z) > 4.0) break;\n    iter = i;\n  }\n  float t = float(iter) / 100.0;\n  float r = sin(t * 6.28 + 0.0) * 0.5 + 0.5;\n  float g = sin(t * 6.28 + 2.09) * 0.5 + 0.5;\n  float b = sin(t * 6.28 + 4.19) * 0.5 + 0.5;\n  return half4(r, g, b, 1.0);\n}`,
};

let allOk = true;
for (const [name, agsl] of Object.entries(SHADERS)) {
    process.stdout.write(`${name}: `);
    try {
        const r = transpileAgslToGlsl(agsl);
        // Verify no AGSL-specific tokens remain
        const bad = ['half4', 'half3', 'float2', 'float3', 'float4', 'uniform shader', 'sk_FragCoord'];
        const found = bad.filter(b => r.glsl.includes(b));
        if (found.length > 0) {
            console.log(`FAIL — leftover AGSL tokens: ${found.join(', ')}`);
            allOk = false;
        } else if (!r.glsl.includes('void main()')) {
            console.log('FAIL — no void main()');
            allOk = false;
        } else if (!r.glsl.includes('fragColor =')) {
            console.log('FAIL — no fragColor assignment');
            allOk = false;
        } else {
            console.log(`OK (${r.uniforms.length} uniforms, ${r.childShaders.length} child shaders, ${r.glsl.length} chars)`);
        }
    } catch (e: any) {
        console.log(`ERROR — ${e.message}`);
        allOk = false;
    }
}

if (!allOk) { process.exit(1); }
console.log('\nAll shaders transpiled successfully.');
