// Final composite: lens (chromatic aberration, bloom, anamorphic streak, light shafts, flash veil),
// filmic tone map, vignette, then the viewfinder HUD, grain and dither. Writes display sRGB.
// The night look (uNight) turns the picture into Night Sky's red night-vision palette and softens the flash.
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform sampler2D uStreak;
uniform sampler2D uRays;
uniform sampler2D uLabels;
layout(location = 0) out vec4 oColor;

vec3 aces(vec3 x) {
    const mat3 i = mat3(0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777);
    const mat3 o = mat3(1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602);
    vec3 v = i * x;
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return clamp(o * (a / b), 0.0, 1.0);
}
vec3 toSRGB(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

// corner brackets of the autofocus frame (px space); returns coverage
float brackets(vec2 p, vec2 c, vec2 hs, float arm, float th) {
    vec2 d = abs(p - c) - hs;          // distance outside the frame edges
    vec2 q = abs(p - c);
    float onV = step(hs.y - arm, q.y) * (1.0 - smoothstep(th * 0.5 - 0.5, th * 0.5 + 0.5, abs(d.x)));
    float onH = step(hs.x - arm, q.x) * (1.0 - smoothstep(th * 0.5 - 0.5, th * 0.5 + 0.5, abs(d.y)));
    float inside = step(q.x, hs.x + th) * step(q.y, hs.y + th);
    return max(onV, onH) * inside;
}

// Night Sky's screen colours (RED_DARK, RED_DIM, RED), display sRGB
const vec3 NIGHT_DARK = vec3(0.165, 0.031, 0.027);
const vec3 NIGHT_DIM  = vec3(0.604, 0.165, 0.145);
const vec3 NIGHT_RED  = vec3(0.898, 0.224, 0.208);

// display lightness (0..1) -> the red palette; every channel rises with v
vec3 nightRamp(float v) {
    vec3 c = NIGHT_DARK * sat(v / 0.16);
    c = mix(c, NIGHT_DIM, smoothstep(0.16, 0.60, v));
    return mix(c, NIGHT_RED, smoothstep(0.60, 1.0, v));
}

float hudText(vec2 p, vec2 centre, float pxPerEm) {
    vec2 size = uHudSize * pxPerEm;
    vec2 local = (p - centre) / size + 0.5;
    if (local.x < 0.0 || local.x > 1.0 || local.y < 0.0 || local.y > 1.0) return 0.0;
    float sd = (texture(uLabels, uHudUV.xy + local * uHudUV.zw).r - 0.5) * 2.0 * uLabelSpread * pxPerEm;
    return sat(0.5 - sd);
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 uv = frag / uScreen;
    float t = uTime;
    float hero = heroI(t);
    float pap = papSum(t);

    vec2 dc = uv - vec2(0.5, 0.5 + 0.5 * lensShift());
    float ca = 0.0010 + 0.0065 * hero + 0.0015 * pap;
    float soft = mix(1.0, 0.55, uNight);              // the night look keeps the flash glow lower
    vec3 c;
    c.r = texture(uScene, uv - dc * ca).r;
    c.g = texture(uScene, uv).g;
    c.b = texture(uScene, uv + dc * ca).b;
    c += texture(uBloom, uv).rgb * (0.080 + 0.06 * hero) * soft;
    c += texture(uStreak, uv).rgb * vec3(0.32, 0.52, 1.00) * (0.55 - 0.25 * sat(hero)) * soft;
    c += texture(uRays, uv).rgb * vec3(0.50, 0.66, 1.00) * 0.9 * soft;
    c += vec3(0.93, 0.96, 1.00) * (0.10 * pulse(t, HERO_T, 0.010, 0.030) + 0.020 * pap) * mix(1.0, 0.35, uNight);
    c = aces(c * 1.05 * mix(1.0, 0.85, uNight));
    vec2 vq = (uv - 0.5) * vec2(uScreen.x / uScreen.y, 1.0);
    c *= mix(1.0, smoothstep(1.15, 0.15, length(vq) * 1.25), 0.50);
    vec3 s = toSRGB(c);
    if (uNight > 0.0) {
        // like black-and-white film through a red filter: the blue sky drops away, the gold edges lift
        float v = toSRGB(vec3(dot(c, vec3(0.58, 0.36, 0.06)))).x;
        s = mix(s, nightRamp(v), uNight);
    }

    // ---- viewfinder HUD ----
    float u = uScreen.y * 0.001;                       // 1/1000 of the screen height in px
    vec3 ro; mat3 R;
    camera(t, ro, R);
    vec2 blk = projectPx(vec3(0.0, 0.5 * (madeCenterY() + 0.35 * labelScale()) - 0.5 * uNameInk.y * 0.5, 0.0), ro);
    vec2 ext = projectPx(vec3(uNameInk.x * 0.5, madeCenterY() + 0.35 * labelScale(), 0.0), ro) - projectPx(vec3(0.0, -uNameInk.y * 0.5, 0.0), ro);
    vec2 hs = vec2(ext.x, ext.y * 0.5) + vec2(26.0, 30.0) * u;
    float k = easeOutQuart((t - 0.05) / 0.19);
    hs *= mix(1.22, 1.0, k);
    float bA = smoothstep(0.04, 0.09, t) * (1.0 - smoothstep(0.95, 1.25, t));
    float lockFlash = smoothstep(0.235, 0.245, t) * (1.0 - smoothstep(0.36, 0.52, t));
    vec3 bCol = mix(mix(vec3(0.92, 0.95, 1.00), vec3(0.42, 1.00, 0.58), lockFlash),
                    mix(NIGHT_DIM, NIGHT_RED, lockFlash), uNight);
    float br = brackets(frag, blk, hs, 22.0 * u, max(2.2 * u, 1.5));
    s = mix(s, bCol, br * bA * 0.85);

    // exposure readout + progress line
    float pxPerEm = 15.0 * u;
    vec2 hc = vec2(0.5 * uScreen.x, mix(0.085, 0.10, uLandscape) * uScreen.y);
    float hA = smoothstep(0.24, 0.26, t) * (0.75 + 0.25 * step(0.30, t)) * (1.0 - smoothstep(2.02, 2.16, t));
    float txt = hudText(frag, hc, pxPerEm);
    vec3 hudCol = mix(vec3(0.82, 1.00, 0.87), NIGHT_DIM, uNight);
    s = mix(s, hudCol, txt * hA * 0.80);
    float barW = uHudSize.x * pxPerEm * 0.46;
    vec2 bp = frag - (hc - vec2(0.0, 13.0 * u));
    float onBar = (1.0 - smoothstep(0.9 * u, 1.6 * u, abs(bp.y))) * step(abs(bp.x), barW);
    float fill = step(bp.x + barW, 2.0 * barW * sat(t / T_END));
    s = mix(s, hudCol, onBar * hA * mix(0.22, 0.85, fill));

    // grain and dither
    float g = hash12(frag + fract(t * 37.0) * vec2(113.0, 71.0)) - 0.5;
    s += g * 0.030 * (1.0 - 0.6 * s) * mix(vec3(1.0), NIGHT_RED / NIGHT_RED.r, uNight);
    s += (hash12(frag * 1.37 + 5.0) - 0.5) / 255.0;
    s *= mix(0.55, 1.0, smoothstep(0.0, 0.06, t));
    oColor = vec4(s, 1.0);
}
