import { transpileAgslToGlsl } from './AgslTranspiler';

const agsl = `
uniform float2 iResolution;
uniform float iTime;
uniform int iMonth;

float gradient(float p)
{
    vec2 pt0 = vec2(0.00,0.0);
    vec2 pt1 = vec2(0.86,0.1);
    if (p < pt0.x) return pt0.y;
    if (p < pt1.x) return mix(pt0.y, pt1.y, (p-pt0.x) / (pt1.x-pt0.x));
    return pt1.y;
}

float waveN(vec2 uv, vec2 s12, vec2 t12, vec2 f12, vec2 h12)
{
    vec2 x12 = sin((iTime * s12 + t12 + uv.x) * f12) * h12;
    float g = gradient(uv.y / (0.5 + x12.x + x12.y));
    return g * 0.27;
}

half4 main(vec2 fragCoord) { 
    vec2 uv = fragCoord.xy / iResolution.xy;
    float month = float(iMonth);
    float pos  = (uv.y - month) * 10 ;
    uv.y = mod(uv.y, 1);
    float waves = waveN(uv, vec2(0.03,0.06), vec2(0.00,0.02), vec2(8.0,3.7), vec2(0.06,0.05));
    
    float x = uv.x;
    float y = abs(uv.y*2-1.0);
    y = 1 - y * y;
    float con = - pos /(1 + abs(pos));
    con = 0.5 * con * con * con;
    float sat = 0.3;
    vec3 base = vec3( sat+con,sat, sat-con);
    vec3 bg = mix(vec3(0.05, 0.05, 0.3), base, (x + y) * 0.55);
    vec3 ac = bg + vec3(1.0, 1.0, 1.0) * waves;

    return vec4(ac, 1.0);
} 
`;

const r = transpileAgslToGlsl(agsl);
console.log(r.glsl);

const lines = r.glsl.split('\n');
let badReturns = 0;
for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes('fragColor =')) {
        console.error(`REWRITTEN RETURN line ${i+1}: ${lines[i].trim()}`);
        badReturns++;
    }
}
console.log(`\n${badReturns} returns rewritten to fragColor`);
